package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the largest catalog there can be, 500 feature rows by 96 offerings, which is made on
 * request for tests and measurements, and times a copy of it two ways. Each test starts from the
 * seeded library and catalogs, which a start that does not ask for the largest catalog leaves
 * without it.
 */
class LargestCatalogIT extends WorkingCopyTests {
  private static final Logger log = LoggerFactory.getLogger(LargestCatalogIT.class);

  /** How many copies of each kind are timed, after two that are not. */
  private static final int TIMED = 9;

  /**
   * What the copy function does, as the statements it is made of, read from the migration that
   * defines it. They are sent one by one only here, to measure what the single call saves.
   */
  private static List<String> copyStatements() {
    try (var migration =
        LargestCatalogIT.class.getResourceAsStream("/db/migration/R__copy_catalog.sql")) {
      var function = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
      var body = function.substring(function.indexOf("BEGIN") + 5, function.lastIndexOf("END"));

      return Stream.of(body.split(";"))
          .map(String::strip)
          .filter(statement -> !statement.isEmpty())
          .map(statement -> statement.replace("source_id", ":source"))
          .map(statement -> statement.replace("target_id", ":target"))
          .toList();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  @Autowired LargestCatalog largest;
  @Autowired Catalogs catalogs;
  @Autowired CatalogRules catalogRules;
  @Autowired TransactionTemplate transactions;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void aStartThatDoesNotAskForItHasNone() {
    assertThat(count("vehicle_line WHERE code = 'LARGEST_CATALOG'")).isZero();
    assertThat(count("feature WHERE code LIKE 'LARGEST%%'")).isZero();
  }

  @Test
  void itHasFiveHundredFeatureRowsAndTwelveTrimsSoldInEachOfEightRegions() {
    var id = largest.create();

    var opened = open(ana(), id);
    assertThat(opened)
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.snapshot.status")
        .isEqualTo("APPROVED");
    assertThat(opened).bodyJson().extractingPath("$.vehicleLine").isEqualTo("Largest Catalog");
    assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.trims")).hasSize(12);
    assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.regions")).hasSize(8);
    assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.offerings")).hasSize(96);
    assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.featureRows")).hasSize(500);
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM (
                        SELECT trim_id, region_code FROM catalog_cell
                        WHERE catalog_id = :id AND availability = 'S'
                        INTERSECT
                        SELECT trim_id, region_code FROM catalog_cell
                        WHERE catalog_id = :id AND availability = 'A'
                    ) AS offerings
                    """)
                .param("id", id)
                .query(Long.class)
                .single())
        .as("every offering has Standard and Available cells")
        .isEqualTo(96);
    assertThat(count("catalog_cell WHERE catalog_id = %d", id))
        .as("half of the 48,000 cells are Not offered, which is no stored cell")
        .isEqualTo(24_000);
    assertThat(
            ApplicationIT.<List<String>>read(
                mvc.get().uri("/api/lineages").with(ana()).exchange(), "$[*].vehicleLine"))
        .contains("Largest Catalog");
  }

  @Test
  void itHasAsManyRulesAsACatalogCanHaveAndBreaksNone() {
    var id = largest.create();

    var opened = open(ana(), id);

    assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.rules")).hasSize(500);
    assertThat(
            ApplicationIT.<List<String>>read(
                opened, "$.snapshot.rules[?(@.kind == 'EXCLUDES')].pairKey"))
        .as("50 exclusions, each a pair")
        .hasSize(100)
        .doesNotContainNull();
    assertThat(catalogRules.brokenPairs()).isEmpty();
    assertThat(count("catalog_rule_trim WHERE catalog_id = %d", id))
        .as("some rules cover half of the trims")
        .isPositive();
    assertThat(count("catalog_rule_region WHERE catalog_id = %d", id))
        .as("some rules cover half of the regions")
        .isPositive();
    assertThat(ApplicationIT.<List<Object>>read(opened, "$.issues")).isEmpty();
  }

  @Test
  void askingForItAgainAnswersWithTheOneThatIsThere() {
    var before = count("feature");
    var first = largest.create();
    var catalogs = count("catalog");

    assertThat(largest.create()).isEqualTo(first);

    assertThat(count("catalog")).isEqualTo(catalogs);
    assertThat(count("feature")).isEqualTo(before + 500);
  }

  @Test
  void itIsStillFoundOnceItsVehicleLineHasWorkingCopiesInOtherModelYears() {
    var first = largest.create();
    // A working copy for a later model year is a carryover, in a lineage of its own.
    workingCopy(ana(), "LARGEST_CATALOG", modelYear(first) + 1);
    workingCopy(ana(), "LARGEST_CATALOG", modelYear(first));

    assertThat(largest.create()).isEqualTo(first);
  }

  @Test
  void aWorkingCopyOfItIsCreatedAndEditedLikeAnyOther() {
    var source = largest.create();
    var copy = workingCopy(ana(), "LARGEST_CATALOG", modelYear(source));

    assertThat(rows(copy)).isEqualTo(rows(source));
    var trim =
        jdbc.sql("SELECT name FROM trim WHERE name LIKE 'Largest %' ORDER BY name LIMIT 1")
            .query(String.class)
            .single();
    assertThat(setCells(ana(), copy, "\"0\"", cell("LARGEST_001", trim, "LARGEST1", "S")))
        .hasStatusOk();
  }

  /**
   * Times a copy of the largest catalog as the one call the application makes and as the same
   * statements sent one by one, and writes the times to the log. The times are a measurement to be
   * read, not a check: the test only makes sure that both ways copy as many rows of each kind as
   * the catalog has.
   */
  @Test
  void aCopyIsTimedAsOneCallAndAsTheSameStatementsSentOneByOne() {
    var source = largest.create();
    LongConsumer oneCall =
        target ->
            jdbc.sql("SELECT copy_catalog(:source, :target)")
                .param("source", source)
                .param("target", target)
                .query(row -> {});
    var statements = copyStatements();
    assertThat(statements).as("a statement for each content table and rule table").hasSize(9);
    LongConsumer oneByOne =
        target ->
            statements.forEach(
                statement ->
                    jdbc.sql(statement).param("source", source).param("target", target).update());
    var callTimes = new ArrayList<Double>();
    var statementTimes = new ArrayList<Double>();

    for (var run = -2; run < TIMED; run++) {
      var byCall = timedCopy(source, "call " + run, oneCall);
      var byStatements = timedCopy(source, "statements " + run, oneByOne);
      if (run >= 0) {
        callTimes.add(byCall);
        statementTimes.add(byStatements);
      }
    }

    log.info(
        "Copy of the largest catalog, {} runs each, in milliseconds. One call: {}. Statement by"
            + " statement: {}.",
        TIMED,
        summary(callTimes),
        summary(statementTimes));
  }

  /**
   * Times the save of one cell of a working copy of the largest catalog, as the backend answers it,
   * and writes the times to the log. Like the copy's, they are a measurement to be read.
   */
  @Test
  void aCellSaveIsTimedAtTheLargestSize() {
    var copy = workingCopy(ana(), "LARGEST_CATALOG", modelYear(largest.create()));
    var trims =
        jdbc.sql("SELECT name FROM trim WHERE name LIKE 'Largest %' ORDER BY name")
            .query(String.class)
            .list();
    var times = new ArrayList<Double>();
    var owner = ana();

    for (var save = -5; save < 60; save++) {
      var feature = "LARGEST_%03d".formatted(1 + Math.floorMod(save * 37, 500));
      var trim = trims.get(Math.floorMod(save, trims.size()));
      var cell = cell(feature, trim, "LARGEST1", save % 2 == 0 ? "S" : "A");
      var revision = "\"%d\"".formatted(revision(copy));
      var started = System.nanoTime();
      var saved = setCells(owner, copy, revision, cell);
      var took = (System.nanoTime() - started) / 1_000_000.0;
      assertThat(saved).hasStatusOk();
      if (save >= 0) {
        times.add(took);
      }
    }

    log.info(
        "Save of one cell of the largest catalog, {} saves, in milliseconds: {}, over 50 ms: {}.",
        times.size(),
        summary(times),
        times.stream().filter(time -> time > 50).count());
  }

  /**
   * Times the validation of a working copy of the largest catalog, on its own and together with the
   * reading of the catalog that every edit's answer starts with, and writes the times to the log.
   * Like the others, they are a measurement to be read.
   */
  @Test
  void validationIsTimedAtTheLargestSize() {
    var copy = workingCopy(ana(), "LARGEST_CATALOG", modelYear(largest.create()));
    var owner = person("ana");
    var onItsOwn = new ArrayList<Double>();
    var withTheRead = new ArrayList<Double>();

    for (var run = -10; run < 30; run++) {
      var started = System.nanoTime();
      var read = catalogs.find(copy, owner).orElseThrow();
      var readAndValidated = (System.nanoTime() - started) / 1_000_000.0;
      var library = catalogs.library(copy);
      started = System.nanoTime();
      var issues = Validation.issues(read.snapshot(), library);
      var validated = (System.nanoTime() - started) / 1_000_000.0;
      assertThat(issues).isEqualTo(read.issues()).isEmpty();
      if (run >= 0) {
        onItsOwn.add(validated);
        withTheRead.add(readAndValidated);
      }
    }

    log.info(
        "Validation of the largest catalog, {} runs, in milliseconds. On its own: {}. With the"
            + " reading of the catalog: {}.",
        onItsOwn.size(),
        summary(onItsOwn),
        summary(withTheRead));
  }

  /** The model year of the catalog's lineage. */
  private int modelYear(long catalog) {
    return jdbc.sql(
            """
            SELECT l.model_year FROM lineage l JOIN catalog c ON c.lineage_id = l.id
            WHERE c.id = :id
            """)
        .param("id", catalog)
        .query(Integer.class)
        .single();
  }

  /**
   * Copies the catalog into a new working copy in one transaction and answers with the milliseconds
   * the copy itself took, without the transaction's start and commit.
   */
  private double timedCopy(long source, String name, LongConsumer copy) {
    var owner = person("ana");
    var took = new double[1];
    var target =
        transactions.execute(
            transaction -> {
              var id =
                  jdbc.sql(
                          """
                          INSERT INTO catalog (lineage_id, name, owner_id, base_catalog_id)
                          SELECT lineage_id, :name, :owner, id FROM catalog WHERE id = :source
                          RETURNING id
                          """)
                      .param("name", name)
                      .param("owner", owner)
                      .param("source", source)
                      .query(Long.class)
                      .single();
              var started = System.nanoTime();
              copy.accept(id);
              took[0] = (System.nanoTime() - started) / 1_000_000.0;
              return id;
            });

    assertThat(rows(target)).as(name).isEqualTo(rows(source));
    return took[0];
  }

  /** How many rows each content table holds of the catalog. */
  private Map<String, Long> rows(long catalog) {
    return Map.of(
        "trims", count("catalog_trim WHERE catalog_id = %d", catalog),
        "regions", count("catalog_region WHERE catalog_id = %d", catalog),
        "offerings", count("catalog_trim_region WHERE catalog_id = %d", catalog),
        "feature rows", count("catalog_feature WHERE catalog_id = %d", catalog),
        "cells", count("catalog_cell WHERE catalog_id = %d", catalog),
        "rules", count("catalog_rule WHERE catalog_id = %d", catalog),
        "rule targets", count("catalog_rule_target WHERE catalog_id = %d", catalog),
        "rule trims", count("catalog_rule_trim WHERE catalog_id = %d", catalog),
        "rule regions", count("catalog_rule_region WHERE catalog_id = %d", catalog));
  }

  /** The median, the fastest, and the slowest of the times. */
  private static String summary(List<Double> times) {
    var sorted = times.stream().sorted().toList();
    return "median %.1f, fastest %.1f, slowest %.1f"
        .formatted(sorted.get(sorted.size() / 2), sorted.getFirst(), sorted.getLast());
  }
}
