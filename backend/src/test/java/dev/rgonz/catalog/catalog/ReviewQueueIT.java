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
 * Checks that managers and admins find the Submitted catalogs in a review queue and can open them,
 * and that nobody else can. Each test starts from the seeded catalogs and a working copy of Compact
 * SUV 2026 that Ana, an author, owns and has submitted with a note.
 */
class ReviewQueueIT extends WorkingCopyTests {
  /** Ana's working copy, Submitted, at revision 1. */
  private long copy;

  @BeforeEach
  void anasSubmission() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(submit(ana(), copy, "\"0\"", "Winter content.")).hasStatusOk();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void aSubmittedCatalogIsInTheQueueOfEveryManagerAndAdminUntilItIsWithdrawn() {
    for (var reviewer : List.of(mia(), signedInAs(Role.ADMIN, "adam"))) {
      List<Map<String, Object>> queue = read(queue(reviewer), "$");

      assertThat(queue).hasSize(1);
      assertThat(queue.getFirst())
          .containsEntry("id", (int) copy)
          .containsEntry("name", "COMPACT_SUV 2026")
          .containsEntry("vehicleLine", "Compact SUV")
          .containsEntry("modelYear", 2026)
          .containsEntry("owner", "ana")
          .containsEntry("note", "Winter content.")
          .containsEntry("own", false)
          .containsKey("submittedAt");
    }

    edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null);

    assertThat(ApplicationIT.<List<Object>>read(queue(mia()), "$")).isEmpty();
  }

  @Test
  void theQueueListsTheCatalogSubmittedFirstAtTheTopAndMarksTheReviewersOwn() {
    var mias = workingCopy(mia(), "COMPACT_SUV", 2027);
    submit(mia(), mias, "\"0\"", null);

    List<Map<String, Object>> queue = read(queue(mia()), "$");

    assertThat(queue)
        .extracting(listed -> listed.get("id"))
        .containsExactly((int) copy, (int) mias);
    assertThat(queue).extracting(listed -> listed.get("own")).containsExactly(false, true);
    assertThat(queue.getLast()).containsEntry("note", null);
  }

  @Test
  void aReviewerOpensASubmittedCatalogReadOnlyAndADraftNotAtAll() {
    var opened = open(mia(), copy);

    assertThat(opened).hasStatusOk();
    assertThat(opened).bodyJson().extractingPath("$.owned").isEqualTo(false);
    assertThat(opened).bodyJson().extractingPath("$.owner").isEqualTo("ana");
    assertThat(opened).bodyJson().extractingPath("$.submitNote").isEqualTo("Winter content.");
    assertThat(opened).bodyJson().extractingPath("$.issues").isNotNull();
    assertThat(mvc.get().uri("/api/catalogs/{id}/changes", copy).with(mia()))
        .as("its History")
        .hasStatusOk();
    assertThat(mvc.get().uri("/api/catalogs/{id}/diff?against=base", copy).with(mia()))
        .as("what it changes")
        .hasStatusOk();
    assertThat(setCells(mia(), copy, "\"1\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .as("an edit of it")
        .hasStatus(404);
    assertThat(edit(mia(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null))
        .as("withdrawing it")
        .hasStatus(404);

    edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null);

    assertThat(open(mia(), copy)).as("once it is a Draft again").hasStatus(404);
    assertThat(mvc.get().uri("/api/catalogs/{id}/changes", copy).with(mia())).hasStatus(404);
  }

  @Test
  void anAuthorGetsNeitherTheQueueNorAnotherPersonsSubmittedCatalog() {
    assertThat(queue(ben())).hasStatus(403);
    assertThat(open(ben(), copy)).hasStatus(404);
    assertThat(mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ben())).hasStatus(404);
    assertThat(mvc.get().uri("/api/catalogs?scope=mine").with(ben()))
        .as("the list of their own, at the same address")
        .hasStatusOk();
  }

  private MvcTestResult queue(RequestPostProcessor who) {
    return mvc.get().uri("/api/catalogs?scope=review").with(who).exchange();
  }

  private MvcTestResult submit(
      RequestPostProcessor who, long catalog, String revision, String note) {
    var body = note == null ? "{}" : "{\"note\": \"%s\"}".formatted(note);

    return edit(who, mvc.post().uri("/api/catalogs/{id}/submit", catalog), revision, body);
  }
}
