package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a catalog is told what was decided about it: by the catalog itself while
 * the decision stands, and by its history for good. Each test starts from the seeded catalogs and a
 * working copy of Compact SUV 2026 that Ana, an author, owns and has submitted.
 */
class DecisionsIT extends WorkingCopyTests {
  /** Ana's working copy, Submitted, at revision 1. */
  private long copy;

  @BeforeEach
  void anasSubmission() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(submit(ana(), copy)).hasStatusOk();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void aRejectedCatalogSaysSoUntilItIsSubmittedAgainAndItsHistoryKeepsTheRejection() {
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.decision").isNull();

    decide(mia(), "reject", copy, "The hybrid needs its battery cooling.");

    Map<String, Object> decision = read(open(ana(), copy), "$.decision");
    assertThat(decision)
        .containsEntry("decision", "REJECTED")
        .containsEntry("reviewer", "mia")
        .containsEntry("comment", "The hybrid needs its battery cooling.")
        .containsKey("at");
    setCells(ana(), copy, "\"" + revision(copy) + "\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    assertThat(open(ana(), copy))
        .as("while its owner puts it right")
        .bodyJson()
        .extractingPath("$.decision.decision")
        .isEqualTo("REJECTED");

    submit(ana(), copy);

    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.decision").isNull();
    edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null);
    assertThat(open(ana(), copy))
        .as("withdrawn after it was submitted again")
        .bodyJson()
        .extractingPath("$.decision")
        .isNull();
    var history = history(ana(), copy, "");
    assertThat(ApplicationIT.<List<String>>read(history, "$.items[*].kind"))
        .containsExactly("WITHDRAWN", "SUBMITTED", "CELL_SET", "REJECTED", "SUBMITTED");
    Map<String, Object> rejection = read(history, "$.items[3]");
    assertThat(rejection)
        .containsEntry("actor", "mia")
        .containsEntry("newValue", "The hybrid needs its battery cooling.")
        .containsEntry("oldValue", null)
        .containsEntry("featureName", null);
    assertThat(history).bodyJson().extractingPath("$.total").isEqualTo(5);
  }

  @Test
  void aReturnedCatalogSaysThatAnotherCatalogOfItsLineageWasApprovedFirst() {
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    submit(ben(), bens);

    decide(mia(), "approve", copy, "Good to go.");

    Map<String, Object> decision = read(open(ben(), bens), "$.decision");
    assertThat(decision)
        .containsEntry("decision", "RETURNED_STALE")
        .containsEntry("reviewer", null)
        .containsEntry("comment", null);
    Map<String, Object> returned = read(history(ben(), bens, ""), "$.items[0]");
    assertThat(returned)
        .containsEntry("kind", "RETURNED_STALE")
        .containsEntry("actor", null)
        .containsEntry("newValue", "Another catalog of its lineage was approved first.");
  }

  @Test
  void anApprovedVersionsHistoryShowsItsApprovalForEveryone() {
    decide(mia(), "approve", copy, "Good to go.");

    var history = history(ben(), copy, "");

    assertThat(open(ben(), copy)).bodyJson().extractingPath("$.decision").isNull();
    assertThat(ApplicationIT.<List<String>>read(history, "$.items[*].kind"))
        .containsExactly("APPROVED", "SUBMITTED");
    Map<String, Object> approval = read(history, "$.items[0]");
    assertThat(approval).containsEntry("actor", "mia").containsEntry("newValue", "Good to go.");
  }

  @Test
  void changesAndDecisionsAreOneTimelineThatPagesWithoutLosingOrRepeatingAny() {
    decide(mia(), "reject", copy, "First, the cooling.");
    setCells(ana(), copy, "\"" + revision(copy) + "\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    submit(ana(), copy);
    decide(mia(), "reject", copy, "Now the roof.");

    var whole = ApplicationIT.<List<Integer>>read(history(ana(), copy, ""), "$.items[*].id");
    var paged = new ArrayList<Integer>();
    for (var page = 0; page < 3; page++) {
      var answer = history(ana(), copy, "?page=%d&size=2".formatted(page));
      assertThat(answer).bodyJson().extractingPath("$.total").isEqualTo(5);
      paged.addAll(ApplicationIT.<List<Integer>>read(answer, "$.items[*].id"));
    }

    assertThat(ApplicationIT.<List<String>>read(history(ana(), copy, ""), "$.items[*].kind"))
        .containsExactly("REJECTED", "SUBMITTED", "CELL_SET", "REJECTED", "SUBMITTED");
    assertThat(whole).doesNotHaveDuplicates().hasSize(5);
    assertThat(paged).isEqualTo(whole);
  }

  private MvcTestResult submit(RequestPostProcessor who, long catalog) {
    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/submit", catalog),
        "\"" + revision(catalog) + "\"",
        "{}");
  }

  /** Sends a decision about the catalog as it is, with the comment. */
  private MvcTestResult decide(
      RequestPostProcessor who, String decision, long catalog, String comment) {
    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/{decision}", catalog, decision),
        "\"" + revision(catalog) + "\"",
        "{\"comment\": \"%s\"}".formatted(comment));
  }

  private MvcTestResult history(RequestPostProcessor who, long catalog, String paging) {
    return mvc.get().uri("/api/catalogs/" + catalog + "/changes" + paging).with(who).exchange();
  }
}
