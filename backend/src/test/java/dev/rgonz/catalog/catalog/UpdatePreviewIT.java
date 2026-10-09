package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks what the owner of a stale working copy is shown of an update from Approved: which catalog
 * the merge starts from, what it brings in, what conflicts, and that it saves nothing. Each test
 * starts from the seeded catalogs.
 */
class UpdatePreviewIT extends WorkingCopyTests {
  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void theOwnerOfAStaleCopySeesWhatApprovedBringsAndWhatConflictsAndNothingIsSaved() {
    var anas = workingCopy(ana(), "COMPACT_SUV", 2026);
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    // Both give the manual transmission to Base in North America, each another availability.
    setCells(
        ana(),
        anas,
        rev(anas),
        cell("ENGINE_20T_I4", "Sport", "NA", "S"),
        cell("TRANS_MANUAL", "Base", "NA", "A"));
    edit(
        ana(),
        mvc.post().uri("/api/catalogs/{id}/features", anas),
        rev(anas),
        "{\"featureIds\": [%d]}".formatted(feature("ACCENT_WOOD")));
    setCells(
        ben(),
        bens,
        rev(bens),
        cell("TRANS_MANUAL", "Base", "NA", "S"),
        cell("FLOOR_CARPET_MATS", "Base", "NA", "A"));
    approve(ana(), anas);
    // The library moves on after the approval, which kept the labels of its day.
    jdbc.sql("UPDATE feature SET name = 'Walnut Accents' WHERE code = 'ACCENT_WOOD'").update();
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();
    jdbc.sql("UPDATE trim SET active = false WHERE name = 'Touring'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'EU'").update();
    var before = open(ben(), bens);
    var entries = count("catalog_change WHERE catalog_id = %d", bens);

    var preview = preview(ben(), bens);

    assertThat(preview).hasStatusOk();
    assertThat(preview).bodyJson().extractingPath("$.revision").isEqualTo((int) revision(bens));
    assertThat(preview).bodyJson().extractingPath("$.approved.catalogId").isEqualTo((int) anas);
    assertThat(preview).bodyJson().extractingPath("$.approved.versionNumber").isEqualTo(3);
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(preview, "$.taken.cellsChanged"))
        .as("what only Ana changed, and neither the conflict nor what only Ben changed")
        .singleElement()
        .satisfies(
            cell ->
                assertThat(cell)
                    .containsEntry("featureCode", "ENGINE_20T_I4")
                    .containsEntry("trim", "Sport")
                    .containsEntry("regionCode", "NA")
                    .containsEntry("before", "A")
                    .containsEntry("after", "S"));
    assertThat(ApplicationIT.<List<String>>read(preview, "$.taken.featureRowsAdded[*].name"))
        .as("by the library's label of today")
        .containsExactly("Walnut Accents");
    assertThat(ApplicationIT.<List<Object>>read(preview, "$.taken.featureRowsRemoved"))
        .as("the retired feature stays")
        .isEmpty();
    assertThat(ApplicationIT.<List<Object>>read(preview, "$.taken.trimsRemoved"))
        .as("the inactive trim stays")
        .isEmpty();
    assertThat(ApplicationIT.<List<Object>>read(preview, "$.taken.regionsRemoved"))
        .as("the inactive region stays")
        .isEmpty();
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(preview, "$.conflicts"))
        .singleElement()
        .satisfies(
            conflict -> {
              assertThat(conflict)
                  .containsEntry(
                      "id", "cell:%d:%d:NA".formatted(feature("TRANS_MANUAL"), trim("Base")))
                  .containsEntry("kind", "CELL")
                  .containsEntry("base", "Not offered")
                  .containsEntry("mine", "Standard")
                  .containsEntry("theirs", "Available")
                  .containsEntry("resolution", null);
              assertThat((String) conflict.get("what")).endsWith(", Base in North America");
            });

    assertThat(ApplicationIT.<Map<String, Object>>read(open(ben(), bens), "$"))
        .as("the catalog, with its revision and its contents")
        .isEqualTo(ApplicationIT.<Map<String, Object>>read(before, "$"));
    assertThat(count("catalog_change WHERE catalog_id = %d", bens)).isEqualTo(entries);
  }

  @Test
  void onlyTheOwnerOfAStaleDraftHasAPreview() {
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);

    assertThat(preview(ana(), bens)).as("someone else's").hasStatus(404);
    assertThat(preview(ben(), bens + 1000)).as("no catalog at all").hasStatus(404);
    assertThat(preview(ben(), bens))
        .as("a Draft that is up to date")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_STALE");
    submit(ben(), bens);
    assertThat(preview(ben(), bens))
        .as("a Submitted catalog")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_DRAFT");
    assertThat(mvc.post().uri("/api/catalogs/{id}/merge-preview", bens).with(csrfToken()))
        .as("without signing in")
        .hasStatus(401);
  }

  @Test
  void aCarryoverMergesFromTheNearestCatalogBothSidesDescendFromAndKeepsWhatItInherited() {
    // Ben carries Approved v1 of 2026 over. Then 2026 gets a v2, which Ana carries over.
    var bens = workingCopy(ben(), "PICKUP_TRUCK", 2027);
    var next = workingCopy(ana(), "PICKUP_TRUCK", 2026);
    setCells(ana(), next, rev(next), cell("FLOOR_CARPET_MATS", "Base", "NA", "S"));
    approve(ana(), next);
    var anas = workingCopy(ana(), "PICKUP_TRUCK", 2027);
    setCells(ben(), bens, rev(bens), cell("ROOF_PANORAMIC", "Luxury", "NA", "S"));

    // Ben's becomes the first Approved of 2027. It does not descend from Ana's base, v2 of 2026.
    approve(ben(), bens);
    var preview = preview(ana(), anas);

    assertThat(preview).hasStatusOk();
    assertThat(ApplicationIT.<List<Object>>read(preview, "$.conflicts")).isEmpty();
    assertThat(ApplicationIT.<List<String>>read(preview, "$.taken.cellsChanged[*].featureCode"))
        .as("what Ben changed, and not the floor mats that Ana's inherited from v2")
        .containsExactly("ROOF_PANORAMIC");
  }

  @Test
  void twoCatalogsThatStartedEmptyMergeFromAnEmptyCatalog() {
    var anas = workingCopy(ana(), "SPORTS_COUPE", 2026);
    var bens = workingCopy(ben(), "SPORTS_COUPE", 2026);
    sellBaseInNorthAmerica(ana(), anas, "FLOOR_CARPET_MATS", "TRANS_MANUAL");
    setCells(
        ana(),
        anas,
        rev(anas),
        cell("FLOOR_CARPET_MATS", "Base", "NA", "S"),
        cell("TRANS_MANUAL", "Base", "NA", "S"));
    sellBaseInNorthAmerica(ben(), bens, "FLOOR_CARPET_MATS", "ROOF_PANORAMIC");
    setCells(
        ben(),
        bens,
        rev(bens),
        cell("FLOOR_CARPET_MATS", "Base", "NA", "A"),
        cell("ROOF_PANORAMIC", "Base", "NA", "A"));

    approve(ana(), anas);
    var preview = preview(ben(), bens);

    assertThat(preview).hasStatusOk();
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(preview, "$.conflicts"))
        .as("the one cell both have, and have differently")
        .singleElement()
        .satisfies(
            conflict ->
                assertThat(conflict)
                    .containsEntry(
                        "id", "cell:%d:%d:NA".formatted(feature("FLOOR_CARPET_MATS"), trim("Base")))
                    .containsEntry("base", "Not offered")
                    .containsEntry("mine", "Available")
                    .containsEntry("theirs", "Standard"));
    assertThat(ApplicationIT.<List<String>>read(preview, "$.taken.featureRowsAdded[*].code"))
        .containsExactly("TRANS_MANUAL");
    assertThat(ApplicationIT.<List<Object>>read(preview, "$.taken.featureRowsRemoved"))
        .as("what only Ben's has stays")
        .isEmpty();
  }

  /** Gives an empty catalog what it needs: Base sold in North America, and feature rows. */
  private void sellBaseInNorthAmerica(RequestPostProcessor who, long catalog, String... features) {
    edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/trims", catalog),
        rev(catalog),
        "{\"trimIds\": [%d]}".formatted(trim("Base")));
    edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/regions", catalog),
        rev(catalog),
        "{\"regionCodes\": [\"NA\"]}");
    edit(
        who,
        mvc.put().uri("/api/catalogs/{id}/trims/{trim}/regions", catalog, trim("Base")),
        rev(catalog),
        "{\"regionCodes\": [\"NA\"]}");
    edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/features", catalog),
        rev(catalog),
        "{\"featureIds\": %s}".formatted(List.of(features).stream().map(this::feature).toList()));
  }

  /** The catalog's revision as an edit names it. */
  private String rev(long catalog) {
    return "\"" + revision(catalog) + "\"";
  }

  /** Submits the owner's catalog as it is, and has Mia approve it. */
  private void approve(RequestPostProcessor owner, long catalog) {
    assertThat(submit(owner, catalog)).hasStatusOk();
    assertThat(
            edit(mia(), mvc.post().uri("/api/catalogs/{id}/approve", catalog), rev(catalog), "{}"))
        .hasStatusOk();
  }

  private MvcTestResult submit(RequestPostProcessor who, long catalog) {
    return edit(who, mvc.post().uri("/api/catalogs/{id}/submit", catalog), rev(catalog), "{}");
  }

  private MvcTestResult preview(RequestPostProcessor who, long catalog) {
    return edit(who, mvc.post().uri("/api/catalogs/{id}/merge-preview", catalog), null, null);
  }
}
