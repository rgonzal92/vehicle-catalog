package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a stale working copy settles the conflicts of an update from Approved
 * and applies it. Each test starts from the seeded catalogs, and most from two working copies of
 * Compact SUV 2026: Ana's, which is approved as v3, and Ben's, which that leaves stale. Both gave
 * the manual transmission to Base in North America, Ana as Available and Ben as Standard.
 */
class UpdatesIT extends WorkingCopyTests {
  @Autowired CatalogRules catalogRules;

  /** Ana's working copy, which becomes Approved v3. */
  private long anas;

  /** Ben's working copy, stale once Ana's is approved. */
  private long bens;

  @BeforeEach
  void twoWorkingCopiesOfOneVersion() throws Exception {
    seedLibraryAndCatalogs();
    anas = workingCopy(ana(), "COMPACT_SUV", 2026);
    bens = workingCopy(ben(), "COMPACT_SUV", 2026);
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  /** The conflict that both copies' change of the manual transmission makes. */
  private String manualOnBase() {
    return "cell:%d:%d:NA".formatted(feature("TRANS_MANUAL"), trim("Base"));
  }

  /**
   * Has both change the manual transmission, each something of their own too, and approves Ana's.
   */
  private void bothChangeTheManualAndAnasIsApproved() {
    setCells(
        ana(),
        anas,
        rev(anas),
        cell("ENGINE_20T_I4", "Sport", "NA", "S"),
        cell("TRANS_MANUAL", "Base", "NA", "A"));
    setCells(
        ben(),
        bens,
        rev(bens),
        cell("TRANS_MANUAL", "Base", "NA", "S"),
        cell("FLOOR_CARPET_MATS", "Base", "NA", "A"));
    approve(ana(), anas);
  }

  @Test
  void theOwnerSettlesTheConflictAndTheCatalogHasWhatWasApprovedAndItsOwnChanges() {
    bothChangeTheManualAndAnasIsApproved();
    var revision = revision(bens);
    var updates = counted("catalog.merged");

    var updated = update(ben(), bens, rev(bens), anas, Map.of(manualOnBase(), "THEIRS"));

    assertThat(counted("catalog.merged")).isEqualTo(updates + 1);

    assertThat(updated).hasStatusOk().headers().hasValue("ETag", "\"" + (revision + 1) + "\"");
    assertThat(updated).bodyJson().extractingPath("$.revision").isEqualTo((int) revision + 1);
    assertThat(updated).bodyJson().extractingPath("$.stale").isEqualTo(false);
    assertThat(updated).bodyJson().extractingPath("$.issues").asArray().isNotNull();
    var opened = open(ben(), bens);
    assertThat(opened).bodyJson().extractingPath("$.stale").isEqualTo(false);
    assertThat(opened).bodyJson().extractingPath("$.base.versionNumber").isEqualTo(3);
    assertThat(availability(opened, "ENGINE_20T_I4", "Sport", "NA")).as("taken").isEqualTo("S");
    assertThat(availability(opened, "TRANS_MANUAL", "Base", "NA")).as("theirs").isEqualTo("A");
    assertThat(availability(opened, "FLOOR_CARPET_MATS", "Base", "NA")).as("kept").isEqualTo("A");
    var history = mvc.get().uri("/api/catalogs/{id}/changes", bens).with(ben()).exchange();
    assertThat(history).bodyJson().extractingPath("$.items[0].kind").isEqualTo("MERGED");
    assertThat(history).bodyJson().extractingPath("$.items[0].newValue").isEqualTo("Approved v3");
    assertThat(count("catalog_change WHERE catalog_id = %d AND kind = 'MERGED'", bens)).isOne();
    assertThat(catalogRules.brokenPairs()).isEmpty();
    var review = mvc.get().uri("/api/catalogs/{id}/diff?against=base", bens).with(ben()).exchange();
    assertThat(ApplicationIT.<List<String>>read(review, "$.cellsChanged[*].featureCode"))
        .as("the owner's own change against the new base, and nothing of what was taken")
        .containsExactly("FLOOR_CARPET_MATS");
    assertThat(submit(ben(), bens)).as("its submit").hasStatusOk();
  }

  @Test
  void anUpdateWithAConflictLeftOpenIsRefused() {
    bothChangeTheManualAndAnasIsApproved();
    var revision = revision(bens);
    var updates = counted("catalog.merged");

    var refused = update(ben(), bens, rev(bens), anas, Map.of());

    assertThat(counted("catalog.merged")).as("a refused update is not counted").isEqualTo(updates);

    assertThat(refused)
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("UNRESOLVED_CONFLICTS");
    assertThat(ApplicationIT.<List<String>>read(refused, "$.conflicts[*].id"))
        .containsExactly(manualOnBase());
    assertThat(
            edit(
                ben(),
                mvc.post().uri("/api/catalogs/{id}/merge", bens),
                rev(bens),
                "{\"approvedCatalogId\": %d}".formatted(anas)))
        .as("with no resolutions at all")
        .hasStatus(422);
    assertThat(revision(bens)).isEqualTo(revision);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(count("catalog_change WHERE catalog_id = %d AND kind = 'MERGED'", bens)).isZero();
  }

  @Test
  void anEditAfterThePreviewRefusesTheUpdateUntilItIsWorkedOutAgain() {
    bothChangeTheManualAndAnasIsApproved();
    var previewed = rev(bens);
    setCells(ben(), bens, rev(bens), cell("FLOOR_CARPET_MATS", "Sport", "NA", "A"));
    var mine = Map.of(manualOnBase(), "MINE");

    assertThat(update(ben(), bens, previewed, anas, mine))
        .hasStatus(412)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("REVISION_CONFLICT");
    assertThat(update(ben(), bens, null, anas, mine)).as("without a revision").hasStatus(428);
    assertThat(update(ana(), bens, rev(bens), anas, mine)).as("by someone else").hasStatus(404);
    assertThat(update(ana(), anas, rev(anas), anas, mine))
        .as("of an Approved version")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_DRAFT");

    var preview = edit(ben(), mvc.post().uri("/api/catalogs/{id}/merge-preview", bens), null, null);
    assertThat(preview).bodyJson().extractingPath("$.revision").isEqualTo((int) revision(bens));
    assertThat(update(ben(), bens, rev(bens), anas, mine)).hasStatusOk();
    var opened = open(ben(), bens);
    assertThat(availability(opened, "TRANS_MANUAL", "Base", "NA")).as("mine").isEqualTo("S");
    assertThat(availability(opened, "FLOOR_CARPET_MATS", "Sport", "NA")).isEqualTo("A");
  }

  @Test
  void anotherApprovalBetweenThePreviewAndTheUpdateRefusesIt() {
    bothChangeTheManualAndAnasIsApproved();
    var next = idOf(create(ana(), "Another one", line("COMPACT_SUV"), 2026));
    approve(ana(), next);

    var refused = update(ben(), bens, rev(bens), anas, Map.of(manualOnBase(), "THEIRS"));

    assertThat(refused)
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("APPROVED_MOVED");
    assertThat(refused).bodyJson().extractingPath("$.detail").asString().contains("Approved v4");
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(true);
  }

  @Test
  void aCatalogThatIsNotStaleIsNotUpdated() {
    assertThat(update(ben(), bens, rev(bens), approved("COMPACT_SUV", 2026, 2), Map.of()))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_STALE");
  }

  @Test
  void aRegionThatTheApprovedVersionRemovedGoesWithTheOwnersCellsAndRulesOrStaysWithThem() {
    var second = idOf(create(ben(), "A second one", line("COMPACT_SUV"), 2026));
    for (long copy : List.of(bens, second)) {
      setCells(ben(), copy, rev(copy), cell("FLOOR_CARPET_MATS", "Base", "EU", "A"));
      assertThat(
              edit(
                  ben(),
                  mvc.post().uri("/api/catalogs/{id}/rules", copy),
                  rev(copy),
                  """
                  {"kind": "REQUIRES", "sourceFeatureId": %d, "targetFeatureIds": [%d],
                   "allTrims": true, "trimIds": [], "allRegions": false, "regionCodes": ["EU"]}
                  """
                      .formatted(feature("FLOOR_CARPET_MATS"), feature("TRANS_MANUAL"))))
          .hasStatusOk();
    }
    assertThat(
            edit(
                ana(),
                mvc.delete().uri("/api/catalogs/{id}/regions/{code}", anas, "EU"),
                rev(anas),
                null))
        .hasStatusOk();
    approve(ana(), anas);
    var preview = edit(ben(), mvc.post().uri("/api/catalogs/{id}/merge-preview", bens), null, null);
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(preview, "$.conflicts"))
        .singleElement()
        .satisfies(
            conflict ->
                assertThat(conflict)
                    .containsEntry("id", "region:EU")
                    .containsEntry("mine", "Kept, with 1 cell, 1 rule changed beneath it")
                    .containsEntry("theirs", "Removed"));

    assertThat(update(ben(), bens, rev(bens), anas, Map.of("region:EU", "THEIRS"))).hasStatusOk();
    assertThat(update(ben(), second, rev(second), anas, Map.of("region:EU", "MINE"))).hasStatusOk();

    var removed = open(ben(), bens);
    assertThat(ApplicationIT.<List<String>>read(removed, "$.snapshot.regions[*].code"))
        .doesNotContain("EU");
    assertThat(ApplicationIT.<List<String>>read(removed, "$.snapshot.cells[*].regionCode"))
        .doesNotContain("EU");
    assertThat(ApplicationIT.<List<List<String>>>read(removed, "$.snapshot.rules[*].regionCodes"))
        .as("the rule that covered Europe alone went with it")
        .noneMatch(regions -> regions.contains("EU"));
    var kept = open(ben(), second);
    assertThat(ApplicationIT.<List<String>>read(kept, "$.snapshot.regions[*].code")).contains("EU");
    assertThat(availability(kept, "FLOOR_CARPET_MATS", "Base", "EU")).isEqualTo("A");
    assertThat(ApplicationIT.<List<List<String>>>read(kept, "$.snapshot.rules[*].regionCodes"))
        .contains(List.of("EU"));
    assertThat(kept).bodyJson().extractingPath("$.stale").isEqualTo(false);
    assertThat(catalogRules.brokenPairs()).isEmpty();
  }

  @Test
  void anInheritedRetiredFeatureStaysARowAndItsErrorStillBlocksTheSubmit() {
    setCells(ana(), anas, rev(anas), cell("ENGINE_20T_I4", "Sport", "NA", "S"));
    approve(ana(), anas);
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();

    var updated = update(ben(), bens, rev(bens), anas, Map.of());

    assertThat(updated).hasStatusOk();
    assertThat(ApplicationIT.<List<String>>read(updated, "$.issues[*].code"))
        .contains("FEATURE_RETIRED");
    assertThat(
            ApplicationIT.<List<String>>read(open(ben(), bens), "$.snapshot.featureRows[*].code"))
        .contains("ROOF_PANORAMIC");
    assertThat(submit(ben(), bens))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("HAS_ERRORS");
  }

  /** The availability the opened catalog has for the cell, or N when it has no such cell. */
  private String availability(MvcTestResult opened, String feature, String trim, String region) {
    return ApplicationIT.<List<Map<String, Object>>>read(opened, "$.snapshot.cells").stream()
        .filter(cell -> cell.get("featureId").equals((int) feature(feature)))
        .filter(cell -> cell.get("trimId").equals((int) trim(trim)))
        .filter(cell -> cell.get("regionCode").equals(region))
        .map(cell -> (String) cell.get("availability"))
        .findFirst()
        .orElse("N");
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

  /** Updates the catalog from the Approved version named, with a side taken for each conflict. */
  private MvcTestResult update(
      RequestPostProcessor who,
      long catalog,
      String revision,
      long approved,
      Map<String, String> resolutions) {
    var taken =
        resolutions.entrySet().stream()
            .map(taking -> "\"%s\": \"%s\"".formatted(taking.getKey(), taking.getValue()))
            .collect(java.util.stream.Collectors.joining(", ", "{", "}"));

    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/merge", catalog),
        revision,
        "{\"approvedCatalogId\": %d, \"resolutions\": %s}".formatted(approved, taken));
  }
}
