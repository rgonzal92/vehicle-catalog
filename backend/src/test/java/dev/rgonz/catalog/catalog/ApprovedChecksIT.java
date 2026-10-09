package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import dev.rgonz.catalog.library.LibraryRevision;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks that every lineage's current Approved is validated again when the library changes in a way
 * that can break a catalog, and that the list of lineages says which of them need revision. Each
 * test starts from the seeded library and catalogs, whose four current Approved versions wait to be
 * checked, and no other job.
 */
class ApprovedChecksIT extends WorkingCopyTests {
  @Autowired ApplicationContext application;
  @Autowired ApprovedChecks checks;
  @Autowired LibraryRevision library;
  @Autowired JsonMapper json;

  @BeforeEach
  void seededCatalogs() throws Exception {
    Worker.forgets(application);
    seedLibraryAndCatalogs();
  }

  private RequestPostProcessor ada() {
    return signedInAs(Role.ADMIN, "ada");
  }

  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void seedingHasEveryCurrentApprovedChecked() {
    assertThat(count("job WHERE type = 'RECHECK_APPROVED' AND status = 'QUEUED'")).isEqualTo(4);
    assertThat(count("approved_check")).isZero();
    assertThat(flags()).as("a catalog that has not been checked yet").doesNotContainValue(true);

    Worker.runs(application);

    assertThat(count("job WHERE status = 'SUCCEEDED'")).isEqualTo(4);
    assertThat(
            count(
                """
                approved_check k
                JOIN lineage l ON l.current_catalog_id = k.catalog_id
                WHERE k.library_revision = %d AND (k.status = 'NEEDS_REVISION') = (k.error_count > 0)
                """,
                library.current()))
        .isEqualTo(4);
  }

  @Test
  void aGlobalRuleThatAnApprovedCatalogBreaksFlagsItsLineageAndDeletingItRemovesTheFlag() {
    Worker.runs(application);
    var before = flags();
    assertThat(before).containsEntry("Compact SUV 2026", false).containsEntry("Sedan 2027", false);

    // Adaptive cruise and the cargo cover are both Standard on a compact SUV, and on nothing else.
    var added = addTheRuleThatBreaksTheCompactSuv();

    assertThat(count("job WHERE type = 'RECHECK_APPROVED' AND status = 'QUEUED'"))
        .as("a job for each current Approved, written with the rule")
        .isEqualTo(4);
    assertThat(flags()).as("until the worker has run").isEqualTo(before);

    Worker.runs(application);

    assertThat(flags())
        .containsEntry("Compact SUV 2026", true)
        .containsEntry("Compact SUV 2027", true)
        .containsEntry("Pickup Truck 2026", before.get("Pickup Truck 2026"))
        .containsEntry("Sedan 2027", false);
    assertThat(errors("Compact SUV 2026")).isPositive();
    assertThat(errors("Sedan 2027")).isZero();

    assertThat(
            mvc.delete()
                .uri("/api/global-rules/{id}", ApplicationIT.<Integer>read(added, "$[0].id"))
                .with(ada())
                .with(csrfToken()))
        .hasStatus(204);
    Worker.runs(application);

    assertThat(flags()).isEqualTo(before);
    assertThat(errors("Compact SUV 2026")).isZero();
  }

  @Test
  void aCheckFromBeforeTheLibraryChangedAgainStoresNothing() {
    Worker.runs(application);
    var catalog = approved("COMPACT_SUV", 2026, 2);
    var atFirst = library.current();

    var added = addTheRuleThatBreaksTheCompactSuv();
    var broken = library.current();
    mvc.delete()
        .uri("/api/global-rules/{id}", ApplicationIT.<Integer>read(added, "$[0].id"))
        .with(ada())
        .with(csrfToken())
        .exchange();
    var mended = library.current();

    // The job that was written with the rule arrives only now, after the rule is gone again.
    check(catalog, broken);
    assertThat(checkedAt(catalog)).as("the result that is kept").isEqualTo(atFirst + " OK");

    check(catalog, mended);
    assertThat(checkedAt(catalog)).isEqualTo(mended + " OK");
    check(catalog, broken);
    assertThat(checkedAt(catalog)).as("an earlier job, arriving last").isEqualTo(mended + " OK");
  }

  @Test
  void aCatalogThatIsNoLongerItsLineagesCurrentApprovedIsNotCheckedAgain() {
    Worker.runs(application);
    var replaced = approved("COMPACT_SUV", 2026, 2);
    var atFirst = library.current();
    var copy = approvedCopyOfTheCompactSuv();
    addTheRuleThatBreaksTheCompactSuv();

    check(replaced, library.current());
    check(copy, library.current());

    assertThat(checkedAt(replaced)).isEqualTo(atFirst + " OK");
    assertThat(checkedAt(copy)).isEqualTo(library.current() + " NEEDS_REVISION");
  }

  @Test
  void aNewlyApprovedCatalogHasItsCheckFromItsApprovalWithoutAJob() {
    Worker.runs(application);
    Worker.forgets(application);

    var copy = approvedCopyOfTheCompactSuv();

    assertThat(checkedAt(copy)).isEqualTo(library.current() + " OK");
    assertThat(count("job WHERE type = 'RECHECK_APPROVED'")).isZero();
    assertThat(flags()).containsEntry("Compact SUV 2026", false);
  }

  @Test
  void aCheckWhoseMessageIsSentTwiceKeepsOneResult() {
    Worker.runs(application);

    jdbc.sql("UPDATE outbox SET sent_at = NULL").update();
    Worker.runs(application);

    assertThat(count("approved_check")).isEqualTo(4);
    assertThat(count("job WHERE status = 'SUCCEEDED' AND attempts = 1")).isEqualTo(4);
  }

  /** Has the job of checking the catalog against that revision of the library done, at once. */
  private void check(long catalog, long revision) {
    checks.handle(
        json.readTree("{\"catalogId\": %d, \"libraryRevision\": %d}".formatted(catalog, revision)));
  }

  /** The revision a catalog was last checked against, and what was found. */
  private String checkedAt(long catalog) {
    return jdbc.sql(
            "SELECT library_revision || ' ' || status FROM approved_check WHERE catalog_id = :id")
        .param("id", catalog)
        .query(String.class)
        .single();
  }

  private MvcTestResult addTheRuleThatBreaksTheCompactSuv() {
    var added =
        mvc.post()
            .uri("/api/global-rules")
            .with(ada())
            .with(csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"kind": "EXCLUDES", "sourceFeatureId": %d, "targetFeatureIds": [%d],
                 "allRegions": true, "regionCodes": []}
                """
                    .formatted(feature("ADAS_ADAPTIVE_CRUISE"), feature("CARGO_COVER")))
            .exchange();
    assertThat(added).hasStatus(201);
    return added;
  }

  /** Has Ana copy the compact SUV of 2026 and Mia approve the copy, and answers with its id. */
  private long approvedCopyOfTheCompactSuv() {
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), "\"0\"", "{}"))
        .hasStatusOk();
    assertThat(edit(mia(), mvc.post().uri("/api/catalogs/{id}/approve", copy), "\"1\"", "{}"))
        .hasStatusOk();
    return copy;
  }

  /** Whether each lineage's current Approved needs revision, by the lineage's name. */
  private Map<String, Boolean> flags() {
    return lineages().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                ApprovedChecksIT::named, lineage -> (Boolean) lineage.get("needsRevision")));
  }

  private int errors(String lineage) {
    return lineages().stream()
        .filter(listed -> named(listed).equals(lineage))
        .map(listed -> (Integer) listed.get("errorCount"))
        .findFirst()
        .orElseThrow();
  }

  private static String named(Map<String, Object> lineage) {
    return lineage.get("vehicleLine") + " " + lineage.get("modelYear");
  }

  private List<Map<String, Object>> lineages() {
    return ApplicationIT.read(mvc.get().uri("/api/lineages").with(ben()).exchange(), "$");
  }
}
