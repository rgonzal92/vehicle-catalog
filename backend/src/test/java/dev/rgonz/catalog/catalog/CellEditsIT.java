package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that the owner of a working copy in status Draft sets cells, that each save names the
 * revision it was made from, and that every change is recorded. Each test starts from the seeded
 * catalogs and a working copy of Compact SUV 2026 that Ana owns.
 */
class CellEditsIT extends WorkingCopyTests {
  @Autowired MeterRegistry metrics;
  @Autowired TransactionTemplate transactions;

  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
  }

  @Test
  void theOwnerSetsACellAndTheSaveAnswersWithTheNewRevision() {
    assertThat(stored("TRANS_MANUAL", "Base", "NA")).isEqualTo("N");

    var saved = setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));

    assertThat(saved).hasStatusOk().headers().hasValue("ETag", "\"1\"");
    assertThat(saved).bodyJson().extractingPath("$.revision").isEqualTo(1);
    assertThat(stored("TRANS_MANUAL", "Base", "NA")).isEqualTo("A");
    var opened = open(ana(), copy);
    assertThat(opened).headers().hasValue("ETag", "\"1\"");
    assertThat(
            ApplicationIT.<List<String>>read(
                opened,
                "$.snapshot.cells[?(@.featureId == %d && @.trimId == %d && @.regionCode == 'NA')]"
                        .formatted(feature("TRANS_MANUAL"), trim("Base"))
                    + ".availability"))
        .containsExactly("A");
  }

  @Test
  void theSameFeatureAndTrimCanBeStandardInOneRegionAndNotOfferedInAnother() {
    var saved =
        setCells(
            ana(),
            copy,
            "\"0\"",
            cell("ROOF_PANORAMIC", "Sport", "NA", "S"),
            cell("ROOF_PANORAMIC", "Sport", "EU", "N"),
            cell("ENGINE_15T_I4", "Base", "EU", "N"));

    assertThat(saved).hasStatusOk();
    assertThat(stored("ROOF_PANORAMIC", "Sport", "NA")).isEqualTo("S");
    assertThat(stored("ROOF_PANORAMIC", "Sport", "EU")).isEqualTo("N");
    assertThat(stored("ENGINE_15T_I4", "Base", "NA")).isEqualTo("S");
    assertThat(stored("ENGINE_15T_I4", "Base", "EU")).isEqualTo("N");
  }

  @Test
  void settingACellToNotOfferedRemovesIt() {
    assertThat(stored("ENGINE_15T_I4", "Base", "NA")).isEqualTo("S");
    var cells = count("catalog_cell WHERE catalog_id = %d", copy);

    assertThat(setCells(ana(), copy, "\"0\"", cell("ENGINE_15T_I4", "Base", "NA", "N")))
        .hasStatusOk();

    assertThat(stored("ENGINE_15T_I4", "Base", "NA")).isEqualTo("N");
    assertThat(count("catalog_cell WHERE catalog_id = %d", copy)).isEqualTo(cells - 1);
  }

  @Test
  void aSaveWithoutARevisionOrWithOneThatIsNoRevisionChangesNothing() {
    var change = cell("TRANS_MANUAL", "Base", "NA", "A");

    assertThat(setCells(ana(), copy, null, change))
        .hasStatus(428)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("REVISION_REQUIRED");
    for (var malformed : List.of("0", "W/\"0\"", "\"zero\"", "*", "\"-1\"", "\"0\", \"1\"")) {
      assertThat(setCells(ana(), copy, malformed, change))
          .as(malformed)
          .hasStatus(400)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("BAD_REQUEST");
    }

    assertNothingChanged(copy);
  }

  @Test
  void aSaveFromAnEarlierRevisionIsARevisionConflictThatNamesTheCurrentOne() {
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    var changes = count("catalog_change WHERE catalog_id = %d", copy);

    var refused = setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "S"));

    assertThat(refused)
        .hasStatus(412)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("REVISION_CONFLICT");
    assertThat(refused).bodyJson().extractingPath("$.revision").isEqualTo(1);
    assertThat(stored("TRANS_MANUAL", "Base", "NA")).isEqualTo("A");
    assertThat(revision(copy)).isEqualTo(1);
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isEqualTo(changes);
    assertThat(setCells(ana(), copy, "\"7\"", cell("TRANS_MANUAL", "Base", "NA", "S")))
        .as("a revision the catalog has not reached yet is no better")
        .hasStatus(412);
  }

  @Test
  void ofTwoClientsThatLoadedTheSameRevisionTheSecondToSaveIsRefused() {
    var first = open(ana(), copy).getResponse().getHeader("ETag");
    var second = open(ana(), copy).getResponse().getHeader("ETag");

    assertThat(setCells(ana(), copy, first, cell("TRANS_MANUAL", "Base", "NA", "A"))).hasStatusOk();
    assertThat(setCells(ana(), copy, second, cell("ROOF_PANORAMIC", "Sport", "NA", "S")))
        .hasStatus(412);

    assertThat(stored("ROOF_PANORAMIC", "Sport", "NA")).isEqualTo("N");
  }

  @Test
  void aDelayedRepeatOfAnEarlierSaveCannotOverwriteALaterEdit() {
    var earlier = cell("TRANS_MANUAL", "Base", "NA", "A");
    setCells(ana(), copy, "\"0\"", earlier);
    setCells(ana(), copy, "\"1\"", cell("TRANS_MANUAL", "Base", "NA", "S"));

    assertThat(setCells(ana(), copy, "\"0\"", earlier)).hasStatus(412);

    assertThat(stored("TRANS_MANUAL", "Base", "NA")).isEqualTo("S");
    assertThat(revision(copy)).isEqualTo(2);
  }

  @Test
  void anyoneButTheOwnerFindsNothingToEdit() {
    var change = cell("TRANS_MANUAL", "Base", "NA", "A");

    for (var role : Role.values()) {
      for (var revision : List.of("\"0\"", "\"7\"")) {
        assertThat(setCells(signedInAs(role, "someone-else"), copy, revision, change))
            .as("%s at %s", role, revision)
            .hasStatus(404)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("NOT_FOUND");
      }
    }
    assertThat(setCells(ana(), 987_654_321, "\"0\"", change)).hasStatus(404);
    assertThat(setCells(request -> request, copy, "\"0\"", change)).hasStatus(401);
    assertNothingChanged(copy);
  }

  @Test
  void whoseTheCatalogIsAndItsStatusAreSettledBeforeAnythingElseAboutAnEdit() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    var nonsense = "{\"availability\": \"X\"}";

    for (var revision : new String[] {null, "no revision", "\"7\""}) {
      assertThat(setCells(ben(), copy, revision, nonsense))
          .as("someone else's working copy, at %s", revision)
          .hasStatus(404);
      assertThat(setCells(signedInAs(Role.AUTHOR, "author"), approved, revision, nonsense))
          .as("the owner's Approved version, at %s", revision)
          .hasStatus(409);
    }
    assertThat(setCells(ana(), copy, "\"7\"", nonsense))
        .as("the revision is settled before what the save holds")
        .hasStatus(412);
    assertThat(setCells(ana(), copy, "\"0\"", nonsense)).hasStatus(422);
    assertNothingChanged(copy);
  }

  @Test
  void twoSavesFromOneRevisionAtTheSameMomentDoNotBothGoThrough() throws Exception {
    var first = cell("TRANS_MANUAL", "Base", "NA", "A");
    var second = cell("ROOF_PANORAMIC", "Sport", "NA", "S");
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newFixedThreadPool(3)) {
      // Holds the catalog's row, as an edit in progress does, until both saves are waiting on it.
      var holder =
          threads.submit(
              () ->
                  transactions.executeWithoutResult(
                      transaction -> {
                        jdbc.sql("SELECT id FROM catalog WHERE id = :id FOR UPDATE")
                            .param("id", copy)
                            .query(Long.class)
                            .single();
                        held.countDown();
                        await(release);
                      }));
      await(held);
      var saves =
          List.of(
              threads.submit(() -> setCells(ana(), copy, "\"0\"", first).getResponse().getStatus()),
              threads.submit(
                  () -> setCells(ana(), copy, "\"0\"", second).getResponse().getStatus()));
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> count("pg_stat_activity WHERE wait_event_type = 'Lock'") == 2);

      release.countDown();
      holder.get();

      assertThat(List.of(saves.get(0).get(), saves.get(1).get()))
          .containsExactlyInAnyOrder(200, 412);
    }
    assertThat(revision(copy)).isEqualTo(1);
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Test
  void aSaveThatNamesACellTwiceLeavesItAsGivenLast() {
    assertThat(
            setCells(
                ana(),
                copy,
                "\"0\"",
                cell("TRANS_MANUAL", "Base", "NA", "S"),
                cell("TRANS_MANUAL", "Base", "NA", "A")))
        .hasStatusOk();

    assertThat(stored("TRANS_MANUAL", "Base", "NA")).isEqualTo("A");
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isEqualTo(1);
  }

  @Test
  void aSaveThatChangesNothingLeavesTheCatalogAsItWasAndSaysSoByItsRevision() {
    var later = workingCopy(ana(), "SPORTS_COUPE", 2026);
    assertThat(stored("ENGINE_15T_I4", "Base", "NA")).isEqualTo("S");
    assertThat(stored("TRANS_MANUAL", "Base", "NA")).isEqualTo("N");

    var nothingNew =
        setCells(
            ana(),
            copy,
            "\"0\"",
            cell("ENGINE_15T_I4", "Base", "NA", "S"),
            cell("TRANS_MANUAL", "Base", "NA", "N"));

    assertThat(nothingNew).hasStatusOk().headers().hasValue("ETag", "\"0\"");
    assertThat(nothingNew).bodyJson().extractingPath("$.revision").isEqualTo(0);
    assertNothingChanged(copy);
    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(ana()).exchange();
    assertThat(ApplicationIT.<List<Integer>>read(mine, "$[*].id"))
        .as("it does not count as an update either")
        .containsExactly((int) later, (int) copy);
    assertThat(setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .as("the next edit is still one of the same revision")
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(1);
  }

  @Test
  void anApprovedVersionIsNotEditedEvenByItsOwner() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    // The seeded catalogs belong to the demo author.
    var owner = signedInAs(Role.AUTHOR, "author");
    var change = cell("TRANS_MANUAL", "Base", "NA", "A");

    for (var revision : List.of("\"0\"", "\"7\"")) {
      assertThat(setCells(owner, approved, revision, change))
          .as("the status is checked before the revision: %s", revision)
          .hasStatus(409)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NOT_DRAFT");
    }
    assertThat(setCells(ana(), approved, "\"0\"", change))
        .as("someone who can read it but does not own it")
        .hasStatus(404);
    assertNothingChanged(approved);
  }

  @Test
  void aSaveSetsOneToFiveHundredCells() {
    var offerings = List.of("Base:NA", "Sport:NA", "Touring:NA", "Off-Road:NA", "Base:EU");
    var features =
        jdbc.sql(
                """
                SELECT f.code
                FROM catalog_feature c
                JOIN feature f ON f.id = c.feature_id
                WHERE c.catalog_id = :id
                ORDER BY f.code
                LIMIT 101
                """)
            .param("id", copy)
            .query(String.class)
            .list();
    var cells =
        features.stream()
            .flatMap(
                feature ->
                    offerings.stream()
                        .map(offering -> offering.split(":"))
                        .map(offering -> cell(feature, offering[0], offering[1], "S")))
            .toList();
    assertThat(cells).hasSize(505);

    assertThat(setCells(ana(), copy, "\"0\"", cells.toArray(String[]::new)))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(setCells(ana(), copy, "\"0\"", cells.subList(0, 501).toArray(String[]::new)))
        .hasStatus(422);
    assertThat(setCells(ana(), copy, "\"0\""))
        .as("no cells at all")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VALIDATION");
    assertNothingChanged(copy);

    assertThat(setCells(ana(), copy, "\"0\"", cells.subList(0, 500).toArray(String[]::new)))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(1);
    assertThat(
            count(
                """
                catalog_cell WHERE catalog_id = %d AND availability = 'S' AND region_code = 'NA'
                AND feature_id IN (SELECT id FROM feature WHERE code = ANY ('{%s}'))
                """,
                copy, String.join(",", features.subList(0, 100))))
        .as("a hundred feature rows are Standard in all four offerings of North America")
        .isEqualTo(400);
  }

  @Test
  void aCellOutsideTheCatalogsFeatureRowsAndOfferingsIsRefused() {
    assertThat(
            count(
                "catalog_feature WHERE catalog_id = %d AND feature_id = %d",
                copy, feature("ROOF_REMOVABLE")))
        .as("the catalog has no feature row for the removable roof")
        .isZero();
    var refused =
        Map.of(
            "a feature that is no feature row of the catalog",
            cell("ROOF_REMOVABLE", "Base", "NA", "A"),
            "an offering the catalog does not have: Off-Road is not sold in Europe",
            cell("TRANS_MANUAL", "Off-Road", "EU", "A"),
            "a trim the catalog has not added",
            cell("TRANS_MANUAL", "Luxury", "NA", "A"),
            "an availability that is none",
            cell("TRANS_MANUAL", "Base", "NA", "X"),
            "a cell that names no feature",
            "{\"trimId\": %d, \"regionCode\": \"NA\", \"availability\": \"A\"}"
                .formatted(trim("Base")),
            "a cell that is nothing",
            "null");

    refused.forEach(
        (what, change) ->
            assertThat(
                    setCells(
                        ana(), copy, "\"0\"", cell("ROOF_PANORAMIC", "Sport", "NA", "S"), change))
                .as(what)
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("VALIDATION"));
    assertThat(stored("ROOF_PANORAMIC", "Sport", "NA"))
        .as("a refused save sets none of its cells")
        .isEqualTo("N");
    assertNothingChanged(copy);
  }

  @Test
  void everyCellChangeIsRecordedWithItsOldAndNewValueWhoMadeItAndWhen() {
    assertThat(count("catalog_change WHERE catalog_id = %d", copy))
        .as("a new working copy has no change history")
        .isZero();

    setCells(
        ana(),
        copy,
        "\"0\"",
        cell("TRANS_MANUAL", "Base", "NA", "A"),
        cell("ENGINE_15T_I4", "Base", "NA", "N"),
        cell("ROOF_PANORAMIC", "Touring", "NA", "S"));

    var changes =
        jdbc.sql(
                """
                SELECT kind, actor_id, payload::text AS payload, at > now() - interval '1 minute' AS recent
                FROM catalog_change
                WHERE catalog_id = :id
                ORDER BY id
                """)
            .param("id", copy)
            .query()
            .listOfRows();
    assertThat(changes)
        .as("the roof was Standard already, so setting it to Standard changed nothing")
        .hasSize(2)
        .allSatisfy(
            change ->
                assertThat(change)
                    .containsEntry("kind", "CELL_SET")
                    .containsEntry("actor_id", person("ana"))
                    .containsEntry("recent", true));
    assertThat(payload(changes.get(0)))
        .containsEntry("featureId", (int) feature("TRANS_MANUAL"))
        .containsEntry("trimId", (int) trim("Base"))
        .containsEntry("regionCode", "NA")
        .containsEntry("old", "N")
        .containsEntry("new", "A");
    assertThat(payload(changes.get(1)))
        .containsEntry("featureId", (int) feature("ENGINE_15T_I4"))
        .containsEntry("old", "S")
        .containsEntry("new", "N");
  }

  @Test
  void aSaveMovesTheWorkingCopyToTheTopOfItsOwnersList() {
    var later = workingCopy(ana(), "SPORTS_COUPE", 2026);

    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));

    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(ana()).exchange();
    assertThat(ApplicationIT.<List<Integer>>read(mine, "$[*].id"))
        .containsExactly((int) copy, (int) later);
  }

  @Test
  void theTimeAnEditTakesIsRecorded() {
    var before = editsTimed();

    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "S"));

    assertThat(editsTimed()).as("a refused edit is not timed").isEqualTo(before + 1);
  }

  private long editsTimed() {
    var timer = metrics.find("catalog.edit").timer();
    return timer == null ? 0 : timer.count();
  }

  /** What the working copy stores for the cell, where no stored cell is Not offered. */
  private String stored(String feature, String trim, String region) {
    return jdbc.sql(
            """
            SELECT availability
            FROM catalog_cell
            WHERE catalog_id = :id AND feature_id = :feature AND trim_id = :trim
              AND region_code = :region
            """)
        .param("id", copy)
        .param("feature", feature(feature))
        .param("trim", trim(trim))
        .param("region", region)
        .query(String.class)
        .optional()
        .orElse("N");
  }

  /**
   * The catalog has the cells of Compact SUV 2026 version 2, its first revision, and no history.
   */
  private void assertNothingChanged(long catalog) {
    assertThat(revision(catalog)).isZero();
    assertThat(count("catalog_change WHERE catalog_id = %d", catalog)).isZero();
    assertThat(
            jdbc.sql(
                    """
                    SELECT feature_id, trim_id, region_code, availability
                    FROM catalog_cell
                    WHERE catalog_id = :id
                    ORDER BY 1, 2, 3
                    """)
                .param("id", catalog)
                .query()
                .listOfRows())
        .isEqualTo(
            jdbc.sql(
                    """
                    SELECT feature_id, trim_id, region_code, availability
                    FROM catalog_cell
                    WHERE catalog_id = :id
                    ORDER BY 1, 2, 3
                    """)
                .param("id", approved("COMPACT_SUV", 2026, 2))
                .query()
                .listOfRows());
  }

  private static Map<String, Object> payload(Map<String, Object> change) {
    return JsonPath.read((String) change.get("payload"), "$");
  }
}
