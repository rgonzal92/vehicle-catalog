package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.library.RuleKind;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads lineages, their Approved versions, and whole catalogs. */
@Repository
class Catalogs {
  /** The condition on a catalog {@code c} under which the person {@code :viewer} may open it. */
  private static final String OPENS_FOR_VIEWER = "(c.status = 'APPROVED' OR c.owner_id = :viewer)";

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
   * Whether the viewer may open the catalog. Everyone opens an Approved version. A working copy is
   * its owner's alone, and to anyone else it is as if it did not exist.
   */
  boolean opensFor(long id, long viewerId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM catalog c WHERE c.id = :id AND %s)"
                .formatted(OPENS_FOR_VIEWER))
        .param("id", id)
        .param("viewer", viewerId)
        .query(Boolean.class)
        .single();
  }

  /**
   * A catalog with its contents, if the viewer may open it: one query for the catalog itself, one
   * for each content table, and one for its rules. The queries share one view of the database, so
   * the contents always belong to the revision.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Optional<CatalogView> find(long id, long viewerId) {
    return jdbc.sql(
            """
            SELECT c.id, c.lineage_id, c.status, c.revision, c.name, c.version_number,
                   v.id AS vehicle_line_id, v.name AS vehicle_line, l.model_year,
                   a.display_name AS approved_by, c.approved_at, c.owner_id = :viewer AS owned,
                   b.id AS base_catalog_id,
                   bl.model_year AS base_model_year, b.version_number AS base_version_number
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            LEFT JOIN app_user a ON a.id = c.approved_by
            LEFT JOIN catalog b ON b.id = c.base_catalog_id
            LEFT JOIN lineage bl ON bl.id = b.lineage_id
            WHERE c.id = :id AND %s
            """
                .formatted(OPENS_FOR_VIEWER))
        .param("id", id)
        .param("viewer", viewerId)
        .query(Header.class)
        .optional()
        .map(
            header ->
                new CatalogView(
                    header.name(),
                    header.versionNumber(),
                    header.vehicleLineId(),
                    header.vehicleLine(),
                    header.modelYear(),
                    header.approvedBy(),
                    header.approvedAt(),
                    header.owned(),
                    header.baseCatalogId() == null
                        ? null
                        : new Base(
                            header.baseCatalogId(),
                            header.baseModelYear(),
                            header.baseVersionNumber()),
                    snapshot(header),
                    List.of()))
        .map(catalog -> catalog.withIssues(Validation.issues(catalog.snapshot(), library(id))));
  }

  /**
   * What validation needs of the library as it is today: which of the catalog's features are
   * retired, which of its trims and regions are inactive, and the global rules with the names of
   * the features they name. It is read with three queries, however many rules there are.
   */
  Validation.Library library(long catalogId) {
    var out =
        jdbc.sql(
                """
                SELECT 'FEATURE' AS kind, f.id::text AS key
                FROM catalog_feature c
                JOIN feature f ON f.id = c.feature_id
                WHERE c.catalog_id = :id AND f.status = 'RETIRED'
                UNION ALL
                SELECT 'TRIM', t.id::text
                FROM catalog_trim c
                JOIN trim t ON t.id = c.trim_id
                WHERE c.catalog_id = :id AND NOT t.active
                UNION ALL
                SELECT 'REGION', r.code
                FROM catalog_region c
                JOIN region r ON r.code = c.region_code
                WHERE c.catalog_id = :id AND NOT r.active
                """)
            .param("id", catalogId)
            .query(OutOfUse.class)
            .list();

    var rules =
        jdbc.sql(
                """
                SELECT r.id, r.kind, r.source_feature_id, r.all_regions, r.pair_key,
                       ARRAY(SELECT t.feature_id FROM global_rule_target t
                             WHERE t.global_rule_id = r.id ORDER BY t.feature_id) AS targets,
                       ARRAY(SELECT s.region_code FROM global_rule_region s
                             WHERE s.global_rule_id = r.id ORDER BY s.region_code) AS regions
                FROM global_rule r
                ORDER BY r.id
                """)
            .query(
                (row, _) ->
                    new Rule(
                        Rule.Origin.GLOBAL,
                        String.valueOf(row.getLong("id")),
                        RuleKind.valueOf(row.getString("kind")),
                        row.getLong("source_feature_id"),
                        List.of((Long[]) row.getArray("targets").getArray()),
                        true,
                        Set.of(),
                        row.getBoolean("all_regions"),
                        Set.of((String[]) row.getArray("regions").getArray()),
                        row.getString("pair_key")))
            .list();
    var names =
        jdbc
            .sql(
                """
                SELECT f.id, f.name
                FROM feature f
                WHERE f.id IN (SELECT source_feature_id FROM global_rule
                               UNION
                               SELECT feature_id FROM global_rule_target)
                """)
            .query(Named.class)
            .list()
            .stream()
            .collect(Collectors.toMap(Named::id, Named::name));

    return new Validation.Library(
        keys(out, "FEATURE").map(Long::valueOf).collect(Collectors.toSet()),
        keys(out, "TRIM").map(Long::valueOf).collect(Collectors.toSet()),
        keys(out, "REGION").collect(Collectors.toSet()),
        rules,
        names);
  }

  /** A feature by its identity and what it is called. */
  private record Named(long id, String name) {}

  private static Stream<String> keys(List<OutOfUse> out, String kind) {
    return out.stream().filter(entry -> entry.kind().equals(kind)).map(OutOfUse::key);
  }

  /** A retired feature or an inactive trim or region of a catalog, by what identifies it. */
  private record OutOfUse(String kind, String key) {}

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
            SELECT c.feature_id AS id, f.code, f.kind, COALESCE(c.approved_name, f.name) AS name,
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
        cells,
        rules(header.id()));
  }

  /**
   * The catalog's own rules, read with one query: each with its targets by their codes, and with
   * the trims and regions it lists in the library's order.
   */
  private List<Rule> rules(long catalogId) {
    return jdbc.sql(
            """
            SELECT r.rule_key, r.kind, r.source_feature_id, r.all_trims, r.all_regions, r.pair_key,
                   ARRAY(SELECT t.feature_id
                         FROM catalog_rule_target t
                         JOIN feature tf ON tf.id = t.feature_id
                         WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                         ORDER BY tf.code) AS targets,
                   ARRAY(SELECT t.trim_id
                         FROM catalog_rule_trim t
                         JOIN trim tr ON tr.id = t.trim_id
                         WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                         ORDER BY tr.sort_order, tr.id) AS trims,
                   ARRAY(SELECT s.region_code
                         FROM catalog_rule_region s
                         JOIN region rg ON rg.code = s.region_code
                         WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key
                         ORDER BY rg.sort_order, rg.code) AS regions
            FROM catalog_rule r
            JOIN feature f ON f.id = r.source_feature_id
            WHERE r.catalog_id = :id
            ORDER BY f.code, r.kind, r.rule_key
            """)
        .param("id", catalogId)
        .query(
            (row, _) ->
                new Rule(
                    Rule.Origin.CATALOG,
                    row.getString("rule_key"),
                    RuleKind.valueOf(row.getString("kind")),
                    row.getLong("source_feature_id"),
                    List.of((Long[]) row.getArray("targets").getArray()),
                    row.getBoolean("all_trims"),
                    new LinkedHashSet<>(List.of((Long[]) row.getArray("trims").getArray())),
                    row.getBoolean("all_regions"),
                    new LinkedHashSet<>(List.of((String[]) row.getArray("regions").getArray())),
                    row.getString("pair_key")))
        .list();
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

  /**
   * A catalog as the API shows it: what describes it, its contents, and its issues.
   *
   * @param owned whether the viewer owns it, which lets them edit it while it is in status Draft
   * @param base the Approved version it was copied from, or null when it started empty
   * @param issues what validation finds in the contents against the library as it is today, Errors
   *     before Warnings
   */
  record CatalogView(
      String name,
      Integer versionNumber,
      long vehicleLineId,
      String vehicleLine,
      int modelYear,
      String approvedBy,
      Instant approvedAt,
      boolean owned,
      Base base,
      CatalogSnapshot snapshot,
      List<Issue> issues) {

    CatalogView withIssues(List<Issue> found) {
      return new CatalogView(
          name,
          versionNumber,
          vehicleLineId,
          vehicleLine,
          modelYear,
          approvedBy,
          approvedAt,
          owned,
          base,
          snapshot,
          found);
    }
  }

  /** A catalog's base. After a carryover its model year is an earlier one than the catalog's. */
  record Base(long catalogId, int modelYear, int versionNumber) {}

  /** The catalog's own row, joined with what names its lineage and its approver. */
  private record Header(
      long id,
      long lineageId,
      Status status,
      long revision,
      String name,
      Integer versionNumber,
      long vehicleLineId,
      String vehicleLine,
      int modelYear,
      String approvedBy,
      Instant approvedAt,
      boolean owned,
      Long baseCatalogId,
      Integer baseModelYear,
      Integer baseVersionNumber) {}
}
