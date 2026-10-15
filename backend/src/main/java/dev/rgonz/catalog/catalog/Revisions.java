package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where every change of a working copy is checked, recorded, and numbered. A write locks its
 * catalog's row, checks that the caller may make it, makes it, records the changes it made in the
 * catalog's history, and raises the catalog's revision by one, or leaves it where it was when
 * nothing changed. It answers, in the same transaction, with the issues the catalog then has as the
 * caller may see it.
 */
@Component
class Revisions {
  /** A revision as {@code If-Match} carries it: the number in quotes, as the entity tag gave it. */
  private static final Pattern REVISION = Pattern.compile("\"(\\d{1,18})\"");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Catalogs catalogs;
  private final ObservationRegistry observations;
  private final Timer editTime;

  Revisions(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      Catalogs catalogs,
      ObservationRegistry observations,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.catalogs = catalogs;
    this.observations = observations;
    this.editTime =
        Timer.builder("catalog.edit")
            .description("How long saving an edit of a working copy takes")
            .register(metrics);
  }

  /**
   * Runs one edit of the owner's Draft and answers with where it led. An edit that asks for what is
   * already so makes no changes: the revision stays where it was, which tells the caller so, and
   * nothing is recorded. The catalog's row stays locked from the first check to the commit. The
   * checks come in one order for every edit: that the catalog is the caller's, that it is in status
   * Draft, that the edit names the revision the catalog is at, and only then whatever the edit
   * itself asks. So no refusal tells anybody anything about a catalog that is not theirs. A refused
   * edit changes nothing. From the lock until the revision is raised the save is one step of the
   * request's trace, which a refusal leaves marked as failed, and the answer's reading of the
   * catalog follows it as steps of their own.
   *
   * <p>The time of the step is taken, and only of an edit that is saved. Working out the answer's
   * issues is timed as validation, and the commit after it is not timed.
   *
   * @param ifMatch the revision the edit was made from, as its {@code If-Match} header gave it
   * @param edit makes the edit and answers with the changes it made, in the order they were made
   */
  Outcome edit(long catalogId, long ownerId, String ifMatch, Edit edit) {
    var saving = new long[1];
    var outcome =
        transactions.execute(
            transaction -> {
              var started = System.nanoTime();
              var revision =
                  Catalogs.step(observations, "catalog.save", catalogId)
                      .observe(
                          () -> {
                            var catalog = lockToEdit(catalogId, ownerId, ifMatch);
                            return record(catalogId, ownerId, catalog.revision(), edit.make());
                          });
              saving[0] = System.nanoTime() - started;

              return answer(catalogId, ownerId, revision);
            });
    editTime.record(saving[0], TimeUnit.NANOSECONDS);

    return outcome;
  }

  /**
   * Locks the catalog's row until the transaction ends and makes sure the caller may edit the
   * catalog as it is: that it is theirs, that it is in status Draft, and that the edit names the
   * revision it is at, in that order.
   */
  Locked lockToEdit(long catalogId, long ownerId, String ifMatch) {
    var catalog =
        jdbc.sql("SELECT owner_id, status, revision FROM catalog WHERE id = :id FOR UPDATE")
            .param("id", catalogId)
            .query(Locked.class)
            .optional()
            .filter(found -> found.ownerId() == ownerId)
            .orElseThrow(ApiException::notFound);
    if (catalog.status() != Status.DRAFT) {
      throw ApiException.conflict("NOT_DRAFT", "Only a catalog in status Draft can be edited.");
    }
    if (catalog.revision() != expectedRevision(ifMatch)) {
      throw ApiException.revisionConflict(catalog.revision());
    }
    return catalog;
  }

  /** The revision a write says it was made from. */
  private static long expectedRevision(String ifMatch) {
    if (ifMatch == null) {
      throw ApiException.revisionRequired();
    }
    var revision = REVISION.matcher(ifMatch.strip());
    if (!revision.matches()) {
      throw ApiException.badRequest(
          "If-Match takes the catalog's revision in quotes, as in \"42\".");
    }
    return Long.parseLong(revision.group(1));
  }

  /**
   * Records the changes in the catalog's history, in their order, and answers with the revision the
   * catalog is at afterwards: one more than it was, or the same when there are none.
   */
  private long record(long catalogId, long actorId, long revision, List<Change> changes) {
    if (changes.isEmpty()) {
      return revision;
    }
    jdbc.sql(
            """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            SELECT :catalog, :actor, c.kind,
                   jsonb_strip_nulls(jsonb_build_object(
                       'featureId', c.feature_id, 'trimId', c.trim_id,
                       'regionCode', c.region_code, 'old', c.old, 'new', c.new))
            FROM unnest(:kinds::text[], :features::bigint[], :trims::bigint[], :regions::text[],
                        :olds::text[], :news::text[])
                WITH ORDINALITY AS c (kind, feature_id, trim_id, region_code, old, new, place)
            ORDER BY c.place
            """)
        .param("catalog", catalogId)
        .param("actor", actorId)
        .param("kinds", each(changes, change -> change.kind().name(), String[]::new))
        .param("features", each(changes, Change::featureId, Long[]::new))
        .param("trims", each(changes, Change::trimId, Long[]::new))
        .param("regions", each(changes, Change::regionCode, String[]::new))
        .param("olds", each(changes, Change::before, String[]::new))
        .param("news", each(changes, Change::after, String[]::new))
        .update();

    return jdbc.sql(
            """
            UPDATE catalog SET revision = revision + 1, updated_at = now()
            WHERE id = :id
            RETURNING revision
            """)
        .param("id", catalogId)
        .query(Long.class)
        .single();
  }

  private static <T> T[] each(
      List<Change> changes, Function<Change, T> part, IntFunction<T[]> array) {
    return changes.stream().map(part).toArray(array);
  }

  /**
   * What a write answers with: the revision, and the catalog's issues and whether it is stale, as
   * the caller may see the catalog now. A catalog the caller may no longer see has neither.
   */
  private Outcome answer(long catalogId, long callerId, long revision) {
    var catalog = catalogs.find(catalogId, callerId);

    return new Outcome(
        revision,
        catalog.map(CatalogView::issues).orElseGet(List::of),
        catalog.map(CatalogView::stale).orElse(false),
        List.of());
  }

  /** An edit of a working copy, which makes its changes and answers with them. */
  @FunctionalInterface
  interface Edit {
    List<Change> make();
  }

  /**
   * Where a write led: the revision the catalog is at afterwards, which is the one it was made from
   * when it changed nothing; the issues the catalog then has and whether it is stale, as the caller
   * may see it; and the rules deleted along the way, each in words and a pair once.
   */
  record Outcome(long revision, List<Issue> issues, boolean stale, List<String> rulesDeleted) {
    Outcome withRulesDeleted(List<String> deleted) {
      return new Outcome(revision, issues, stale, deleted);
    }
  }

  /** What a write checks of a catalog once it has locked it. */
  record Locked(long ownerId, Status status, long revision) {}

  /**
   * One change of a working copy, as its history keeps it: its kind, and what it is about. A change
   * names only what its kind is about, and the rest is null. The history keeps them under the keys
   * that {@link ChangeHistory} reads: {@code featureId}, {@code trimId}, {@code regionCode}, {@code
   * old}, and {@code new}.
   *
   * @param before what the change replaced: a cell's availability, a catalog's name, or a rule in
   *     words
   * @param after what it was replaced with
   */
  record Change(
      Kind kind, Long featureId, Long trimId, String regionCode, String before, String after) {

    enum Kind {
      CELL_SET,
      TRIM_ADDED,
      TRIM_REMOVED,
      REGION_ADDED,
      REGION_REMOVED,
      OFFERING_ADDED,
      OFFERING_REMOVED,
      FEATURE_ADDED,
      FEATURE_REMOVED,
      RENAMED,
      RULE_ADDED,
      RULE_UPDATED,
      RULE_REMOVED
    }

    static Change cellSet(long featureId, long trimId, String regionCode, String was, String is) {
      return new Change(Kind.CELL_SET, featureId, trimId, regionCode, was, is);
    }

    static Change trimAdded(long trimId) {
      return new Change(Kind.TRIM_ADDED, null, trimId, null, null, null);
    }

    static Change trimRemoved(long trimId) {
      return new Change(Kind.TRIM_REMOVED, null, trimId, null, null, null);
    }

    static Change regionAdded(String regionCode) {
      return new Change(Kind.REGION_ADDED, null, null, regionCode, null, null);
    }

    static Change regionRemoved(String regionCode) {
      return new Change(Kind.REGION_REMOVED, null, null, regionCode, null, null);
    }

    static Change offeringAdded(long trimId, String regionCode) {
      return new Change(Kind.OFFERING_ADDED, null, trimId, regionCode, null, null);
    }

    static Change offeringRemoved(long trimId, String regionCode) {
      return new Change(Kind.OFFERING_REMOVED, null, trimId, regionCode, null, null);
    }

    static Change featureAdded(long featureId) {
      return new Change(Kind.FEATURE_ADDED, featureId, null, null, null, null);
    }

    static Change featureRemoved(long featureId) {
      return new Change(Kind.FEATURE_REMOVED, featureId, null, null, null, null);
    }

    static Change renamed(String was, String is) {
      return new Change(Kind.RENAMED, null, null, null, was, is);
    }

    static Change ruleAdded(String rule) {
      return new Change(Kind.RULE_ADDED, null, null, null, null, rule);
    }

    static Change ruleUpdated(String was, String is) {
      return new Change(Kind.RULE_UPDATED, null, null, null, was, is);
    }

    static Change ruleRemoved(String rule) {
      return new Change(Kind.RULE_REMOVED, null, null, null, rule, null);
    }
  }
}
