package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edits working copies. Only a catalog in status Draft can be edited, and only by its owner. Every
 * edit names the revision it was made from and runs in one transaction that locks the catalog, so
 * edits of one catalog happen one after another and none overwrites a change it has not seen.
 */
@Service
class CatalogEdits {
  /** The most cells one save sets. */
  private static final int MOST_CELLS = 500;

  /** What a save may set a cell to: S, A, or N. */
  private static final Set<String> AVAILABILITIES =
      Arrays.stream(Availability.values()).map(Enum::name).collect(Collectors.toSet());

  /** A revision as {@code If-Match} carries it: the number in quotes, as the entity tag gave it. */
  private static final Pattern REVISION = Pattern.compile("\"(\\d{1,18})\"");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Timer editTime;

  CatalogEdits(JdbcClient jdbc, TransactionTemplate transactions, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.editTime =
        Timer.builder("catalog.edit")
            .description("How long saving an edit of a working copy takes")
            .register(metrics);
  }

  /**
   * Sets cells to the availabilities given, whatever they were, and answers with the catalog's new
   * revision. A cell set to Not offered is no longer stored. Each cell whose availability changed
   * gets a change entry.
   *
   * @param ifMatch the revision the edit was made from, as its {@code If-Match} header gave it
   */
  long setCells(long catalogId, long actorId, String ifMatch, List<CellChange> given) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          if (given.size() > MOST_CELLS) {
            throw ApiException.limitExceeded("A save sets at most %d cells.".formatted(MOST_CELLS));
          }
          if (given.isEmpty()
              || !given.stream().allMatch(cell -> cell != null && cell.complete())) {
            throw ApiException.invalid(
                "Give at least one cell, each with a feature, a trim, a region, and S, A, or N.");
          }
          // A cell named more than once ends up as it was given last.
          var cells = new LinkedHashMap<List<Object>, CellChange>();
          given.forEach(
              cell -> cells.put(List.of(cell.featureId(), cell.trimId(), cell.regionCode()), cell));

          store(catalogId, actorId, cells.values());
        });
  }

  /**
   * Stores the cells, each named once, and records every one whose availability changes. They have
   * to be cells of the catalog's own feature rows and offerings.
   */
  private void store(long catalogId, long actorId, Collection<CellChange> cells) {
    var outside =
        over(
                cells,
                """
                SELECT count(*)
                FROM given g
                WHERE NOT EXISTS (
                        SELECT 1 FROM catalog_feature f
                        WHERE f.catalog_id = :catalog AND f.feature_id = g.feature_id)
                   OR NOT EXISTS (
                        SELECT 1 FROM catalog_trim_region o
                        WHERE o.catalog_id = :catalog AND o.trim_id = g.trim_id
                          AND o.region_code = g.region_code)
                """)
            .param("catalog", catalogId)
            .query(Long.class)
            .single();
    if (outside > 0) {
      throw ApiException.invalid(
          "A cell can be set only for a feature row and an offering of this catalog.");
    }

    // The history is written first, while the cells still hold their old values.
    over(
            cells,
            """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            SELECT :catalog, :actor, 'CELL_SET',
                   jsonb_build_object('featureId', g.feature_id, 'trimId', g.trim_id,
                                      'regionCode', g.region_code,
                                      'old', COALESCE(c.availability, 'N'), 'new', g.wanted)
            FROM given g
            LEFT JOIN catalog_cell c
                ON c.catalog_id = :catalog AND c.feature_id = g.feature_id
                    AND c.trim_id = g.trim_id AND c.region_code = g.region_code
            WHERE COALESCE(c.availability, 'N') <> g.wanted
            ORDER BY g.place
            """)
        .param("catalog", catalogId)
        .param("actor", actorId)
        .update();
    over(
            cells,
            """
            DELETE FROM catalog_cell c
            USING given g
            WHERE c.catalog_id = :catalog AND c.feature_id = g.feature_id
              AND c.trim_id = g.trim_id AND c.region_code = g.region_code AND g.wanted = 'N'
            """)
        .param("catalog", catalogId)
        .update();
    over(
            cells,
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, feature_id, trim_id, region_code, wanted FROM given WHERE wanted <> 'N'
            ON CONFLICT (catalog_id, feature_id, trim_id, region_code)
                DO UPDATE SET availability = EXCLUDED.availability
            """)
        .param("catalog", catalogId)
        .update();
  }

  /** A statement over the cells, which it reads as the rows of {@code given}, in their order. */
  private StatementSpec over(Collection<CellChange> cells, String statement) {
    return jdbc.sql(
            """
            WITH given AS (
                SELECT *
                FROM unnest(:features::bigint[], :trims::bigint[], :regions::text[], :wanted::text[])
                    WITH ORDINALITY AS g (feature_id, trim_id, region_code, wanted, place)
            )
            """
                + statement)
        .param("features", cells.stream().map(CellChange::featureId).toArray(Long[]::new))
        .param("trims", cells.stream().map(CellChange::trimId).toArray(Long[]::new))
        .param("regions", cells.stream().map(CellChange::regionCode).toArray(String[]::new))
        .param("wanted", cells.stream().map(CellChange::availability).toArray(String[]::new));
  }

  /**
   * Runs one edit of a working copy and answers with the catalog's new revision. The catalog's row
   * stays locked from the first check to the commit. The checks come in one order for every edit:
   * that the catalog is the caller's, that it is in status Draft, that the edit names the revision
   * the catalog is at, and only then whatever the change itself asks. So no refusal tells anybody
   * anything about a catalog that is not theirs. A refused edit changes nothing.
   *
   * <p>The time is taken through the commit, and only of an edit that is saved.
   */
  private long edit(long catalogId, long actorId, String ifMatch, Runnable change) {
    var started = System.nanoTime();
    var revision =
        transactions.execute(
            transaction -> {
              var catalog =
                  jdbc.sql(
                          "SELECT owner_id, status, revision FROM catalog WHERE id = :id FOR UPDATE")
                      .param("id", catalogId)
                      .query(Locked.class)
                      .optional()
                      .filter(found -> found.ownerId() == actorId)
                      .orElseThrow(ApiException::notFound);
              if (catalog.status() != Status.DRAFT) {
                throw ApiException.conflict(
                    "NOT_DRAFT", "Only a catalog in status Draft can be edited.");
              }
              if (catalog.revision() != expectedRevision(ifMatch)) {
                throw ApiException.revisionConflict(catalog.revision());
              }

              change.run();

              return jdbc.sql(
                      """
                      UPDATE catalog SET revision = revision + 1, updated_at = now()
                      WHERE id = :id
                      RETURNING revision
                      """)
                  .param("id", catalogId)
                  .query(Long.class)
                  .single();
            });
    editTime.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);

    return revision;
  }

  /** The revision an edit says it was made from. */
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

  /** One cell of a save: the availability a feature is to have in an offering. */
  record CellChange(Long featureId, Long trimId, String regionCode, String availability) {
    boolean complete() {
      return featureId != null
          && trimId != null
          && regionCode != null
          && AVAILABILITIES.contains(availability);
    }
  }

  /** What an edit checks of a catalog once it has locked it. */
  private record Locked(long ownerId, Status status, long revision) {}
}
