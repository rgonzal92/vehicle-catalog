package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
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

  /**
   * What the copy function does, as the statements it is made of. They are sent one by one only
   * here, to measure what the single call saves.
   */
  private static final List<String> COPY_STATEMENTS =
      List.of(
          """
          INSERT INTO catalog_trim (catalog_id, trim_id)
          SELECT :target, trim_id FROM catalog_trim WHERE catalog_id = :source
          """,
          """
          INSERT INTO catalog_region (catalog_id, region_code)
          SELECT :target, region_code FROM catalog_region WHERE catalog_id = :source
          """,
          """
          INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
          SELECT :target, trim_id, region_code FROM catalog_trim_region WHERE catalog_id = :source
          """,
          """
          INSERT INTO catalog_feature (catalog_id, feature_id)
          SELECT :target, feature_id FROM catalog_feature WHERE catalog_id = :source
          """,
          """
          INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
          SELECT :target, feature_id, trim_id, region_code, availability
          FROM catalog_cell
          WHERE catalog_id = :source
          """);

  /** How many copies of each kind are timed, after two that are not. */
  private static final int TIMED = 9;

  @Autowired LargestCatalog largest;
  @Autowired TransactionTemplate transactions;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void aStartThatDoesNotAskForItHasNone() {
    assertThat(count("vehicle_line WHERE code = 'LARGEST_CATALOG'")).isZero();
    assertThat(count("feature")).isEqualTo(330);
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
  void askingForItAgainAnswersWithTheOneThatIsThere() {
    var first = largest.create();
    var catalogs = count("catalog");
    var features = count("feature");

    assertThat(largest.create()).isEqualTo(first);

    assertThat(count("catalog")).isEqualTo(catalogs);
    assertThat(count("feature")).isEqualTo(features).isEqualTo(830);
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
   * read, not a check: the test only makes sure that both ways copy the same rows.
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
    LongConsumer oneByOne =
        target ->
            COPY_STATEMENTS.forEach(
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
    assertThat(callTimes).hasSize(TIMED);
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

    for (var save = -5; save < 60; save++) {
      var feature = "LARGEST_%03d".formatted(1 + Math.floorMod(save * 37, 500));
      var trim = trims.get(Math.floorMod(save, trims.size()));
      var availability = save % 2 == 0 ? "S" : "A";
      var revision = "\"%d\"".formatted(revision(copy));
      var started = System.nanoTime();
      var saved = setCells(ana(), copy, revision, cell(feature, trim, "LARGEST1", availability));
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
    assertThat(times).hasSize(60);
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
        "cells", count("catalog_cell WHERE catalog_id = %d", catalog));
  }

  /** The median, the fastest, and the slowest of the times. */
  private static String summary(List<Double> times) {
    var sorted = times.stream().sorted().toList();
    return "median %.1f, fastest %.1f, slowest %.1f"
        .formatted(sorted.get(sorted.size() / 2), sorted.getFirst(), sorted.getLast());
  }
}
