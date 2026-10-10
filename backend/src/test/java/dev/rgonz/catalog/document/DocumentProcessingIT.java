package dev.rgonz.catalog.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks what the worker makes of an uploaded document: that it is read, split into passages, and
 * kept with what each passage means, how far the list says it is, and what becomes of a document
 * that cannot be read or whose passages the model cannot be asked about. A stand-in answers for the
 * embedding model. Each test starts from the seeded library and no documents.
 */
class DocumentProcessingIT extends ApplicationIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired ApplicationContext application;
  @Autowired DocumentProcessing processing;
  @Autowired TransactionTemplate transactions;

  private long suv;

  @BeforeEach
  void theSeedAndNoDocuments() throws Exception {
    seedLibraryAndCatalogs();
    Worker.forgets(application);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
    jdbc.sql("DELETE FROM document").update();
    documentFiles().forEach(ApplicationIT::removeDocumentFile);
    suv =
        jdbc.sql("SELECT id FROM vehicle_line WHERE code = 'COMPACT_SUV'")
            .query(Long.class)
            .single();
  }

  @AfterEach
  void nothingSpentAndNoJobs() {
    jdbc.sql("DELETE FROM ai_spend").update();
    Worker.forgets(application);
  }

  private RequestPostProcessor ada() {
    return signedInAs(Role.ADMIN, "ada");
  }

  private static String sentences(int from, int to) {
    return IntStream.rangeClosed(from, to)
        .mapToObj(
            number -> "Sentence number %d says something about the catalog.".formatted(number))
        .collect(Collectors.joining(" "));
  }

  private long upload(String fileName, byte[] file) {
    var uploaded =
        mvc.post()
            .uri("/api/documents")
            .multipart()
            .file(new MockMultipartFile("file", fileName, null, file))
            .param("title", "A document")
            .param("vehicleLineId", String.valueOf(suv))
            .param("modelYear", "2026")
            .with(csrfToken())
            .with(ada())
            .exchange();
    assertThat(uploaded).hasStatus(201);
    return ApplicationIT.<Integer>read(uploaded, "$.id");
  }

  private long upload(String text) {
    return upload("notes.md", text.getBytes(StandardCharsets.UTF_8));
  }

  /** How the list shows a document: its status, its reason if it has one, and its passages. */
  private String listed(long id) {
    var documents = mvc.get().uri("/api/documents").with(ada()).exchange();
    assertThat(documents).hasStatusOk();
    for (Map<String, Object> one : ApplicationIT.<List<Map<String, Object>>>read(documents, "$")) {
      if (((Number) one.get("id")).longValue() == id) {
        return one.get("status")
            + (one.get("reason") == null ? "" : ": " + one.get("reason"))
            + ", "
            + one.get("passages")
            + " passages";
      }
    }
    return "not listed";
  }

  private MvcTestResult processAgain(RequestPostProcessor who, long id) {
    return mvc.post().uri("/api/documents/{id}/process", id).with(who).with(csrfToken()).exchange();
  }

  private List<String> passagesOf(long id) {
    return jdbc.sql("SELECT text FROM document_passage WHERE document_id = ? ORDER BY position")
        .param(id)
        .query(String.class)
        .list();
  }

  /** The texts the stand-in was asked the meaning of, in order. */
  private static List<String> sentToTheModel() {
    var texts = new ArrayList<String>();
    for (var request : ModelStandIn.embedded()) {
      request.path("input").forEach(text -> texts.add(text.asString()));
    }
    return texts;
  }

  @Test
  void anUploadedDocumentIsReadSplitAndKeptWithWhatEachPassageMeans() {
    long note = upload("# Launch notes\n\nThe hybrid follows in the autumn.\n");
    long longer = upload(sentences(1, 200));
    assertThat(listed(note)).isEqualTo("WAITING, 0 passages");

    Worker.runs(application);

    assertThat(listed(note)).isEqualTo("READY, 1 passages");
    assertThat(passagesOf(note))
        .containsExactly("# Launch notes\n\nThe hybrid follows in the autumn.");
    var passages = passagesOf(longer);
    assertThat(passages).hasSizeGreaterThan(5);
    assertThat(listed(longer)).isEqualTo("READY, %d passages".formatted(passages.size()));
    assertThat(sentToTheModel())
        .containsExactlyInAnyOrderElementsOf(
            java.util.stream.Stream.concat(passagesOf(note).stream(), passages.stream()).toList());
    assertThat(
            jdbc.sql("SELECT DISTINCT vector_dims(meaning) FROM document_passage")
                .query(Integer.class)
                .list())
        .containsExactly(1536);
    // What a passage means is what the model said of it.
    assertThat(
            jdbc.sql(
                    """
                    SELECT text FROM document_passage
                    ORDER BY meaning <=> :meaning::vector, document_id, position LIMIT 1
                    """)
                .param(
                    "meaning",
                    DocumentProcessing.asAVector(
                        ModelStandIn.meaningOf("When does the hybrid follow?")))
                .query(String.class)
                .single())
        .contains("The hybrid follows in the autumn.");
    var requests = ModelStandIn.embedded();
    assertThat(requests)
        .allSatisfy(
            request ->
                assertThat(request.path("model").asString()).isEqualTo("text-embedding-3-small"));
    // Who uploaded it is no part of what the model is sent.
    assertThat(requests.toString()).doesNotContain("\"user\"").doesNotContain("ada");
    assertThat(
            jdbc.sql(
                    """
                    SELECT count(*) FROM ai_spend
                    WHERE purpose = 'DOCUMENT_EMBEDDING' AND user_id = :ada AND spent > 0
                    """)
                .param("ada", person("ada"))
                .query(Long.class)
                .single())
        .as("requests spent for whoever uploaded")
        .isEqualTo(requests.size());
  }

  @Test
  void aDocumentIsSeenToBeRunningWhileTheWorkerHasIt() throws Exception {
    long note = upload("The hybrid follows in the autumn.");
    ModelStandIn.takesToSayWhatTextsMean(1500);

    var working = CompletableFuture.runAsync(() -> Worker.runs(application));

    await().untilAsserted(() -> assertThat(listed(note)).isEqualTo("RUNNING, 0 passages"));
    working.get();
    assertThat(listed(note)).isEqualTo("READY, 1 passages");
  }

  @Test
  void aFileThatCannotBeReadHasNoTextOrIsTooLongEndsAsFailedWithItsReasonAfterOneTry() {
    long broken =
        upload("broken.pdf", "%PDF-1.7\nnot a PDF after all\n".getBytes(StandardCharsets.UTF_8));
    long pictures = upload("pictures.pdf", Pdfs.of(List.of(), List.of()));
    long endless = upload("endless.txt", "word ".repeat(120_000).getBytes(StandardCharsets.UTF_8));

    Worker.runs(application);

    assertThat(listed(broken)).isEqualTo("FAILED: It could not be read as a PDF., 0 passages");
    assertThat(listed(pictures)).isEqualTo("FAILED: It holds no text., 0 passages");
    assertThat(listed(endless))
        .isEqualTo(
            "FAILED: It is too long: a document is read as at most 300 passages., 0 passages");
    assertThat(ModelStandIn.embedded()).isEmpty();
    assertThat(
            jdbc.sql(
                    """
                    SELECT DISTINCT status || ' after ' || attempts FROM job
                    WHERE type = 'PROCESS_DOCUMENT'
                    """)
                .query(String.class)
                .list())
        .as("each job is done, since another try would end the same way")
        .containsExactly("SUCCEEDED after 1");
  }

  @Test
  void aPdfsTextBecomesItsPassages() {
    long brochure =
        upload(
            "brochure.pdf", Pdfs.of(List.of("Launch notes", "The hybrid follows in the autumn.")));

    Worker.runs(application);

    assertThat(listed(brochure)).isEqualTo("READY, 1 passages");
    assertThat(passagesOf(brochure).getFirst())
        .contains("Launch notes", "The hybrid follows in the autumn.");
  }

  @Test
  void processingADocumentTwiceLeavesOneSetOfPassages() {
    long note = upload(sentences(1, 80));
    Worker.runs(application);
    var passages = passagesOf(note);
    var subject =
        JSON.valueToTree(
            Map.of(DocumentProcessing.DOCUMENT, note, DocumentProcessing.PROCESSING, 0));

    transactions.executeWithoutResult(again -> processing.handle(subject));

    assertThat(passagesOf(note)).isEqualTo(passages);
  }

  @Test
  void withTheAllowanceSpentTheDocumentFailsWithThatReasonAndIsProcessedAgainOnceItCanBe() {
    jdbc.sql(
            """
            INSERT INTO ai_spend (day, user_id, purpose, reserved)
            VALUES ((now() AT TIME ZONE 'UTC')::date, :ada, 'A_TEST', 0.25)
            """)
        .param("ada", person("ada"))
        .update();
    long note = upload("The hybrid follows in the autumn.");

    Worker.runs(application);

    assertThat(listed(note))
        .isEqualTo(
            "FAILED: This account's allowance for the model is spent for today. It renews at 00:00"
                + " UTC., 0 passages");
    assertThat(ModelStandIn.embedded()).isEmpty();

    jdbc.sql("DELETE FROM ai_spend").update();
    assertThat(processAgain(ada(), note))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("WAITING");
    Worker.runs(application);

    assertThat(listed(note)).isEqualTo("READY, 1 passages");
  }

  @Test
  void whenTheModelDoesNotAnswerTheDocumentFailsAndKeepsNoPassage() {
    long note = upload(sentences(1, 200));
    ModelStandIn.cannotSayWhatTextsMean();

    Worker.runs(application);

    assertThat(listed(note)).isEqualTo("FAILED: The model did not answer., 0 passages");
    assertThat(ModelStandIn.embedded()).hasSize(1);
  }

  @Test
  void onlyAFailedDocumentIsProcessedAgainAndOnlyByAnAdmin() {
    long note = upload("The hybrid follows in the autumn.");
    Worker.runs(application);

    assertThat(processAgain(ada(), note))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Only a document that has failed is processed again.");
    assertThat(processAgain(signedInAs(Role.MANAGER, "mia"), note)).hasStatus(403);
    assertThat(processAgain(ada(), -1)).hasStatus(404);
  }

  @Test
  void aDocumentWhoseJobHasFailedForGoodSaysSoAndCanBeProcessedAgain() {
    long note = upload("The hybrid follows in the autumn.");
    // As the worker leaves a job that it could not do in three tries.
    assertThat(
            jdbc.sql("UPDATE job SET status = 'FAILED' WHERE dedupe_key = ?")
                .param("document-%d-0".formatted(note))
                .update())
        .isEqualTo(1);

    assertThat(listed(note))
        .isEqualTo(
            "FAILED: The job that reads it has failed. The page Jobs has what it last said., 0"
                + " passages");

    assertThat(processAgain(ada(), note)).hasStatusOk();
    Worker.runs(application);
    assertThat(listed(note)).isEqualTo("READY, 1 passages");
  }

  @Test
  void deletingADocumentRemovesItsPassagesAndAJobForOneThatIsGoneDoesNothing() {
    long kept = upload("The hybrid follows in the autumn.");
    Worker.runs(application);
    long gone = upload("It comes to Europe first.");

    assertThat(mvc.delete().uri("/api/documents/{id}", kept).with(ada()).with(csrfToken()))
        .hasStatus(204);
    assertThat(mvc.delete().uri("/api/documents/{id}", gone).with(ada()).with(csrfToken()))
        .hasStatus(204);
    Worker.runs(application);

    assertThat(jdbc.sql("SELECT count(*) FROM document_passage").query(Long.class).single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT DISTINCT status FROM job WHERE type = 'PROCESS_DOCUMENT'")
                .query(String.class)
                .list())
        .containsExactly("SUCCEEDED");
  }
}
