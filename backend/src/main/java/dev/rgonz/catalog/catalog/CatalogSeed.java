package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.DemoPeople;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a database without catalogs its starting Approved versions, from {@code
 * seed/catalogs.json}. They are owned by the demo author and approved by the demo manager, and each
 * carries the labels the library has when it is loaded. Catalogs are made of library entries, so
 * this runs after the library's own seed.
 */
@Component
@Order(2)
class CatalogSeed implements ApplicationRunner {
  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final DemoPeople demoPeople;

  CatalogSeed(JdbcClient jdbc, JsonMapper json, DemoPeople demoPeople) {
    this.jdbc = jdbc;
    this.json = json;
    this.demoPeople = demoPeople;
  }

  /**
   * Runs once the application has started. The whole load is one transaction, so a file that cannot
   * be loaded leaves no catalogs and the next start tries again.
   */
  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog)").query(Boolean.class).single()) {
      return;
    }
    var owner = demoPeople.idOf(Role.AUTHOR);
    var approver = demoPeople.idOf(Role.MANAGER);

    for (var catalog : read()) {
      try {
        add(catalog, owner, approver);
      } catch (RuntimeException refused) {
        throw new IllegalStateException(
            "seed/catalogs.json: %s could not be loaded".formatted(catalog.title()), refused);
      }
    }
  }

  /** The seeded Approved versions, each after any version it is based on. */
  List<SeededCatalog> read() {
    try (var content = new ClassPathResource("seed/catalogs.json").getInputStream()) {
      return json.readValue(
          content, json.getTypeFactory().constructCollectionType(List.class, SeededCatalog.class));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private void add(SeededCatalog seeded, long owner, long approver) {
    var catalog = addApprovedVersion(seeded, owner, approver);
    var regions = List.copyOf(seeded.offerings().keySet());
    var trims = seeded.offerings().values().stream().flatMap(List::stream).distinct().toList();
    var features = List.copyOf(seeded.features().keySet());

    addAll(
        "regions",
        regions.size(),
        """
        INSERT INTO catalog_region (catalog_id, region_code, approved_name)
        SELECT :catalog, code, name FROM region WHERE code = ANY (:regions)
        """,
        Map.of("catalog", catalog, "regions", regions.toArray(String[]::new)));
    addAll(
        "trims",
        trims.size(),
        """
        INSERT INTO catalog_trim (catalog_id, trim_id, approved_name, approved_sort_order)
        SELECT :catalog, id, name, sort_order FROM trim WHERE name = ANY (:trims)
        """,
        Map.of("catalog", catalog, "trims", trims.toArray(String[]::new)));
    addAll(
        "feature rows",
        features.size(),
        """
        INSERT INTO catalog_feature (catalog_id, feature_id, approved_name, approved_category_code)
        SELECT :catalog, id, name, category_code FROM feature WHERE code = ANY (:features)
        """,
        Map.of("catalog", catalog, "features", features.toArray(String[]::new)));

    var offerings = seeded.offeringsInOrder();
    addAll(
        "offerings",
        offerings.size(),
        """
        INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
        SELECT :catalog, t.id, o.region
        FROM unnest(:trims::text[], :regions::text[]) AS o (trim, region)
        JOIN trim t ON t.name = o.trim
        """,
        Map.of(
            "catalog", catalog,
            "trims", offerings.stream().map(SeededOffering::trim).toArray(String[]::new),
            "regions", offerings.stream().map(SeededOffering::region).toArray(String[]::new)));

    var cells = seeded.cells();
    addAll(
        "cells",
        cells.size(),
        """
        INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
        SELECT :catalog, f.id, t.id, c.region, c.availability
        FROM unnest(:features::text[], :trims::text[], :regions::text[], :availabilities::text[])
                 AS c (feature, trim, region, availability)
        JOIN feature f ON f.code = c.feature
        JOIN trim t ON t.name = c.trim
        """,
        Map.of(
            "catalog", catalog,
            "features", cells.stream().map(SeededCell::feature).toArray(String[]::new),
            "trims", cells.stream().map(cell -> cell.offering().trim()).toArray(String[]::new),
            "regions", cells.stream().map(cell -> cell.offering().region()).toArray(String[]::new),
            "availabilities",
                cells.stream()
                    .map(cell -> String.valueOf(cell.availability()))
                    .toArray(String[]::new)));
  }

  /**
   * Adds the catalog itself, in its lineage, as that lineage's newest and so current Approved
   * version. A lineage begins with its first catalog.
   */
  private long addApprovedVersion(SeededCatalog seeded, long owner, long approver) {
    var lineage =
        jdbc.sql(
                """
                INSERT INTO lineage (vehicle_line_id, model_year)
                SELECT id, :modelYear FROM vehicle_line WHERE code = :vehicleLine
                ON CONFLICT (vehicle_line_id, model_year)
                    DO UPDATE SET model_year = EXCLUDED.model_year
                RETURNING id
                """)
            .param("vehicleLine", seeded.vehicleLine())
            .param("modelYear", seeded.modelYear())
            .query(Long.class)
            .single();
    var catalog =
        jdbc.sql(
                """
                INSERT INTO catalog (lineage_id, name, owner_id, status, base_catalog_id,
                                     version_number, submitted_at, approved_by, approved_at,
                                     created_at, updated_at)
                VALUES (:lineage, :name, :owner, 'APPROVED',
                        (SELECT c.id
                         FROM catalog c
                         JOIN lineage l ON l.id = c.lineage_id
                         JOIN vehicle_line v ON v.id = l.vehicle_line_id
                         WHERE v.code = :vehicleLine AND l.model_year = :baseModelYear
                           AND c.version_number = :baseVersion),
                        :version, :approvedAt, :approver, :approvedAt, :approvedAt, :approvedAt)
                RETURNING id
                """)
            .param("lineage", lineage)
            .param("name", seeded.name())
            .param("owner", owner)
            .param("vehicleLine", seeded.vehicleLine())
            .param("baseModelYear", seeded.base() == null ? null : seeded.base().modelYear())
            .param("baseVersion", seeded.base() == null ? null : seeded.base().version())
            .param("version", seeded.version())
            .param("approvedAt", Timestamp.from(seeded.approvedAt()))
            .param("approver", approver)
            .query(Long.class)
            .single();
    jdbc.sql("UPDATE lineage SET current_catalog_id = :catalog WHERE id = :lineage")
        .param("catalog", catalog)
        .param("lineage", lineage)
        .update();

    return catalog;
  }

  /** Runs one insert for a whole content table and insists that every entry was found. */
  private void addAll(String what, int expected, String sql, Map<String, Object> parameters) {
    var added = jdbc.sql(sql).params(parameters).update();
    if (added != expected) {
      throw new IllegalStateException(
          "%d of %d %s name entries the library does not have"
              .formatted(expected - added, expected, what));
    }
  }

  /**
   * An Approved version as the seed file writes it.
   *
   * @param base the version this one was copied from, in the same vehicle line, if any
   * @param offerings each region's code with the names of the trims sold there, in column order
   * @param features each feature row's code with its cells: one group of characters per region,
   *     separated by a space, and in each group one S, A, or - per trim sold there
   */
  record SeededCatalog(
      String vehicleLine,
      int modelYear,
      int version,
      String name,
      Base base,
      Instant approvedAt,
      Map<String, List<String>> offerings,
      Map<String, String> features) {

    /** The version a seeded one was copied from: its model year and its number there. */
    record Base(int modelYear, int version) {}

    String title() {
      return "%s %d version %d".formatted(vehicleLine, modelYear, version);
    }

    /** The offerings in column order: region by region, and in each the trims sold there. */
    List<SeededOffering> offeringsInOrder() {
      return offerings.entrySet().stream()
          .flatMap(
              region ->
                  region.getValue().stream().map(trim -> new SeededOffering(trim, region.getKey())))
          .toList();
    }

    /** The Standard and Available cells that the feature rows' characters spell out. */
    List<SeededCell> cells() {
      var columns = offeringsInOrder();
      var cells = new ArrayList<SeededCell>();

      features.forEach(
          (feature, row) -> {
            var characters = row.replace(" ", "");
            if (characters.length() != columns.size() || !characters.matches("[SA-]*")) {
              throw new IllegalStateException(
                  "%s needs one S, A, or - for each of the %d offerings, but has \"%s\""
                      .formatted(feature, columns.size(), row));
            }
            for (int column = 0; column < columns.size(); column++) {
              if (characters.charAt(column) != '-') {
                cells.add(new SeededCell(feature, columns.get(column), characters.charAt(column)));
              }
            }
          });

      return cells;
    }
  }

  /** One trim sold in one region, by the names the seed file uses. */
  record SeededOffering(String trim, String region) {}

  /** A Standard (S) or Available (A) cell of a seeded feature row. */
  record SeededCell(String feature, SeededOffering offering, char availability) {}
}
