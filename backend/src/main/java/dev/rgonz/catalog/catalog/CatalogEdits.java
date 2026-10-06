package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
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
  private static final int MOST_CELLS = 500;
  private static final Set<String> AVAILABILITIES = Set.of("S", "A", "N");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final MeterRegistry metrics;
  private final Timer editTime;

  CatalogEdits(JdbcClient jdbc, TransactionTemplate transactions, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.metrics = metrics;
    this.editTime =
        Timer.builder("catalog.edit")
            .description("How long saving an edit of a working copy takes")
            .register(metrics);
  }

  /**
   * Sets cells to the availabilities given, whatever they were, and answers with the catalog's new
   * revision. A cell set to Not offered is no longer stored. Each cell whose availability changed
   * gets a change entry.
   */
  long setCells(long catalogId, long actorId, long expectedRevision, List<CellChange> given) {
    if (given.size() > MOST_CELLS) {
      throw ApiException.limitExceeded("A save sets at most %d cells.".formatted(MOST_CELLS));
    }
    if (given.isEmpty() || !given.stream().allMatch(CellChange::complete)) {
      throw ApiException.invalid(
          "Give at least one cell, each with a feature, a trim, a region, and S, A, or N.");
    }
    // A cell named more than once ends up as it was given last.
    var cells = new LinkedHashMap<List<Object>, CellChange>();
    given.forEach(cell -> cells.put(List.of(cell.featureId, cell.trimId, cell.regionCode), cell));

    return edit(
        catalogId, actorId, expectedRevision, () -> set(catalogId, actorId, cells.values()));
  }

  private void set(long catalogId, long actorId, java.util.Collection<CellChange> cells) {
    var asked =
        cellsOf(
                """
                SELECT g.feature_id, g.trim_id, g.region_code, g.wanted,
                       COALESCE(c.availability, 'N') AS stored,
                       f.feature_id IS NOT NULL AND o.trim_id IS NOT NULL AS in_catalog
                FROM given g
                LEFT JOIN catalog_feature f
                    ON f.catalog_id = :catalog AND f.feature_id = g.feature_id
                LEFT JOIN catalog_trim_region o
                    ON o.catalog_id = :catalog AND o.trim_id = g.trim_id
                        AND o.region_code = g.region_code
                LEFT JOIN catalog_cell c
                    ON c.catalog_id = :catalog AND c.feature_id = g.feature_id
                        AND c.trim_id = g.trim_id AND c.region_code = g.region_code
                ORDER BY g.place
                """,
                catalogId,
                cells.stream()
                    .map(
                        cell ->
                            new AskedCell(
                                cell.featureId,
                                cell.trimId,
                                cell.regionCode,
                                cell.availability,
                                null,
                                false))
                    .toList())
            .query(AskedCell.class)
            .list();
    if (!asked.stream().allMatch(AskedCell::inCatalog)) {
      throw ApiException.invalid(
          "A cell can be set only for a feature row and an offering of this catalog.");
    }
    var changed = asked.stream().filter(cell -> !cell.wanted().equals(cell.stored())).toList();

    cellsOf(
            """
            DELETE FROM catalog_cell c
            USING given g
            WHERE c.catalog_id = :catalog AND c.feature_id = g.feature_id
              AND c.trim_id = g.trim_id AND c.region_code = g.region_code AND g.wanted = 'N'
            """,
            catalogId,
            changed)
        .update();
    cellsOf(
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, feature_id, trim_id, region_code, wanted FROM given WHERE wanted <> 'N'
            ON CONFLICT (catalog_id, feature_id, trim_id, region_code)
                DO UPDATE SET availability = EXCLUDED.availability
            """,
            catalogId,
            changed)
        .update();
    cellsOf(
            """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            SELECT :catalog, :actor, 'CELL_SET',
                   jsonb_build_object('featureId', feature_id, 'trimId', trim_id,
                                      'regionCode', region_code, 'old', stored, 'new', wanted)
            FROM given
            ORDER BY place
            """,
            catalogId,
            changed)
        .param("actor", actorId)
        .update();
  }

  /** A statement over the cells, which it reads as the rows of {@code given}, in their order. */
  private StatementSpec cellsOf(String statement, long catalogId, List<AskedCell> cells) {
    return jdbc.sql(
            """
            WITH given AS (
                SELECT *
                FROM unnest(:features::bigint[], :trims::bigint[], :regions::text[],
                            :wanted::text[], :stored::text[])
                    WITH ORDINALITY AS g (feature_id, trim_id, region_code, wanted, stored, place)
            )
            """
                + statement)
        .param("catalog", catalogId)
        .param("features", cells.stream().map(AskedCell::featureId).toArray(Long[]::new))
        .param("trims", cells.stream().map(AskedCell::trimId).toArray(Long[]::new))
        .param("regions", cells.stream().map(AskedCell::regionCode).toArray(String[]::new))
        .param("wanted", cells.stream().map(AskedCell::wanted).toArray(String[]::new))
        .param("stored", cells.stream().map(AskedCell::stored).toArray(String[]::new));
  }

  /**
   * Runs one edit of a working copy and answers with the catalog's new revision. The catalog's row
   * stays locked from the first check to the commit. Whether the caller may edit the catalog at all
   * is settled before its revision is looked at, so a refusal for the revision tells nobody
   * anything about a catalog that is not theirs. A refused edit changes nothing.
   */
  private long edit(long catalogId, long actorId, long expectedRevision, Runnable change) {
    var timing = Timer.start(metrics);
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
              if (catalog.revision() != expectedRevision) {
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
    timing.stop(editTime);

    return revision;
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

  /**
   * A cell a save asks for, with what the catalog stores for it and whether it is the catalog's.
   */
  private record AskedCell(
      long featureId,
      long trimId,
      String regionCode,
      String wanted,
      String stored,
      boolean inCatalog) {}

  /** What an edit checks of a catalog once it has locked it. */
  private record Locked(long ownerId, Status status, long revision) {}
}
