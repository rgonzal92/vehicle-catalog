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
 * Checks that a working copy is stale exactly when its lineage has a current Approved that is not
 * its base, and what that means for its owner. Each test starts from the seeded catalogs.
 */
class StaleIT extends WorkingCopyTests {
  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void whenOneOfTwoCopiesOfAVersionIsApprovedTheOtherIsStaleAndCannotBeSubmitted() {
    var anas = workingCopy(ana(), "COMPACT_SUV", 2026);
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(false);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.current.versionNumber").isEqualTo(2);
    assertThat(listed(ben(), bens)).containsEntry("stale", false);

    approve(ana(), anas);

    var opened = open(ben(), bens);
    assertThat(opened).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(opened).bodyJson().extractingPath("$.current.versionNumber").isEqualTo(3);
    assertThat(opened).bodyJson().extractingPath("$.current.catalogId").isEqualTo((int) anas);
    assertThat(opened).bodyJson().extractingPath("$.base.versionNumber").isEqualTo(2);
    assertThat(listed(ben(), bens)).containsEntry("stale", true);
    var refused = submit(ben(), bens);
    assertThat(refused).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("STALE");
    assertThat(refused).bodyJson().extractingPath("$.detail").asString().contains("Approved v3");
    var edited = setCells(ben(), bens, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    assertThat(edited).as("a stale Draft is edited like any other").hasStatusOk();
    assertThat(edited).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(open(ana(), anas))
        .as("an Approved version is never stale")
        .bodyJson()
        .extractingPath("$.stale")
        .isEqualTo(false);
  }

  @Test
  void aCatalogThatWasReturnedByTheApprovalIsStaleToo() {
    var anas = workingCopy(ana(), "COMPACT_SUV", 2026);
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    assertThat(submit(ben(), bens)).hasStatusOk();

    approve(ana(), anas);

    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(submit(ben(), bens)).hasStatus(409);
  }

  @Test
  void aCarryoverIsNotStaleUntilItsLineageHasItsFirstApproved() {
    var anas = workingCopy(ana(), "PICKUP_TRUCK", 2027);
    var bens = workingCopy(ben(), "PICKUP_TRUCK", 2027);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(false);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.current").isNull();

    approve(ana(), anas);

    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.current.versionNumber").isEqualTo(1);
    assertThat(open(ben(), workingCopy(ben(), "PICKUP_TRUCK", 2028)))
        .as("a carryover into another model year")
        .bodyJson()
        .extractingPath("$.stale")
        .isEqualTo(false);
  }

  @Test
  void aCatalogThatStartedEmptyIsNotStaleUntilItsLineageHasItsFirstApproved() {
    var anas = workingCopy(ana(), "SPORTS_COUPE", 2026);
    var bens = workingCopy(ben(), "SPORTS_COUPE", 2026);
    // Ana gives hers what a catalog needs: a trim sold in a region, and a feature offered there.
    edit(
        ana(),
        mvc.post().uri("/api/catalogs/{id}/trims", anas),
        "\"0\"",
        "{\"trimIds\": [%d]}".formatted(trim("Base")));
    edit(
        ana(),
        mvc.post().uri("/api/catalogs/{id}/regions", anas),
        "\"1\"",
        "{\"regionCodes\": [\"NA\"]}");
    edit(
        ana(),
        mvc.put().uri("/api/catalogs/{id}/trims/{trim}/regions", anas, trim("Base")),
        "\"2\"",
        "{\"regionCodes\": [\"NA\"]}");
    edit(
        ana(),
        mvc.post().uri("/api/catalogs/{id}/features", anas),
        "\"3\"",
        "{\"featureIds\": [%d]}".formatted(feature("FLOOR_CARPET_MATS")));
    setCells(ana(), anas, "\"4\"", cell("FLOOR_CARPET_MATS", "Base", "NA", "S"));
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(false);

    approve(ana(), anas);

    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.base").isNull();
  }

  /** Submits the owner's catalog as it is, and has Mia approve it. */
  private void approve(RequestPostProcessor owner, long catalog) {
    assertThat(submit(owner, catalog)).hasStatusOk();
    assertThat(
            edit(
                mia(),
                mvc.post().uri("/api/catalogs/{id}/approve", catalog),
                "\"" + revision(catalog) + "\"",
                "{}"))
        .hasStatusOk();
  }

  private MvcTestResult submit(RequestPostProcessor who, long catalog) {
    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/submit", catalog),
        "\"" + revision(catalog) + "\"",
        "{}");
  }

  /** The working copy as its owner's list has it. */
  private Map<String, Object> listed(RequestPostProcessor who, long catalog) {
    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(who).exchange();

    return ApplicationIT.<List<Map<String, Object>>>read(mine, "$").stream()
        .filter(copy -> copy.get("id").equals((int) catalog))
        .findFirst()
        .orElseThrow();
  }
}
