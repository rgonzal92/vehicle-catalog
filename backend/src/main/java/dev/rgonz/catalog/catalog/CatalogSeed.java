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
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * this runs after the library's own seed. Where the library lacks an entry the file names, or a
 * demo account does not say who it is, no catalogs are seeded and the application starts without
 * them.
 *
 * <p>The file lists the versions of each lineage in order, and a version after the one it is based
 * on. A version names its vehicle line by code, its regions by code with the names of the trims
 * sold in each, and its feature rows by code. A feature row's cells are one string: a group of
 * characters for each region, in the regions' order and separated by spaces, and in each group a
 * character for each trim sold there, in the trims' order. S is Standard, A is Available, and - is
 * Not offered.
 */
@Component
@Order(2)
class CatalogSeed implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(CatalogSeed.class);

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
    var catalogs = read();
    var missing = missingFromTheLibrary(catalogs);
    if (!missing.isEmpty()) {
      log.warn("No catalogs were seeded, because the library has no {}", missing);
      return;
    }
    var owner = demoPeople.record(Role.AUTHOR);
    var approver = demoPeople.record(Role.MANAGER);
    if (owner.isEmpty() || approver.isEmpty()) {
      log.warn(
          "No catalogs were seeded, because the author and manager demo accounts each need a"
              + " subject, a username, and a display name in app.demo-accounts");
      return;
    }

    for (var catalog : catalogs) {
      try {
        add(catalog, owner.get(), approver.get());
      } catch (RuntimeException refused) {
        throw new IllegalStateException(
            "seed/catalogs.json: %s could not be loaded".formatted(catalog.title()), refused);
      }
    }
  }

  private List<SeededCatalog> read() {
    try (var content = new ClassPathResource("seed/catalogs.json").getInputStream()) {
      return List.of(json.readValue(content, SeededCatalog[].class));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /** Every library entry the catalogs name that the library does not have, said by its kind. */
  private List<String> missingFromTheLibrary(List<SeededCatalog> catalogs) {
    var missing = new ArrayList<String>();
    missing.addAll(
        unknown(
            "vehicle line",
            "SELECT code FROM vehicle_line",
            catalogs.stream().map(SeededCatalog::vehicleLine)));
    missing.addAll(
        unknown(
            "region",
            "SELECT code FROM region",
            catalogs.stream().flatMap(catalog -> catalog.offerings().keySet().stream())));
    missing.addAll(
        unknown(
            "trim",
            "SELECT name FROM trim",
            catalogs.stream()
                .flatMap(catalog -> catalog.offerings().values().stream())
                .flatMap(List::stream)));
    missing.addAll(
        unknown(
            "feature",
            "SELECT code FROM feature",
            catalogs.stream().flatMap(catalog -> catalog.features().keySet().stream())));

    return missing;
  }

  private List<String> unknown(String kind, String known, Stream<String> named) {
    return jdbc
        .sql(
            """
            SELECT DISTINCT named
            FROM unnest(:named::text[]) AS named
            WHERE named NOT IN (%s)
            ORDER BY named
            """
                .formatted(known))
        .param("named", named.distinct().toArray(String[]::new))
        .query(String.class)
        .list()
        .stream()
        .map(name -> kind + " " + name)
        .toList();
  }

  /** Adds one Approved version: the catalog itself, then each content table in one statement. */
  private void add(SeededCatalog seeded, long owner, long approver) {
    var catalog = addApprovedVersion(seeded, owner, approver);
    var offerings = seeded.offeringsInOrder();
    var cells = seeded.cells();

    jdbc.sql(
            """
            INSERT INTO catalog_region (catalog_id, region_code, approved_name)
            SELECT :catalog, code, name FROM region WHERE code = ANY (:regions)
            """)
        .param("catalog", catalog)
        .param("regions", seeded.offerings().keySet().toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_trim (catalog_id, trim_id, approved_name, approved_sort_order)
            SELECT :catalog, id, name, sort_order FROM trim WHERE name = ANY (:trims)
            """)
        .param("catalog", catalog)
        .param(
            "trims", offerings.stream().map(SeededOffering::trim).distinct().toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
            SELECT :catalog, t.id, o.region
            FROM unnest(:trims::text[], :regions::text[]) AS o (trim, region)
            JOIN trim t ON t.name = o.trim
            """)
        .param("catalog", catalog)
        .param("trims", offerings.stream().map(SeededOffering::trim).toArray(String[]::new))
        .param("regions", offerings.stream().map(SeededOffering::region).toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_feature
                (catalog_id, feature_id, approved_name, approved_category_code)
            SELECT :catalog, id, name, category_code FROM feature WHERE code = ANY (:features)
            """)
        .param("catalog", catalog)
        .param("features", seeded.features().keySet().toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, f.id, t.id, c.region, c.availability
            FROM unnest(:features::text[], :trims::text[], :regions::text[], :availabilities::text[])
                     AS c (feature, trim, region, availability)
            JOIN feature f ON f.code = c.feature
            JOIN trim t ON t.name = c.trim
            """)
        .param("catalog", catalog)
        .param("features", cells.stream().map(SeededCell::feature).toArray(String[]::new))
        .param("trims", cells.stream().map(cell -> cell.offering().trim()).toArray(String[]::new))
        .param(
            "regions", cells.stream().map(cell -> cell.offering().region()).toArray(String[]::new))
        .param(
            "availabilities",
            cells.stream().map(cell -> String.valueOf(cell.availability())).toArray(String[]::new))
        .update();
  }

  /**
   * Adds the catalog itself, in its lineage, whose current Approved version is then the one with
   * the highest number. A lineage begins with its first catalog.
   */
  private long addApprovedVersion(SeededCatalog seeded, long owner, long approver) {
    var lineage =
        jdbc.sql(
                """
                INSERT INTO lineage (vehicle_line_id, model_year)
                SELECT id, :modelYear FROM vehicle_line WHERE code = :vehicleLine
                -- Changes nothing; it is here so that an existing lineage answers with its id too.
                ON CONFLICT (vehicle_line_id, model_year)
                    DO UPDATE SET model_year = EXCLUDED.model_year
                RETURNING id
                """)
            .param("vehicleLine", seeded.vehicleLine())
            .param("modelYear", seeded.modelYear())
            .query(Long.class)
            .single();
    var approvedAt = Timestamp.from(seeded.approvedAt());
    var catalog =
        jdbc.sql(
                """
                INSERT INTO catalog (lineage_id, name, owner_id, status, base_catalog_id,
                                     version_number, submitted_at, approved_by, approved_at,
                                     created_at, updated_at)
                VALUES (:lineage, :name, :owner, 'APPROVED', :base, :version, :approvedAt,
                        :approver, :approvedAt, :approvedAt, :approvedAt)
                RETURNING id
                """)
            .param("lineage", lineage)
            .param("name", seeded.name())
            .param("owner", owner)
            .param("base", baseOf(seeded))
            .param("version", seeded.version())
            .param("approvedAt", approvedAt)
            .param("approver", approver)
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            UPDATE lineage
            SET current_catalog_id =
                (SELECT id FROM catalog WHERE lineage_id = :lineage
                 ORDER BY version_number DESC LIMIT 1)
            WHERE id = :lineage
            """)
        .param("lineage", lineage)
        .update();

    return catalog;
  }

  /** The catalog a seeded version was copied from, which the file lists before it, or null. */
  private Long baseOf(SeededCatalog seeded) {
    var base = seeded.base();
    if (base == null) {
      return null;
    }

    return jdbc.sql(
            """
            SELECT c.id
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            WHERE v.code = :vehicleLine AND l.model_year = :modelYear
              AND c.version_number = :version
            """)
        .param("vehicleLine", seeded.vehicleLine())
        .param("modelYear", base.modelYear())
        .param("version", base.version())
        .query(Long.class)
        .optional()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "its base, model year %d version %d, is not listed before it"
                        .formatted(base.modelYear(), base.version())));
  }

  /**
   * An Approved version as the seed file writes it.
   *
   * @param base the version this one was copied from, in the same vehicle line, if any
   * @param offerings each region's code with the names of the trims sold there
   * @param features each feature row's code with its cells, a character for each offering
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

    /** The offerings in the order a feature row's characters follow: region by region. */
    List<SeededOffering> offeringsInOrder() {
      return offerings.entrySet().stream()
          .flatMap(
              region ->
                  region.getValue().stream().map(trim -> new SeededOffering(trim, region.getKey())))
          .toList();
    }

    /** The Standard and Available cells that the feature rows' characters spell out. */
    List<SeededCell> cells() {
      var inOrder = offeringsInOrder();
      var cells = new ArrayList<SeededCell>();

      features.forEach(
          (feature, row) -> {
            var characters = row.replace(" ", "");
            if (characters.length() != inOrder.size() || !characters.matches("[SA-]*")) {
              throw new IllegalStateException(
                  "%s needs one S, A, or - for each of the %d offerings, but has \"%s\""
                      .formatted(feature, inOrder.size(), row));
            }
            for (int position = 0; position < inOrder.size(); position++) {
              if (characters.charAt(position) != '-') {
                cells.add(
                    new SeededCell(feature, inOrder.get(position), characters.charAt(position)));
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
