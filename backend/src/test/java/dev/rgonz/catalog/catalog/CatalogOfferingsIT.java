package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a working copy in status Draft adds and removes library trims and
 * regions and says where each trim is sold, and that the catalog never touches a definition. Each
 * test starts from the seeded catalogs, a working copy of Compact SUV 2026 that Ana owns, and an
 * empty one of hers.
 */
class CatalogOfferingsIT extends WorkingCopyTests {
  /** Ana's working copy of Compact SUV 2026: Base, Sport, Touring, and Off-Road in two regions. */
  private long copy;

  /** Ana's working copy of Sports Coupe 2026, which has nothing in it. */
  private long empty;

  @BeforeEach
  void anasWorkingCopies() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    empty = workingCopy(ana(), "SPORTS_COUPE", 2026);
  }

  @Test
  void theOwnerOfAnEmptyWorkingCopyAddsTrimsAndRegionsAndSaysWhereEachTrimIsSold() {
    var added = addTrims(ana(), empty, "\"0\"", trim("Sport"), trim("Base"));

    assertThat(added).hasStatusOk().headers().hasValue("ETag", "\"1\"");
    assertThat(added).bodyJson().extractingPath("$.revision").isEqualTo(1);
    assertThat(addRegions(ana(), empty, "\"1\"", "EU", "NA")).hasStatusOk();
    assertThat(sellIn(ana(), empty, "\"2\"", trim("Base"), "NA", "EU"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(3);
    assertThat(sellIn(ana(), empty, "\"3\"", trim("Sport"), "EU")).hasStatusOk();

    var opened = open(ana(), empty);
    assertThat(opened).headers().hasValue("ETag", "\"4\"");
    assertThat(ApplicationIT.<List<String>>read(opened, "$.snapshot.trims[*].name"))
        .as("in the library's order, not the order they were added in")
        .containsExactly("Base", "Sport");
    assertThat(ApplicationIT.<List<String>>read(opened, "$.snapshot.regions[*].name"))
        .containsExactly("North America", "Europe");
    assertThat(offerings(empty)).containsExactlyInAnyOrder("Base:NA", "Base:EU", "Sport:EU");
  }

  @Test
  void sayingWhereATrimIsSoldAddsAndRemovesOfferingsAndARemovedOneTakesOnlyItsOwnCells() {
    assertThat(offerings(copy)).contains("Base:NA", "Base:EU").doesNotContain("Off-Road:EU");
    var cells = cellsByOffering(copy);
    assertThat(cells.get("Base:EU")).isPositive();

    assertThat(sellIn(ana(), copy, "\"0\"", trim("Base"), "NA")).hasStatusOk();

    assertThat(offerings(copy)).contains("Base:NA").doesNotContain("Base:EU");
    var after = cellsByOffering(copy);
    assertThat(after).doesNotContainKey("Base:EU");
    cells.remove("Base:EU");
    assertThat(after).as("every other offering keeps its cells").isEqualTo(cells);

    assertThat(sellIn(ana(), copy, "\"1\"", trim("Off-Road"), "EU", "NA")).hasStatusOk();
    assertThat(offerings(copy)).contains("Off-Road:NA", "Off-Road:EU");
    assertThat(cellsByOffering(copy)).as("a new offering starts with no cells").isEqualTo(cells);

    assertThat(sellIn(ana(), copy, "\"2\"", trim("Sport")))
        .as("a trim can be sold nowhere and stay in the catalog")
        .hasStatusOk();
    assertThat(offerings(copy)).noneMatch(offering -> offering.startsWith("Sport:"));
    assertThat(count("catalog_trim WHERE catalog_id = %d AND trim_id = %d", copy, trim("Sport")))
        .isEqualTo(1);
  }

  @Test
  void removingATrimOrARegionRemovesItsOfferingsAndTheirCells() {
    var cells = cellsByOffering(copy);

    assertThat(removeTrim(ana(), copy, "\"0\"", trim("Sport")))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(1);

    assertThat(offerings(copy)).noneMatch(offering -> offering.startsWith("Sport:"));
    assertThat(count("catalog_trim WHERE catalog_id = %d AND trim_id = %d", copy, trim("Sport")))
        .isZero();
    cells.keySet().removeIf(offering -> offering.startsWith("Sport:"));
    assertThat(cellsByOffering(copy)).isEqualTo(cells);

    assertThat(removeRegion(ana(), copy, "\"1\"", "EU")).hasStatusOk();

    assertThat(offerings(copy)).containsExactlyInAnyOrder("Base:NA", "Touring:NA", "Off-Road:NA");
    assertThat(count("catalog_region WHERE catalog_id = %d AND region_code = 'EU'", copy)).isZero();
    cells.keySet().removeIf(offering -> offering.endsWith(":EU"));
    assertThat(cellsByOffering(copy)).isEqualTo(cells);
    assertThat(count("catalog_feature WHERE catalog_id = %d", copy))
        .as("the feature rows stay")
        .isEqualTo(151);
  }

  @Test
  void aCatalogHasAtMostTwelveTrimsAndEightRegions() {
    var trims =
        LongStream.rangeClosed(1, 13)
            .map(
                number ->
                    jdbc.sql("INSERT INTO trim (name, sort_order) VALUES (:name, 99) RETURNING id")
                        .param("name", "Edition " + number)
                        .query(Long.class)
                        .single())
            .boxed()
            .toList();
    for (var code : List.of("R1", "R2", "R3", "R4", "R5")) {
      jdbc.sql("INSERT INTO region (code, name, sort_order) VALUES (:code, :code, 99)")
          .param("code", code)
          .update();
    }

    assertThat(addTrims(ana(), empty, "\"0\"", trims.toArray(Long[]::new)))
        .as("thirteen at once")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(addTrims(ana(), empty, "\"0\"", trims.subList(0, 12).toArray(Long[]::new)))
        .hasStatusOk();
    assertThat(addTrims(ana(), empty, "\"1\"", trims.get(12)))
        .as("a thirteenth")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(count("catalog_trim WHERE catalog_id = %d", empty)).isEqualTo(12);

    assertThat(addRegions(ana(), empty, "\"1\"", "NA", "SA", "EU", "ASIA", "R1", "R2", "R3", "R4"))
        .hasStatusOk();
    assertThat(addRegions(ana(), empty, "\"2\"", "R5"))
        .as("a ninth")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(count("catalog_region WHERE catalog_id = %d", empty)).isEqualTo(8);
    assertThat(revision(empty)).isEqualTo(2);
  }

  @Test
  void onlyAnActiveLibraryEntryTheCatalogDoesNotHaveYetCanBeAdded() {
    jdbc.sql("UPDATE trim SET active = false WHERE name = 'Luxury'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'ASIA'").update();
    var refused =
        Map.of(
            "an inactive trim", addTrims(ana(), copy, "\"0\"", trim("Performance"), trim("Luxury")),
            "a trim the library does not have", addTrims(ana(), copy, "\"0\"", 987_654_321L),
            "a trim the catalog already has", addTrims(ana(), copy, "\"0\"", trim("Sport")),
            "a trim named twice",
                addTrims(ana(), copy, "\"0\"", trim("Performance"), trim("Performance")),
            "no trims at all", addTrims(ana(), copy, "\"0\""),
            "an inactive region", addRegions(ana(), copy, "\"0\"", "SA", "ASIA"),
            "a region the library does not have", addRegions(ana(), copy, "\"0\"", "MARS"),
            "a region the catalog already has", addRegions(ana(), copy, "\"0\"", "EU"),
            "no regions at all", addRegions(ana(), copy, "\"0\""));

    refused.forEach(
        (what, answer) ->
            assertThat(answer)
                .as(what)
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("VALIDATION"));
    assertThat(revision(copy)).isZero();
    assertThat(count("catalog_trim WHERE catalog_id = %d", copy)).isEqualTo(4);
    assertThat(count("catalog_region WHERE catalog_id = %d", copy)).isEqualTo(2);
  }

  @Test
  void anEntryTheLibraryHasDeactivatedStaysInTheCatalogUntilItIsRemoved() {
    jdbc.sql("UPDATE trim SET active = false WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'EU'").update();

    assertThat(ApplicationIT.<List<String>>read(open(ana(), copy), "$.snapshot.trims[*].name"))
        .contains("Sport");
    assertThat(sellIn(ana(), copy, "\"0\"", trim("Sport"), "NA"))
        .as("where it is sold can still be changed")
        .hasStatusOk();
    assertThat(removeTrim(ana(), copy, "\"1\"", trim("Sport"))).hasStatusOk();
    assertThat(removeRegion(ana(), copy, "\"2\"", "EU")).hasStatusOk();
    assertThat(addTrims(ana(), copy, "\"3\"", trim("Sport")))
        .as("once removed, it cannot be added again")
        .hasStatus(422);
    assertThat(addRegions(ana(), copy, "\"3\"", "EU")).hasStatus(422);
  }

  @Test
  void anOfferingNeedsBothItsTrimAndItsRegionInTheCatalog() {
    assertThat(sellIn(ana(), copy, "\"0\"", trim("Luxury"), "NA"))
        .as("a trim the catalog has not added")
        .hasStatus(404);
    assertThat(sellIn(ana(), copy, "\"0\"", trim("Base"), "NA", "ASIA"))
        .as("a region the catalog has not added")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VALIDATION");
    assertThat(removeTrim(ana(), copy, "\"0\"", trim("Luxury"))).hasStatus(404);
    assertThat(removeRegion(ana(), copy, "\"0\"", "ASIA")).hasStatus(404);
    assertThat(revision(copy)).isZero();
    assertThat(offerings(copy)).hasSize(7);
  }

  @Test
  void nothingDoneToACatalogChangesADefinitionOrAnotherCatalog() {
    var library = library();
    var approved = approved("COMPACT_SUV", 2026, 2);
    var approvedOfferings = offerings(approved);
    var approvedCells = cellsByOffering(approved);

    assertThat(
            edit(
                ana(),
                mvc.post().uri("/api/catalogs/{id}/trims", copy),
                "\"0\"",
                """
                {"trimIds": [%d], "name": "Renamed", "sortOrder": 1, "active": false}
                """
                    .formatted(trim("Luxury"))))
        .as("a name sent along is not taken")
        .hasStatusOk();
    addRegions(ana(), copy, "\"1\"", "ASIA");
    sellIn(ana(), copy, "\"2\"", trim("Luxury"), "ASIA", "NA");
    removeTrim(ana(), copy, "\"3\"", trim("Base"));
    removeRegion(ana(), copy, "\"4\"", "EU");

    assertThat(revision(copy)).isEqualTo(5);
    assertThat(library).isEqualTo(library());
    assertThat(offerings(approved)).isEqualTo(approvedOfferings);
    assertThat(cellsByOffering(approved)).isEqualTo(approvedCells);
  }

  @Test
  void twoWorkingCopiesAddTheSameLibraryTrimAndHoldDifferentCellsUnderIt() {
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    for (var each : Map.of(ana(), copy, ben(), bens).entrySet()) {
      addTrims(each.getKey(), each.getValue(), "\"0\"", trim("Luxury"));
      sellIn(each.getKey(), each.getValue(), "\"1\"", trim("Luxury"), "NA");
    }

    assertThat(setCells(ana(), copy, "\"2\"", cell("TRANS_MANUAL", "Luxury", "NA", "S")))
        .hasStatusOk();
    assertThat(setCells(ben(), bens, "\"2\"", cell("TRANS_MANUAL", "Luxury", "NA", "A")))
        .hasStatusOk();

    Function<Long, String> luxuryManual =
        catalog ->
            jdbc.sql(
                    """
                    SELECT availability FROM catalog_cell
                    WHERE catalog_id = :id AND feature_id = :feature AND trim_id = :trim
                    """)
                .param("id", catalog)
                .param("feature", feature("TRANS_MANUAL"))
                .param("trim", trim("Luxury"))
                .query(String.class)
                .single();
    assertThat(luxuryManual.apply(copy)).isEqualTo("S");
    assertThat(luxuryManual.apply(bens)).isEqualTo("A");
  }

  @Test
  void theEditingRulesHoldForEveryOneOfTheseEdits() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    // The seeded catalogs belong to the demo author.
    var ownerOfApproved = signedInAs(Role.AUTHOR, "author");
    Map<String, Edit> edits =
        Map.of(
            "add trims", (who, id, revision) -> addTrims(who, id, revision, trim("Luxury")),
            "remove a trim", (who, id, revision) -> removeTrim(who, id, revision, trim("Sport")),
            "add regions", (who, id, revision) -> addRegions(who, id, revision, "ASIA"),
            "remove a region", (who, id, revision) -> removeRegion(who, id, revision, "EU"),
            "say where a trim is sold",
                (who, id, revision) -> sellIn(who, id, revision, trim("Base"), "NA"));

    edits.forEach(
        (what, edit) -> {
          assertThat(edit.send(ana(), copy, null))
              .as("%s without a revision", what)
              .hasStatus(428)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("REVISION_REQUIRED");
          var conflict = edit.send(ana(), copy, "\"7\"");
          assertThat(conflict)
              .as("%s from another revision", what)
              .hasStatus(412)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("REVISION_CONFLICT");
          assertThat(conflict).bodyJson().extractingPath("$.revision").isEqualTo(0);
          assertThat(edit.send(ben(), copy, "\"0\"")).as("%s by someone else", what).hasStatus(404);
          assertThat(edit.send(ownerOfApproved, approved, "\"0\""))
              .as("%s of an Approved version", what)
              .hasStatus(409)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("NOT_DRAFT");
        });
    assertThat(revision(copy)).isZero();
    assertThat(offerings(copy)).hasSize(7);
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isZero();
  }

  @Test
  void eachChangeAppearsInTheChangeHistoryWithItsKind() {
    addTrims(ana(), copy, "\"0\"", trim("Luxury"), trim("Performance"));
    addRegions(ana(), copy, "\"1\"", "ASIA");
    sellIn(ana(), copy, "\"2\"", trim("Luxury"), "ASIA", "NA");
    sellIn(ana(), copy, "\"3\"", trim("Luxury"), "NA", "EU");
    removeTrim(ana(), copy, "\"4\"", trim("Performance"));
    removeRegion(ana(), copy, "\"5\"", "ASIA");

    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();

    assertThat(
            ApplicationIT.<List<Map<String, Object>>>read(history, "$.items").reversed().stream()
                .map(
                    change ->
                        "%s %s %s"
                            .formatted(
                                change.get("kind"), change.get("trim"), change.get("region"))))
        .as("oldest first")
        .containsExactly(
            "TRIM_ADDED Luxury null",
            "TRIM_ADDED Performance null",
            "REGION_ADDED null Asia",
            "OFFERING_ADDED Luxury Asia",
            "OFFERING_ADDED Luxury North America",
            "OFFERING_REMOVED Luxury Asia",
            "OFFERING_ADDED Luxury Europe",
            "TRIM_REMOVED Performance null",
            "REGION_REMOVED null Asia");
    assertThat(history).bodyJson().extractingPath("$.items[0].actor").isEqualTo("ana");
  }

  /** One of the edits, sent by a person to a catalog as an edit of a revision. */
  private interface Edit {
    MvcTestResult send(RequestPostProcessor who, long catalog, String revision);
  }

  private MvcTestResult addTrims(
      RequestPostProcessor who, long catalog, String revision, Long... trimIds) {
    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/trims", catalog),
        revision,
        "{\"trimIds\": %s}".formatted(List.of(trimIds)));
  }

  private MvcTestResult removeTrim(
      RequestPostProcessor who, long catalog, String revision, long trimId) {
    return edit(
        who, mvc.delete().uri("/api/catalogs/{id}/trims/{trim}", catalog, trimId), revision, null);
  }

  private MvcTestResult addRegions(
      RequestPostProcessor who, long catalog, String revision, String... regionCodes) {
    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/regions", catalog),
        revision,
        "{\"regionCodes\": %s}".formatted(quoted(regionCodes)));
  }

  private MvcTestResult removeRegion(
      RequestPostProcessor who, long catalog, String revision, String regionCode) {
    return edit(
        who,
        mvc.delete().uri("/api/catalogs/{id}/regions/{region}", catalog, regionCode),
        revision,
        null);
  }

  /** Says that the trim is sold in exactly these regions. */
  private MvcTestResult sellIn(
      RequestPostProcessor who, long catalog, String revision, long trimId, String... regionCodes) {
    return edit(
        who,
        mvc.put().uri("/api/catalogs/{id}/trims/{trim}/regions", catalog, trimId),
        revision,
        "{\"regionCodes\": %s}".formatted(quoted(regionCodes)));
  }

  private static String quoted(String... values) {
    return List.of(values).stream()
        .collect(Collectors.joining("\", \"", "[\"", "\"]"))
        .replace("[\"\"]", "[]");
  }

  /**
   * Sends an edit that names the revision, or none when it is null, with a body when it has one.
   */
  private MvcTestResult edit(
      RequestPostProcessor who,
      org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder request,
      String revision,
      String body) {
    request.with(who).with(csrfToken());
    if (revision != null) {
      request.header("If-Match", revision);
    }
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return request.exchange();
  }

  /** The catalog's offerings, each as its trim's name and its region's code. */
  private List<String> offerings(long catalog) {
    return jdbc.sql(
            """
            SELECT t.name || ':' || o.region_code
            FROM catalog_trim_region o
            JOIN trim t ON t.id = o.trim_id
            WHERE o.catalog_id = :id
            """)
        .param("id", catalog)
        .query(String.class)
        .list();
  }

  /** How many cells the catalog stores for each offering that has any. */
  private Map<String, Long> cellsByOffering(long catalog) {
    return jdbc
        .sql(
            """
            SELECT t.name || ':' || c.region_code AS offering, count(*) AS cells
            FROM catalog_cell c
            JOIN trim t ON t.id = c.trim_id
            WHERE c.catalog_id = :id
            GROUP BY 1
            """)
        .param("id", catalog)
        .query()
        .listOfRows()
        .stream()
        .collect(
            Collectors.toMap(row -> (String) row.get("offering"), row -> (Long) row.get("cells")));
  }

  /** The library's trims and regions, as they are. */
  private List<Map<String, Object>> library() {
    var trims = jdbc.sql("SELECT * FROM trim ORDER BY id").query().listOfRows();
    trims.addAll(jdbc.sql("SELECT * FROM region ORDER BY code").query().listOfRows());
    return trims;
  }
}
