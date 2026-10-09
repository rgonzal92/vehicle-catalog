package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks that everyone with a role reads the lineages, their Approved versions, and a catalog's
 * contents, on the catalogs a first start seeds. No test here leaves a change behind, so they are
 * seeded once for the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogsIT extends ApplicationIT {
  @Autowired CatalogSeed seed;
  @Autowired Catalogs catalogs;

  @BeforeAll
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void theLineagesListCompactSuv2026WithItsCurrentVersion() {
    var lineages = mvc.get().uri("/api/lineages").with(signedInAs(Role.AUTHOR)).exchange();

    assertThat(lineages).hasStatusOk();
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(lineages, "$"))
        .filteredOn(lineage -> lineage.get("vehicleLine").equals("Compact SUV"))
        .filteredOn(lineage -> lineage.get("modelYear").equals(2026))
        .singleElement()
        .satisfies(
            lineage -> {
              assertThat(lineage).containsEntry("versionNumber", 2);
              assertThat(lineage).containsEntry("approvedBy", "Demo Manager");
              assertThat(lineage).containsEntry("approvedAt", "2025-11-03T15:30:00Z");
              assertThat(lineage).containsEntry("catalogId", (int) version(2));
              assertThat(lineage).containsEntry("id", (int) lineage());
            });
  }

  @Test
  void aLineageListsItsApprovedVersionsNewestFirst() {
    var versions = get(Role.AUTHOR, "/api/lineages/" + lineage() + "/versions");

    assertThat(versions).hasStatusOk();
    assertThat(versions)
        .bodyJson()
        .extractingPath("$[*].versionNumber")
        .asArray()
        .containsExactly(2, 1);
    assertThat(versions)
        .bodyJson()
        .extractingPath("$[*].name")
        .asArray()
        .containsExactly("Hybrid and autumn update", "Launch content");
    assertThat(versions).bodyJson().extractingPath("$[0].approvedBy").isEqualTo("Demo Manager");
    assertThat(versions)
        .bodyJson()
        .extractingPath("$[1].approvedAt")
        .isEqualTo("2025-06-16T14:00:00Z");
    assertThat(versions).bodyJson().extractingPath("$[0].catalogId").isEqualTo((int) version(2));
    assertThat(
            jdbc.sql("SELECT base_catalog_id FROM catalog WHERE id = :id")
                .param("id", version(2))
                .query(Long.class)
                .single())
        .as("version 2 was made from version 1")
        .isEqualTo(version(1));
  }

  @Test
  void aCatalogComesWithWhatDescribesItItsContentsAndItsRevisionAsTheEntityTag() {
    var catalog = get(Role.AUTHOR, "/api/catalogs/" + version(2));

    assertThat(catalog).hasStatusOk().headers().hasValue("ETag", "\"0\"");
    assertThat(catalog).bodyJson().extractingPath("$.name").isEqualTo("Hybrid and autumn update");
    assertThat(catalog).bodyJson().extractingPath("$.versionNumber").isEqualTo(2);
    assertThat(catalog).bodyJson().extractingPath("$.vehicleLine").isEqualTo("Compact SUV");
    assertThat(catalog).bodyJson().extractingPath("$.modelYear").isEqualTo(2026);
    assertThat(catalog).bodyJson().extractingPath("$.approvedBy").isEqualTo("Demo Manager");
    assertThat(catalog)
        .bodyJson()
        .extractingPath("$.snapshot.catalogId")
        .isEqualTo((int) version(2));
    assertThat(catalog)
        .bodyJson()
        .extractingPath("$.snapshot.lineageId")
        .isEqualTo((int) lineage());
    assertThat(catalog).bodyJson().extractingPath("$.snapshot.status").isEqualTo("APPROVED");
    assertThat(catalog).bodyJson().extractingPath("$.snapshot.revision").isEqualTo(0);
    assertThat(ApplicationIT.<List<String>>read(catalog, "$.snapshot.regions[*].name"))
        .as("regions in the library's order")
        .containsExactly("North America", "Europe");
    assertThat(ApplicationIT.<List<String>>read(catalog, "$.snapshot.trims[*].name"))
        .as("trims in trim order")
        .containsExactly("Base", "Sport", "Touring", "Off-Road");
    assertThat(ApplicationIT.<List<Integer>>read(catalog, "$.snapshot.trims[*].sortOrder"))
        .containsExactly(1, 2, 3, 5);
    assertThat(ApplicationIT.<List<Object>>read(catalog, "$.snapshot.offerings")).hasSize(7);
    assertThat(ApplicationIT.<List<Object>>read(catalog, "$.snapshot.featureRows")).hasSize(151);
    assertThat(
            ApplicationIT.<List<Map<String, Object>>>read(
                catalog, "$.snapshot.featureRows[?(@.code == 'ROOF_PANORAMIC')]"))
        .singleElement()
        .satisfies(
            roof -> {
              assertThat(roof).containsEntry("name", "Panoramic Roof");
              assertThat(roof).containsEntry("categoryCode", "EXTERIOR");
            });
  }

  @Test
  void aTrimNotSoldInARegionHasNoOfferingThereAndACellCanDifferBetweenRegions() {
    var catalog = get(Role.AUTHOR, "/api/catalogs/" + version(2));
    var offRoad = id("trim", "name", "Off-Road");
    var sport = id("trim", "name", "Sport");
    var turbo = id("feature", "code", "ENGINE_20T_I4");

    assertThat(
            ApplicationIT.<List<String>>read(
                catalog, "$.snapshot.offerings[?(@.trimId == %d)].regionCode".formatted(offRoad)))
        .as("Off-Road is sold in North America only")
        .containsExactly("NA");
    assertThat(
            ApplicationIT.<List<Map<String, Object>>>read(
                catalog,
                "$.snapshot.cells[?(@.featureId == %d && @.trimId == %d)]".formatted(turbo, sport)))
        .as("the 2.0L turbo is Available on Sport in North America and Not offered in Europe")
        .singleElement()
        .satisfies(
            cell -> {
              assertThat(cell).containsEntry("regionCode", "NA");
              assertThat(cell).containsEntry("availability", "A");
            });
  }

  @Test
  void anEarlierVersionShowsItsOwnContents() {
    var first = get(Role.AUTHOR, "/api/catalogs/" + version(1));

    assertThat(first).bodyJson().extractingPath("$.versionNumber").isEqualTo(1);
    assertThat(ApplicationIT.<List<String>>read(first, "$.snapshot.featureRows[*].code"))
        .hasSize(145)
        .doesNotContain("POWERTRAIN_HYBRID");
  }

  @Test
  void everyRoleReadsApprovedVersionsAndAVisitorWithoutASessionDoesNot() {
    var addresses =
        List.of(
            "/api/lineages",
            "/api/lineages/" + lineage() + "/versions",
            "/api/catalogs/" + version(2));

    for (var address : addresses) {
      for (var role : Role.values()) {
        assertThat(get(role, address)).as("%s as %s", address, role).hasStatusOk();
      }
      assertThat(mvc.get().uri(address)).as(address).hasStatus(401);
    }
  }

  @Test
  void anUnknownCatalogOrLineageIsNotFound() {
    for (var address : List.of("/api/catalogs/987654321", "/api/lineages/987654321/versions")) {
      assertThat(get(Role.ADMIN, address))
          .as(address)
          .hasStatus(404)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NOT_FOUND");
    }
  }

  @Test
  void renamingInTheLibraryLeavesTheLabelsOfAnApprovedVersionAsTheyWere() {
    jdbc.sql("UPDATE trim SET name = 'Entry', sort_order = 9 WHERE name = 'Base'").update();
    jdbc.sql("UPDATE region SET name = 'Americas' WHERE code = 'NA'").update();
    jdbc.sql(
            "UPDATE feature SET name = 'Glass Roof', category_code = 'INTERIOR'"
                + " WHERE code = 'ROOF_PANORAMIC'")
        .update();

    try {
      var catalog = get(Role.AUTHOR, "/api/catalogs/" + version(2));

      assertThat(ApplicationIT.<List<String>>read(catalog, "$.snapshot.trims[*].name"))
          .containsExactly("Base", "Sport", "Touring", "Off-Road");
      assertThat(ApplicationIT.<List<String>>read(catalog, "$.snapshot.regions[*].name"))
          .containsExactly("North America", "Europe");
      assertThat(
              ApplicationIT.<List<String>>read(
                  catalog, "$.snapshot.featureRows[?(@.code == 'ROOF_PANORAMIC')].name"))
          .containsExactly("Panoramic Roof");
      assertThat(
              ApplicationIT.<List<String>>read(
                  catalog, "$.snapshot.featureRows[?(@.code == 'ROOF_PANORAMIC')].categoryCode"))
          .containsExactly("EXTERIOR");
    } finally {
      // The other tests of this class read the library as it was seeded.
      jdbc.sql("UPDATE trim SET name = 'Base', sort_order = 1 WHERE name = 'Entry'").update();
      jdbc.sql("UPDATE region SET name = 'North America' WHERE code = 'NA'").update();
      jdbc.sql(
              "UPDATE feature SET name = 'Panoramic Roof', category_code = 'EXTERIOR'"
                  + " WHERE code = 'ROOF_PANORAMIC'")
          .update();
    }
  }

  @Test
  void everySeededApprovedVersionCarriesTheLabelsItWasApprovedWith() {
    for (var missing :
        List.of(
            "SELECT count(*) FROM catalog_trim"
                + " WHERE approved_name IS NULL OR approved_sort_order IS NULL",
            "SELECT count(*) FROM catalog_region WHERE approved_name IS NULL",
            "SELECT count(*) FROM catalog_feature"
                + " WHERE approved_name IS NULL OR approved_category_code IS NULL")) {
      assertThat(jdbc.sql(missing).query(Long.class).single()).as(missing).isZero();
    }
  }

  @Test
  void seededCatalogsBelongToTheDemoAuthorAndWereApprovedByTheDemoManager() {
    assertThat(
            jdbc.sql(
                    """
                    SELECT DISTINCT o.cognito_sub || ' ' || o.username || ' ' || o.display_name
                               || ', ' || a.cognito_sub || ' ' || a.display_name
                    FROM catalog c
                    JOIN app_user o ON o.id = c.owner_id
                    JOIN app_user a ON a.id = c.approved_by
                    """)
                .query(String.class)
                .list())
        .containsExactly("author demo-author Demo Author, manager Demo Manager");
  }

  @Test
  void startingAgainAddsNoCatalogs() {
    var catalogs = count("catalog");
    var lineages = count("lineage");
    var cells = count("catalog_cell");

    seed.run(new DefaultApplicationArguments());

    assertThat(count("catalog")).isEqualTo(catalogs).isPositive();
    assertThat(count("lineage")).isEqualTo(lineages);
    assertThat(count("catalog_cell")).isEqualTo(cells);
  }

  @Test
  void theDatabaseRefusesCellsOutsideTheirCatalogAndVersionNumbersThatDoNotFitTheStatus() {
    var catalog = version(2);
    var owner = jdbc.sql("SELECT owner_id FROM catalog LIMIT 1").query(Long.class).single();
    var base = id("trim", "name", "Base");
    var offRoad = id("trim", "name", "Off-Road");
    var roof = id("feature", "code", "ROOF_PANORAMIC");
    var motor = id("feature", "code", "MOTOR_SINGLE");
    var refused =
        Map.of(
            "a cell for an offering that does not exist",
            "INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)"
                + " VALUES (%d, %d, %d, 'EU', 'S')".formatted(catalog, roof, offRoad),
            "a cell for a feature that is not a row of the catalog",
            "INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)"
                + " VALUES (%d, %d, %d, 'NA', 'S')".formatted(catalog, motor, base),
            "a cell that is neither Standard nor Available",
            "UPDATE catalog_cell SET availability = 'N' WHERE catalog_id = %d".formatted(catalog),
            "an Approved catalog without a version number",
            "INSERT INTO catalog (lineage_id, name, owner_id, status)"
                + " VALUES (%d, 'No number', %d, 'APPROVED')".formatted(lineage(), owner),
            "an Approved catalog without an approver",
            "UPDATE catalog SET approved_by = NULL WHERE id = %d".formatted(catalog),
            "a working copy with a version number",
            "INSERT INTO catalog (lineage_id, name, owner_id, status, version_number)"
                + " VALUES (%d, 'Numbered draft', %d, 'DRAFT', 7)".formatted(lineage(), owner),
            "a version number repeated in a lineage",
            "INSERT INTO catalog (lineage_id, name, owner_id, status, version_number)"
                + " VALUES (%d, 'Second 2', %d, 'APPROVED', 2)".formatted(lineage(), owner));

    refused.forEach(
        (what, statement) ->
            assertThatThrownBy(() -> jdbc.sql(statement).update())
                .as(what)
                .isInstanceOf(DataIntegrityViolationException.class));
  }

  @Test
  void aCatalogIsLoadedWithAFixedNumberOfQueriesHoweverMuchItHolds() {
    var catalog = version(2);
    var reader = person("a-reader");
    var statements = new ListAppender<ILoggingEvent>();
    var logger = (Logger) LoggerFactory.getLogger(JdbcTemplate.class);
    var level = logger.getLevel();
    statements.start();
    logger.addAppender(statements);
    logger.setLevel(Level.DEBUG);

    try {
      catalogs.find(catalog, reader).orElseThrow();
    } finally {
      logger.setLevel(level);
      logger.detachAppender(statements);
    }

    var sent =
        statements.list.stream()
            // Other threads, such as the one that clears out expired sessions, log here too.
            .filter(event -> event.getThreadName().equals(Thread.currentThread().getName()))
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.startsWith("Executing prepared SQL statement"))
            .toList();
    // Besides the catalog, its five content tables, and its rules: one query for what the library
    // says today of the catalog's features, trims, and regions, and two for the global rules and
    // their names.
    assertThat(sent).hasSize(10);
    var readsTheLibrary = Pattern.compile("\\bUNION ALL\\b").asPredicate();
    assertThat(sent).filteredOn(readsTheLibrary).hasSize(1);
    var contents = sent.stream().filter(readsTheLibrary.negate()).toList();
    for (var table :
        List.of(
            "catalog_trim",
            "catalog_region",
            "catalog_trim_region",
            "catalog_feature",
            "catalog_cell")) {
      var readsTable = Pattern.compile("\\bFROM " + table + "\\b").asPredicate();
      assertThat(contents).as(table).filteredOn(readsTable).hasSize(1);
    }
  }

  private MvcTestResult get(Role as, String address) {
    return mvc.get().uri(address).with(signedInAs(as, "a-reader")).exchange();
  }

  /** The seeded lineage: Compact SUV 2026. */
  private long lineage() {
    return jdbc.sql(
            """
            SELECT l.id
            FROM lineage l
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            WHERE v.code = 'COMPACT_SUV' AND l.model_year = 2026
            """)
        .query(Long.class)
        .single();
  }

  /** The catalog that is the given Approved version of the seeded lineage. */
  private long version(int number) {
    return jdbc.sql(
            "SELECT id FROM catalog WHERE lineage_id = :lineage AND version_number = :number")
        .param("lineage", lineage())
        .param("number", number)
        .query(Long.class)
        .single();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private long id(String table, String column, String value) {
    return jdbc.sql("SELECT id FROM %s WHERE %s = :value".formatted(table, column))
        .param("value", value)
        .query(Long.class)
        .single();
  }
}
