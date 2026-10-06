package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads lineages, their Approved versions, and whole catalogs. */
@Repository
class Catalogs {
  private final JdbcClient jdbc;

  Catalogs(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every lineage that has an Approved version, with its current one. */
  List<LineageSummary> lineages() {
    return jdbc.sql(
            """
            SELECT l.id, v.name AS vehicle_line, l.model_year, c.id AS catalog_id,
                   c.version_number, a.display_name AS approved_by, c.approved_at
            FROM lineage l
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            JOIN catalog c ON c.id = l.current_catalog_id AND c.status = 'APPROVED'
            JOIN app_user a ON a.id = c.approved_by
            ORDER BY v.name, l.model_year
            """)
        .query(LineageSummary.class)
        .list();
  }

  boolean hasLineage(long id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lineage WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  /** The Approved versions of a lineage, newest first. */
  List<VersionSummary> versions(long lineageId) {
    return jdbc.sql(
            """
            SELECT c.id AS catalog_id, c.version_number, c.name, a.display_name AS approved_by,
                   c.approved_at
            FROM catalog c
            JOIN app_user a ON a.id = c.approved_by
            WHERE c.lineage_id = :lineageId AND c.status = 'APPROVED'
            ORDER BY c.version_number DESC
            """)
        .param("lineageId", lineageId)
        .query(VersionSummary.class)
        .list();
  }

  /**
   * A catalog with its contents: one query for the catalog itself and one for each content table.
   * The queries share one view of the database, so the contents always belong to the revision.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Optional<CatalogView> find(long id) {
    return jdbc.sql(
            """
            SELECT c.id, c.lineage_id, c.status, c.revision, c.name, c.version_number,
                   v.name AS vehicle_line, l.model_year, a.display_name AS approved_by,
                   c.approved_at
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            LEFT JOIN app_user a ON a.id = c.approved_by
            WHERE c.id = :id
            """)
        .param("id", id)
        .query(Header.class)
        .optional()
        .map(
            header ->
                new CatalogView(
                    header.name(),
                    header.versionNumber(),
                    header.vehicleLine(),
                    header.modelYear(),
                    header.approvedBy(),
                    header.approvedAt(),
                    snapshot(header)));
  }

  /**
   * Each label is the one frozen at approval, or the library's current one where none was frozen,
   * which is every label of a working copy.
   */
  private CatalogSnapshot snapshot(Header header) {
    var trims =
        content(
            """
            SELECT c.trim_id AS id, COALESCE(c.approved_name, t.name) AS name,
                   COALESCE(c.approved_sort_order, t.sort_order) AS sort_order
            FROM catalog_trim c
            JOIN trim t ON t.id = c.trim_id
            WHERE c.catalog_id = :id
            ORDER BY sort_order, id
            """,
            header,
            Trim.class);
    var regions =
        content(
            """
            SELECT c.region_code AS code, COALESCE(c.approved_name, r.name) AS name
            FROM catalog_region c
            JOIN region r ON r.code = c.region_code
            WHERE c.catalog_id = :id
            ORDER BY r.sort_order, r.code
            """,
            header,
            Region.class);
    var offerings =
        content(
            "SELECT trim_id, region_code FROM catalog_trim_region WHERE catalog_id = :id",
            header,
            Offering.class);
    var featureRows =
        content(
            """
            SELECT c.feature_id AS id, f.code, COALESCE(c.approved_name, f.name) AS name,
                   COALESCE(c.approved_category_code, f.category_code) AS category_code
            FROM catalog_feature c
            JOIN feature f ON f.id = c.feature_id
            WHERE c.catalog_id = :id
            ORDER BY f.code
            """,
            header,
            FeatureRow.class);
    var cells =
        content(
            """
            SELECT feature_id, trim_id, region_code, availability
            FROM catalog_cell
            WHERE catalog_id = :id
            """,
            header,
            Cell.class);

    return new CatalogSnapshot(
        header.id(),
        header.lineageId(),
        header.status(),
        header.revision(),
        trims,
        regions,
        offerings,
        featureRows,
        cells);
  }

  private <T> List<T> content(String sql, Header of, Class<T> type) {
    return jdbc.sql(sql).param("id", of.id()).query(type).list();
  }

  /** A lineage and its current Approved version, as the dashboard lists them. */
  record LineageSummary(
      long id,
      String vehicleLine,
      int modelYear,
      long catalogId,
      int versionNumber,
      String approvedBy,
      Instant approvedAt) {}

  /** One Approved version in a lineage's list of versions. */
  record VersionSummary(
      long catalogId, int versionNumber, String name, String approvedBy, Instant approvedAt) {}

  /** A catalog as the API shows it: what describes it, and its contents. */
  record CatalogView(
      String name,
      Integer versionNumber,
      String vehicleLine,
      int modelYear,
      String approvedBy,
      Instant approvedAt,
      CatalogSnapshot snapshot) {}

  /** The catalog's own row, joined with what names its lineage and its approver. */
  private record Header(
      long id,
      long lineageId,
      Status status,
      long revision,
      String name,
      Integer versionNumber,
      String vehicleLine,
      int modelYear,
      String approvedBy,
      Instant approvedAt) {}
}
