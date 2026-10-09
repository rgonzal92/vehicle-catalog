package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a working copy submits it for review and withdraws it. Each test starts
 * from the seeded catalogs and a working copy of Compact SUV 2026 that Ana owns, which has Warnings
 * and no Error.
 */
class SubmissionsIT extends WorkingCopyTests {
  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
  }

  @Test
  void theOwnerSubmitsADraftWithANoteAndItTakesNoMoreEdits() {
    var submitted = submit(ana(), copy, "\"0\"", "  Winter content, ready for review.  ");

    assertThat(submitted).hasStatusOk().headers().hasValue("ETag", "\"1\"");
    assertThat(submitted).bodyJson().extractingPath("$.revision").isEqualTo(1);
    var opened = open(ana(), copy);
    assertThat(opened).bodyJson().extractingPath("$.snapshot.status").isEqualTo("SUBMITTED");
    assertThat(opened)
        .bodyJson()
        .extractingPath("$.submitNote")
        .isEqualTo("Winter content, ready for review.");
    assertThat(opened).bodyJson().extractingPath("$.submittedAt").asString().isNotBlank();
    assertThat(setCells(ana(), copy, "\"1\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .as("an edit")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_DRAFT");
    assertThat(edit(ana(), mvc.delete().uri("/api/catalogs/{id}", copy), "\"1\"", null))
        .as("deleting it")
        .hasStatus(409);
    assertThat(submit(ana(), copy, "\"1\"", null)).as("submitting it again").hasStatus(409);
    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();
    assertThat(history).bodyJson().extractingPath("$.items[0].kind").isEqualTo("SUBMITTED");
    assertThat(history)
        .bodyJson()
        .extractingPath("$.items[0].newValue")
        .isEqualTo("Winter content, ready for review.");
  }

  @Test
  void aSubmitNeedsNoNoteAndTakesOneOfAThousandCharactersAtMost() {
    assertThat(submit(ana(), copy, "\"0\"", "x".repeat(1001)))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(revision(copy)).isZero();

    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), "\"0\"", null))
        .as("with no body at all")
        .hasStatusOk();
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.submitNote").isNull();
  }

  @Test
  void aCatalogWithAnErrorIsNotSubmittedAndTheRefusalListsItsIssues() {
    // The copy inherited the panoramic roof, which the library has retired since.
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();

    var refused = submit(ana(), copy, "\"0\"", "Ready.");

    assertThat(refused).hasStatus(422).bodyJson().extractingPath("$.code").isEqualTo("HAS_ERRORS");
    assertThat(ApplicationIT.<List<String>>read(refused, "$.issues[*].code"))
        .contains("FEATURE_RETIRED");
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
    assertThat(revision(copy)).isZero();
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isZero();
  }

  @Test
  void aCatalogOfAnInactiveVehicleLineIsEditedButNotSubmittedUntilTheLineIsActiveAgain() {
    jdbc.sql("UPDATE vehicle_line SET active = false WHERE code = 'COMPACT_SUV'").update();

    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.vehicleLineActive").isEqualTo(false);
    assertThat(submit(ana(), copy, "\"0\"", null))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VEHICLE_LINE_INACTIVE");
    assertThat(setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .as("an edit of the Draft")
        .hasStatusOk();

    jdbc.sql("UPDATE vehicle_line SET active = true WHERE code = 'COMPACT_SUV'").update();

    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.vehicleLineActive").isEqualTo(true);
    assertThat(submit(ana(), copy, "\"1\"", null)).hasStatusOk();
  }

  @Test
  void theEditingRulesHoldForSubmitInTheirOrder() {
    jdbc.sql("UPDATE vehicle_line SET active = false WHERE code = 'COMPACT_SUV'").update();

    assertThat(submit(ana(), copy, null, null))
        .as("without a revision")
        .hasStatus(428)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("REVISION_REQUIRED");
    assertThat(submit(ana(), copy, "\"7\"", null))
        .as("from another revision")
        .hasStatus(412)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("REVISION_CONFLICT");
    assertThat(submit(ben(), copy, "\"0\"", null)).as("by someone else").hasStatus(404);
    assertThat(revision(copy)).isZero();
  }

  @Test
  void aSubmitRefusedForTheStateOfTheCatalogIsCountedByItsReasonAndNoOtherSubmitIs() {
    var errors = counted("catalog.submit.refused", "reason", "HAS_ERRORS");
    var inactive = counted("catalog.submit.refused", "reason", "VEHICLE_LINE_INACTIVE");

    assertThat(submit(ana(), copy, "\"7\"", null)).as("from another revision").hasStatus(412);
    jdbc.sql("UPDATE vehicle_line SET active = false WHERE code = 'COMPACT_SUV'").update();
    assertThat(submit(ana(), copy, "\"0\"", null)).as("of an inactive line").hasStatus(409);
    jdbc.sql("UPDATE vehicle_line SET active = true WHERE code = 'COMPACT_SUV'").update();
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();
    assertThat(submit(ana(), copy, "\"0\"", null)).as("with an Error").hasStatus(422);
    jdbc.sql("UPDATE feature SET status = 'ACTIVE' WHERE code = 'ROOF_PANORAMIC'").update();
    assertThat(submit(ana(), copy, "\"0\"", null)).as("as it should be").hasStatusOk();

    assertThat(counted("catalog.submit.refused", "reason", "HAS_ERRORS")).isEqualTo(errors + 1);
    assertThat(counted("catalog.submit.refused", "reason", "VEHICLE_LINE_INACTIVE"))
        .isEqualTo(inactive + 1);
  }

  @Test
  void theOwnerWithdrawsASubmittedCatalogAndEditsItAgain() {
    submit(ana(), copy, "\"0\"", "Ready.");

    assertThat(withdraw(ben(), copy)).as("by someone else").hasStatus(404);
    var withdrawn = withdraw(ana(), copy);

    assertThat(withdrawn).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(2);
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
    assertThat(setCells(ana(), copy, "\"2\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .hasStatusOk();
    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();
    assertThat(ApplicationIT.<List<String>>read(history, "$.items[*].kind"))
        .containsExactly("CELL_SET", "WITHDRAWN", "SUBMITTED");
    assertThat(withdraw(ana(), copy))
        .as("a Draft")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_SUBMITTED");
  }

  @Test
  void aSubmittedCatalogIsInItsOwnersListWithItsStatus() {
    submit(ana(), copy, "\"0\"", null);

    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(ana()).exchange();

    assertThat(mine).bodyJson().extractingPath("$[0].status").isEqualTo("SUBMITTED");
  }

  private MvcTestResult submit(
      RequestPostProcessor who, long catalog, String revision, String note) {
    var body = note == null ? "{}" : "{\"note\": \"%s\"}".formatted(note);

    return edit(who, mvc.post().uri("/api/catalogs/{id}/submit", catalog), revision, body);
  }

  private MvcTestResult withdraw(RequestPostProcessor who, long catalog) {
    return edit(who, mvc.post().uri("/api/catalogs/{id}/withdraw", catalog), null, null);
  }
}
