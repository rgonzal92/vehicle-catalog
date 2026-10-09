package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that whoever may open a catalog has it as a spreadsheet: they ask for an export, the
 * worker builds the file, and they are sent to it. Each test starts from the seeded catalogs, a
 * working copy of Compact SUV 2026 that Ana owns, no job, and an empty bucket.
 */
class ExportsIT extends WorkingCopyTests {
  @Autowired ApplicationContext application;

  /** Ana's working copy, a Draft. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    Worker.forgets(application);
    noExportedFiles();
    seedLibraryAndCatalogs();
    Worker.forgets(application);
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
  }

  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void whoeverMayOpenACatalogExportsItAndIsSentToItsSpreadsheet() throws Exception {
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));

    var asked = ask(ana(), copy);

    assertThat(asked).hasStatus(202).bodyJson().extractingPath("$.status").isEqualTo("QUEUED");
    assertThat(asked)
        .bodyJson()
        .extractingPath("$.fileName")
        .isEqualTo("Compact SUV 2026 COMPACT_SUV 2026.xlsx");
    long export = idOf(asked);
    assertThat(download(ana(), export))
        .as("before the file is built")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_READY");

    Worker.runs(application);

    assertThat(status(ana(), export)).bodyJson().extractingPath("$.status").isEqualTo("READY");
    var sent = download(ana(), export);
    assertThat(sent).hasStatus(303);
    var link = sent.getResponse().getHeader("Location");
    assertThat(link).as("a link that works for five minutes").contains("X-Amz-Expires=300");
    var file =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(link)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(file.headers().firstValue("Content-Disposition"))
        .hasValue("attachment; filename=\"Compact SUV 2026 COMPACT_SUV 2026.xlsx\"");

    var matrix = SpreadsheetTest.rows(file.body(), 1);
    assertThat(matrix.getFirst())
        .containsExactly(
            "Category",
            "Code",
            "Feature",
            "North America: Base",
            "North America: Sport",
            "North America: Touring",
            "North America: Off-Road",
            "Europe: Base",
            "Europe: Sport",
            "Europe: Touring");
    assertThat(matrix).hasSize(1 + 151);
    assertThat(matrix)
        .as("the cell Ana set, and the one beside it that she did not")
        .anySatisfy(
            row ->
                assertThat(row.subList(1, 5))
                    .containsExactly("TRANS_MANUAL", row.get(2), "A", "-"));
    var rules = SpreadsheetTest.rows(file.body(), 2);
    assertThat(rules.getFirst()).startsWith("Kind", "Source", "Targets");
    assertThat(rules.size()).as("a row for each rule, and one for the headings").isGreaterThan(1);
  }

  @Test
  void nobodyExportsACatalogTheyMayNotOpenOrSeesAnExportThatAnotherPersonAskedFor() {
    assertThat(ask(ben(), copy)).as("another person's Draft").hasStatus(404);
    assertThat(ask(ana(), copy + 1000)).as("no catalog at all").hasStatus(404);
    assertThat(count("catalog_export")).isZero();

    long export = idOf(ask(ana(), copy));
    Worker.runs(application);

    assertThat(status(ben(), export)).hasStatus(404);
    assertThat(download(ben(), export)).hasStatus(404);
    assertThat(download(ana(), export)).hasStatus(303);
    assertThat(mvc.get().uri("/api/exports/{id}", export)).as("without a session").hasStatus(401);
  }

  @Test
  void aReviewerNoLongerDownloadsTheSpreadsheetOnceTheOwnerHasWithdrawnTheCatalog() {
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), "\"0\"", "{}"))
        .hasStatusOk();
    long export = idOf(ask(mia(), copy));
    Worker.runs(application);
    assertThat(download(mia(), export)).as("while it is submitted").hasStatus(303);

    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null))
        .hasStatusOk();

    assertThat(download(mia(), export)).hasStatus(404);
    assertThat(ask(mia(), copy)).as("and asks for no other").hasStatus(404);
  }

  @Test
  void theExportOfAnApprovedVersionCarriesTheLabelsItWasApprovedWith() throws Exception {
    var approved = approved("COMPACT_SUV", 2026, 2);

    var asked = ask(ben(), approved);
    jdbc.sql("UPDATE trim SET name = 'Sport Plus' WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE region SET name = 'Europe and Africa' WHERE code = 'EU'").update();
    jdbc.sql("UPDATE feature SET name = 'Mats' WHERE code = 'FLOOR_CARPET_MATS'").update();
    Worker.runs(application);

    assertThat(asked).bodyJson().extractingPath("$.fileName").isEqualTo("Compact SUV 2026 v2.xlsx");
    var link = download(ben(), idOf(asked)).getResponse().getHeader("Location");
    var file =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(link)).build(),
                HttpResponse.BodyHandlers.ofByteArray())
            .body();
    var matrix = SpreadsheetTest.rows(file, 1);
    assertThat(matrix.getFirst()).contains("North America: Sport", "Europe: Base");
    assertThat(matrix)
        .anySatisfy(
            row ->
                assertThat(row.subList(1, 3))
                    .containsExactly("FLOOR_CARPET_MATS", "Carpeted Floor Mats"));
  }

  @Test
  void anExportWhoseMessageIsSentTwiceLeavesOneFile() {
    long export = idOf(ask(ana(), copy));
    Worker.runs(application);

    jdbc.sql("UPDATE outbox SET sent_at = NULL").update();
    Worker.runs(application);

    assertThat(exportedFiles()).containsExactly(export + ".xlsx");
    assertThat(count("job WHERE type = 'EXPORT' AND status = 'SUCCEEDED' AND attempts = 1"))
        .isOne();
  }

  @Test
  void aWorkingCopyIsDeletedWithItsExports() {
    long export = idOf(ask(ana(), copy));

    assertThat(edit(ana(), mvc.delete().uri("/api/catalogs/{id}", copy), "\"0\"", null))
        .hasStatus(204);
    Worker.runs(application);

    assertThat(status(ana(), export)).hasStatus(404);
    assertThat(exportedFiles()).as("the job finds nothing left to build").isEmpty();
    assertThat(count("job WHERE type = 'EXPORT' AND status = 'SUCCEEDED'")).isOne();
  }

  private MvcTestResult ask(RequestPostProcessor who, long catalog) {
    return edit(who, mvc.post().uri("/api/catalogs/{id}/exports", catalog), null, null);
  }

  private MvcTestResult status(RequestPostProcessor who, long export) {
    return mvc.get().uri("/api/exports/{id}", export).with(who).exchange();
  }

  private MvcTestResult download(RequestPostProcessor who, long export) {
    return mvc.get().uri("/api/exports/{id}/download", export).with(who).exchange();
  }
}
