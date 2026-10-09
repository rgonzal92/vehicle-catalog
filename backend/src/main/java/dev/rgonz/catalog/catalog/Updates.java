package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.Current;
import dev.rgonz.catalog.catalog.Diff.Changes;
import dev.rgonz.catalog.catalog.Merge.Conflict;
import dev.rgonz.catalog.catalog.Merge.Side;
import dev.rgonz.catalog.core.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Updates a stale working copy from its lineage's current Approved: a merge of the two over the
 * nearest catalog they both descend from, which brings in what was approved and keeps what the
 * owner changed.
 */
@Service
class Updates {
  private final JdbcClient jdbc;
  private final Catalogs catalogs;

  Updates(JdbcClient jdbc, Catalogs catalogs) {
    this.jdbc = jdbc;
    this.catalogs = catalogs;
  }

  /**
   * What an update from Approved would do to the owner's stale Draft. It saves nothing, and reads
   * the three catalogs from one view of the database.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Preview preview(long catalogId, long ownerId) {
    var catalog =
        catalogs
            .find(catalogId, ownerId)
            .filter(CatalogView::owned)
            .orElseThrow(ApiException::notFound);
    if (catalog.snapshot().status() != Status.DRAFT) {
      throw ApiException.conflict(
          "NOT_DRAFT", "Only a catalog in status Draft can be updated from Approved.");
    }
    if (!catalog.stale()) {
      throw ApiException.conflict(
          "NOT_STALE", "This catalog already has its lineage's current Approved as its base.");
    }
    var merge = merge(catalog, Map.of());

    return new Preview(
        catalog.snapshot().revision(),
        catalog.current(),
        Diff.between(catalog.snapshot(), merge.merged()),
        merge.conflicts());
  }

  /**
   * The merge of a stale working copy with its lineage's current Approved. All three catalogs are
   * named by the library's labels of today, so that nothing reads as two things.
   */
  private Merge.Result merge(CatalogView mine, Map<String, Side> resolutions) {
    var theirs = mine.current().catalogId();
    var base =
        jdbc.sql(
                """
                WITH RECURSIVE theirs AS (
                    SELECT c.id, c.base_catalog_id FROM catalog c WHERE c.id = :theirs
                    UNION ALL
                    SELECT c.id, c.base_catalog_id
                    FROM catalog c
                    JOIN theirs t ON c.id = t.base_catalog_id
                ), mine AS (
                    SELECT c.base_catalog_id AS id, 1 AS distance FROM catalog c WHERE c.id = :mine
                    UNION ALL
                    SELECT c.base_catalog_id, m.distance + 1
                    FROM catalog c
                    JOIN mine m ON c.id = m.id
                )
                SELECT m.id
                FROM mine m
                WHERE m.id IN (SELECT id FROM theirs)
                ORDER BY m.distance
                LIMIT 1
                """)
            .param("theirs", theirs)
            .param("mine", mine.snapshot().catalogId())
            .query(Long.class)
            .optional();

    return Merge.of(
        base.map(catalogs::approvedAsLabelledToday).orElseGet(CatalogSnapshot::empty),
        mine.snapshot(),
        catalogs.approvedAsLabelledToday(theirs),
        resolutions);
  }

  /**
   * What an update from Approved would do to a working copy.
   *
   * @param revision the working copy's revision that the update was worked out from
   * @param approved the Approved version it was worked out against
   * @param taken what the update changes in the working copy without asking: everything it brings
   *     in from the Approved version but the conflicts
   * @param conflicts what the owner has to settle before the update
   */
  record Preview(long revision, Current approved, Changes taken, List<Conflict> conflicts) {}
}
