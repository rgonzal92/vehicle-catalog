package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogAccess.Act;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.Read;
import dev.rgonz.catalog.catalog.Catalogs.Reader;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where every change of a working copy and every decision about it is checked, recorded, and
 * numbered. A write locks its catalog's row, checks that the caller may make it, makes it, records
 * what it did in the catalog's history, and raises the catalog's revision by one, or leaves it
 * where it was when nothing changed. It answers, in the same transaction, with the issues the
 * catalog then has as the caller may see it. Nothing else raises a catalog's revision or writes its
 * history, and a test holds that.
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
                  saved(
                      catalogId,
                      ownerId,
                      () -> lockToEdit(catalogId, ownerId, ifMatch),
                      edit,
                      written -> {});
              saving[0] = System.nanoTime() - started;

              return answer(catalogId, ownerId, revision);
            });
    editTime.record(saving[0], TimeUnit.NANOSECONDS);

    return outcome;
  }

  /**
   * Submits the owner's Draft by the submit given, under the checks and in the order of an edit,
   * and answers with where it led. The submit is given the catalog as its owner sees it, read once
   * the checks have passed, and the answer takes its issues from that same read, since submitting
   * changes no contents; whether it is stale is worked out afresh. A submit always changes
   * something. It is a step of the request's trace as an edit is, and is not timed as one.
   *
   * @param written told the revision the submit led to, in its own transaction
   */
  Outcome submit(long catalogId, long ownerId, String ifMatch, Move submit, LongConsumer written) {
    return transactions.execute(
        transaction -> {
          var read = new CatalogView[1];
          var revision =
              saved(
                  catalogId,
                  ownerId,
                  () -> lockToEdit(catalogId, ownerId, ifMatch),
                  () -> submit.make(read[0] = shownTo(catalogId, ownerId)),
                  written);

          return new Outcome(revision, read[0].issues(), catalogs.stale(catalogId), List.of());
        });
  }

  /**
   * Updates the owner's Draft from Approved by the update given, under the checks and in the order
   * of an edit, and answers with where it led, read again since its contents are new. The update is
   * given the catalog's contents as its owner sees them, read once the checks have passed, with no
   * issues worked out. An update always changes something. It is a step of the request's trace as
   * an edit is, and is not timed as one.
   */
  Outcome updateFromApproved(long catalogId, long ownerId, String ifMatch, Rewrite update) {
    return transactions.execute(
        transaction ->
            answer(
                catalogId,
                ownerId,
                saved(
                    catalogId,
                    ownerId,
                    () -> lockToEdit(catalogId, ownerId, ifMatch),
                    () ->
                        update.make(
                            catalogs
                                .contents(catalogId, new Reader.Person(ownerId, false))
                                .orElseThrow(ApiException::notFound)),
                    written -> {})));
  }

  /**
   * Makes the owner's Submitted catalog a Draft again by the withdrawal given, and answers with
   * where it led. It names no revision: only a Submitted catalog can be withdrawn, and nothing
   * changes one but a withdrawal or a reviewer's decision. The checks come in this order: that the
   * catalog is the caller's, and that it is in status Submitted.
   */
  Outcome withdraw(long catalogId, long ownerId, Edit withdrawal) {
    return transactions.execute(
        transaction ->
            answer(
                catalogId,
                ownerId,
                saved(
                    catalogId,
                    ownerId,
                    () -> lockToWithdraw(catalogId, ownerId),
                    withdrawal,
                    written -> {})));
  }

  /**
   * Approves a Submitted catalog that is not the reviewer's by the approval given, records it, and
   * answers with where it led: the Approved version as the reviewer then sees it. The lineage is
   * locked before the catalog, so that two approvals in one lineage happen one after the other. The
   * checks come in this order: that the reviewer may know of the catalog and it is not their own,
   * that it is Submitted, and that the approval names the revision it is at. The approval is then
   * given the catalog as the reviewer sees it, and the answer takes its issues from that same read,
   * since approving changes no contents; an Approved version is never stale. An approval is a step
   * of the request's trace as an edit is.
   */
  Outcome approve(long catalogId, long reviewerId, String ifMatch, Approval approval) {
    return transactions.execute(
        transaction -> {
          var read = new CatalogView[1];
          var revision =
              decided(
                  catalogId,
                  reviewerId,
                  () -> {
                    lockLineage(catalogId);
                    var submitted = lockToDecide(catalogId, reviewerId, ifMatch);
                    read[0] =
                        catalogs
                            .shown(catalogId, new Reader.Person(reviewerId, true))
                            .orElseThrow(ApiException::notFound);
                    return approval.make(submitted, read[0]);
                  });

          return new Outcome(revision, read[0].issues(), false, List.of());
        });
  }

  /**
   * Runs a reviewer's decision on a Submitted catalog that is not theirs that sends it back to its
   * owner, a rejection, records it, and answers with where it led, as the reviewer may see the
   * catalog then: a Draft is its owner's alone, so with no issues. The checks come in the order of
   * an approval's. A decision is a step of the request's trace as an edit is.
   *
   * @param decide makes the decision, once the checks have passed, and answers with it
   */
  Outcome decide(long catalogId, long reviewerId, String ifMatch, Decide decide) {
    return transactions.execute(
        transaction ->
            answer(
                catalogId,
                reviewerId,
                decided(
                    catalogId,
                    reviewerId,
                    () -> decide.make(lockToDecide(catalogId, reviewerId, ifMatch)))));
  }

  /**
   * Makes a reviewer's decision as one step of the request's trace, records it in the catalog's
   * history, and answers with the revision it raised the catalog to.
   */
  private long decided(long catalogId, long reviewerId, Supplier<Decision> decide) {
    return Catalogs.step(observations, "catalog.save", catalogId)
        .observe(
            () -> {
              var decision = decide.get();
              jdbc.sql(
                      """
                      INSERT INTO catalog_review
                          (catalog_id, reviewer_id, decision, comment)
                      VALUES (:catalog, :reviewer, :decision, :comment)
                      """)
                  .param("catalog", catalogId)
                  .param("reviewer", reviewerId)
                  .param("decision", decision.kind().name())
                  .param("comment", decision.comment())
                  .update();
              return raise(catalogId);
            });
  }

  /**
   * Sends every other Submitted catalog of the lineage back to its owner as a Draft, each with its
   * revision raised and a record that says it was returned: the approval of the one named has made
   * them stale. It is part of that approval, in its transaction.
   */
  void returnTheOthers(long lineageId, long approvedId) {
    jdbc.sql(
            """
            WITH returned AS (
                UPDATE catalog
                SET status = 'DRAFT', revision = revision + 1, updated_at = now()
                WHERE lineage_id = :lineage AND status = 'SUBMITTED' AND id <> :approved
                RETURNING id
            )
            INSERT INTO catalog_review (catalog_id, decision)
            SELECT id, 'RETURNED_STALE' FROM returned
            """)
        .param("lineage", lineageId)
        .param("approved", approvedId)
        .update();
  }

  /**
   * Deletes the owner's Draft by the removal given, under the checks and in the order of an edit. A
   * deleted catalog has no revision left to raise and nothing to answer with. The deletion is a
   * step of the request's trace as an edit is.
   */
  void delete(long catalogId, long ownerId, String ifMatch, Runnable removal) {
    transactions.executeWithoutResult(
        transaction ->
            Catalogs.step(observations, "catalog.save", catalogId)
                .observe(
                    () -> {
                      lockToEdit(catalogId, ownerId, ifMatch);
                      removal.run();
                    }));
  }

  /**
   * Checks, makes, records, and numbers one write as one step of the request's trace, which a
   * refusal leaves marked as failed, and answers with the revision the catalog is at afterwards.
   */
  private long saved(
      long catalogId, long actorId, Supplier<Locked> check, Edit write, LongConsumer written) {
    return Catalogs.step(observations, "catalog.save", catalogId)
        .observe(
            () -> {
              var catalog = check.get();
              var revision = record(catalogId, actorId, catalog.revision(), write.make());
              if (revision != catalog.revision()) {
                written.accept(revision);
              }
              return revision;
            });
  }

  /**
   * Locks the catalog's row until the transaction ends and makes sure the caller may edit the
   * catalog as it is: that it is theirs, that it is in status Draft, and that the edit names the
   * revision it is at, in that order.
   */
  private Locked lockToEdit(long catalogId, long ownerId, String ifMatch) {
    var catalog = lock(catalogId).orElseThrow(ApiException::notFound);
    CatalogAccess.check(Act.EDIT, catalog.ownerId() == ownerId, catalog.status());
    if (catalog.revision() != expectedRevision(ifMatch)) {
      throw ApiException.revisionConflict(catalog.revision());
    }
    return catalog;
  }

  /**
   * Locks the catalog's row until the transaction ends and makes sure the caller may withdraw the
   * catalog: that it is theirs, and that it is in status Submitted, in that order.
   */
  private Locked lockToWithdraw(long catalogId, long ownerId) {
    var catalog = lock(catalogId).orElseThrow(ApiException::notFound);
    CatalogAccess.check(Act.WITHDRAW, catalog.ownerId() == ownerId, catalog.status());
    return catalog;
  }

  /**
   * Locks the catalog's row until the transaction ends and makes sure the reviewer may decide on
   * the catalog as it is: that they may know of it, that it is not their own, that it is Submitted,
   * and that the decision names the revision it is at, in that order. A Draft is its owner's alone,
   * so to a reviewer it is as if it did not exist.
   */
  private Locked lockToDecide(long catalogId, long reviewerId, String ifMatch) {
    var catalog = lock(catalogId).orElseThrow(ApiException::notFound);
    CatalogAccess.check(Act.DECIDE, catalog.ownerId() == reviewerId, catalog.status());
    if (catalog.revision() != expectedRevision(ifMatch)) {
      throw ApiException.revisionConflict(catalog.revision());
    }
    return catalog;
  }

  /** Locks the catalog's row until the transaction ends, if there is a catalog of the id. */
  private Optional<Locked> lock(long catalogId) {
    return jdbc.sql(
            """
            SELECT owner_id, status, revision, base_catalog_id, lineage_id
            FROM catalog WHERE id = :id FOR UPDATE
            """)
        .param("id", catalogId)
        .query(Locked.class)
        .optional();
  }

  /** Locks the row of the catalog's lineage until the transaction ends. */
  private void lockLineage(long catalogId) {
    jdbc.sql(
            """
            SELECT l.id
            FROM lineage l
            JOIN catalog c ON c.lineage_id = l.id
            WHERE c.id = :id
            FOR UPDATE OF l
            """)
        .param("id", catalogId)
        .query(Long.class)
        .optional()
        .orElseThrow(ApiException::notFound);
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

    return raise(catalogId);
  }

  /** Raises the catalog's revision by one and answers with it. */
  private long raise(long catalogId) {
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
    var catalog = catalogs.shown(catalogId, new Reader.Person(callerId, false));

    return new Outcome(
        revision,
        catalog.map(CatalogView::issues).orElseGet(List::of),
        catalog.map(shown -> shown.read().stale()).orElse(false),
        List.of());
  }

  /** The owner's catalog as they see it, which they may always open once its checks have passed. */
  private CatalogView shownTo(long catalogId, long ownerId) {
    return catalogs
        .shown(catalogId, new Reader.Person(ownerId, false))
        .orElseThrow(ApiException::notFound);
  }

  /** A write of a working copy, which makes its changes and answers with them. */
  @FunctionalInterface
  interface Edit {
    List<Change> make();
  }

  /**
   * A move of the owner's Draft, which is given the catalog as its owner sees it and answers with
   * the changes it made.
   */
  @FunctionalInterface
  interface Move {
    List<Change> make(CatalogView catalog);
  }

  /**
   * A write of the owner's Draft that writes its contents anew, which is given the catalog as its
   * owner reads it, without its issues, and answers with the changes it made.
   */
  @FunctionalInterface
  interface Rewrite {
    List<Change> make(Read catalog);
  }

  /**
   * An approval of a Submitted catalog, which is given the catalog's locked row and the catalog as
   * the reviewer sees it, and answers with the decision.
   */
  @FunctionalInterface
  interface Approval {
    Decision make(Locked submitted, CatalogView catalog);
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

  /** What a write checks of a catalog once it has locked it, and what a decision goes on with. */
  record Locked(long ownerId, Status status, long revision, Long baseCatalogId, long lineageId) {}

  /** A reviewer's decision on a Submitted catalog, which makes it and answers with it. */
  @FunctionalInterface
  interface Decide {
    Decision make(Locked submitted);
  }

  /**
   * A reviewer's decision, as the catalog's history keeps it beside its changes, with what the
   * reviewer said: a rejection says why, and an approval may say nothing.
   */
  record Decision(Kind kind, String comment) {
    enum Kind {
      APPROVED,
      REJECTED
    }

    static Decision approved(String comment) {
      return new Decision(Kind.APPROVED, comment);
    }

    static Decision rejected(String comment) {
      return new Decision(Kind.REJECTED, comment);
    }
  }

  /**
   * One change of a working copy, as its history keeps it: its kind, and what it is about. A change
   * names only what its kind is about, and the rest is null. The history keeps them under the keys
   * that {@link ChangeHistory} reads: {@code featureId}, {@code trimId}, {@code regionCode}, {@code
   * old}, and {@code new}.
   *
   * @param before what the change replaced: a cell's availability, a catalog's name, or a rule in
   *     words
   * @param after what it was replaced with, or for a submit the owner's note and for an update the
   *     Approved version it came from
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
      RULE_REMOVED,
      SUBMITTED,
      WITHDRAWN,
      MERGED
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

    /** A submit, with what its owner said to the reviewer, or nothing. */
    static Change submitted(String note) {
      return new Change(Kind.SUBMITTED, null, null, null, null, note);
    }

    static Change withdrawn() {
      return new Change(Kind.WITHDRAWN, null, null, null, null, null);
    }

    /** An update from Approved, with the Approved version it was updated from, in words. */
    static Change merged(String approved) {
      return new Change(Kind.MERGED, null, null, null, null, approved);
    }
  }
}
