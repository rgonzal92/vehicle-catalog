package dev.rgonz.catalog.document;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that an admin uploads documents about a vehicle line's model year, sees them listed, and
 * deletes them, and what an upload is held to. Each test starts from the seeded library and no
 * documents.
 */
class DocumentsIT extends ApplicationIT {
  private static final byte[] A_PDF =
      "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

  private static final byte[] A_NOTE =
      "# Launch notes\n\nThe hybrid follows in the autumn.\n".getBytes(StandardCharsets.UTF_8);

  @Autowired ApplicationContext application;

  private long suv;

  @BeforeEach
  void theSeedAndNoDocuments() throws Exception {
    seedLibraryAndCatalogs();
    jdbc.sql("DELETE FROM document").update();
    documentFiles().forEach(DocumentsIT::forget);
    suv = line("COMPACT_SUV");
  }

  /** An upload leaves a job for the worker, which is no part of what these tests are about. */
  @AfterEach
  void noJobs() {
    jdbc.sql("DELETE FROM document").update();
    Worker.forgets(application);
  }

  private static void forget(String key) {
    removeDocumentFile(key);
  }

  private RequestPostProcessor ada() {
    return signedInAs(Role.ADMIN, "ada");
  }

  private long line(String code) {
    return jdbc.sql("SELECT id FROM vehicle_line WHERE code = ?")
        .param(code)
        .query(Long.class)
        .single();
  }

  private MvcTestResult upload(
      RequestPostProcessor who, String fileName, byte[] file, String title, long line, int year) {
    var request =
        mvc.post()
            .uri("/api/documents")
            .multipart()
            .file(new MockMultipartFile("file", fileName, null, file))
            .param("title", title)
            .param("vehicleLineId", String.valueOf(line))
            .param("modelYear", String.valueOf(year))
            .with(csrfToken());
    return (who == null ? request : request.with(who)).exchange();
  }

  private long documents() {
    return jdbc.sql("SELECT count(*) FROM document").query(Long.class).single();
  }

  @Test
  void anAdminUploadsANoteOfEachKindAndEachIsListedAsWaitingWithItsFileKept() {
    assertThat(upload(ada(), "launch.md", A_NOTE, "Launch notes", suv, 2026)).hasStatus(201);
    assertThat(upload(ada(), "plain.txt", A_NOTE, "", suv, 2027)).hasStatus(201);
    var pdf = upload(ada(), "C:\\notes\\brochure.PDF", A_PDF, "  The brochure ", suv, 2026);

    assertThat(pdf).hasStatus(201);
    assertThat(pdf)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            {
              "title": "The brochure",
              "vehicleLine": "Compact SUV",
              "modelYear": 2026,
              "fileName": "brochure.PDF",
              "sizeBytes": %d,
              "uploadedBy": "ada",
              "status": "WAITING",
              "reason": null
            }
            """
                .formatted(A_PDF.length));
    var listed = mvc.get().uri("/api/documents").with(ada()).exchange();
    assertThat(listed).hasStatusOk();
    // Newest first. A document without a title of its own is called by its file's name.
    assertThat(listed)
        .bodyJson()
        .extractingPath("$[*].title")
        .isEqualTo(List.of("The brochure", "plain", "Launch notes"));
    assertThat(listed)
        .bodyJson()
        .extractingPath("$[*].modelYear")
        .isEqualTo(List.of(2026, 2027, 2026));
    // Each file is kept under its document's id.
    assertThat(documentFiles())
        .containsExactlyInAnyOrderElementsOf(
            jdbc.sql("SELECT id::text FROM document").query(String.class).list());
  }

  @Test
  void anUploadThatIsNotWhatADocumentMayBeIsRefusedWithItsReasonAndKeepsNothing() {
    var tooLarge = new byte[2 * 1024 * 1024 + 1];
    java.util.Arrays.fill(tooLarge, (byte) 'a');
    long inactive = line("SPORTS_COUPE");
    jdbc.sql("UPDATE vehicle_line SET active = false WHERE id = ?").param(inactive).update();

    assertThat(upload(ada(), "large.txt", tooLarge, "Large", suv, 2026))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("A document is at most 2 MB.");
    assertThat(upload(ada(), "notes.docx", A_NOTE, "Notes", suv, 2026))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("A document is a .md, a .txt, or a .pdf file.");
    assertThat(upload(ada(), "notes.pdf", A_NOTE, "Notes", suv, 2026))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("This file is named as a PDF and is not one.");
    assertThat(upload(ada(), "notes.txt", new byte[] {'a', (byte) 0xC3, 'b'}, "Notes", suv, 2026))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("This file is named as text and is not text in UTF-8.");
    assertThat(upload(ada(), "empty.md", new byte[0], "Notes", suv, 2026)).hasStatus(422);
    assertThat(upload(ada(), "notes.md", A_NOTE, "Notes", inactive, 2026))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VEHICLE_LINE_INACTIVE");
    assertThat(upload(ada(), "notes.md", A_NOTE, "Notes", -1, 2026)).hasStatus(422);
    assertThat(upload(ada(), "notes.md", A_NOTE, "Notes", suv, 2031))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Choose one of the model years.");
    assertThat(upload(ada(), "notes.md", A_NOTE, "x".repeat(81), suv, 2026))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Give the document a title of at most 80 characters.");

    assertThat(documents()).isZero();
    assertThat(documentFiles()).isEmpty();
  }

  @Test
  void aTwentyFirstDocumentIsRefused() {
    for (int document = 1; document <= 20; document++) {
      assertThat(upload(ada(), "notes.md", A_NOTE, "Notes " + document, suv, 2026)).hasStatus(201);
    }

    assertThat(upload(ada(), "notes.md", A_NOTE, "One more", suv, 2026))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("There are at most 20 documents. Delete one first.");
    assertThat(documents()).isEqualTo(20);
    assertThat(documentFiles()).hasSize(20);
  }

  @Test
  void deletingADocumentRemovesItsRowAndItsFile() {
    long kept = id(upload(ada(), "kept.md", A_NOTE, "Kept", suv, 2026));
    long gone = id(upload(ada(), "gone.md", A_NOTE, "Gone", suv, 2026));

    assertThat(mvc.delete().uri("/api/documents/{id}", gone).with(ada()).with(csrfToken()))
        .hasStatus(204);

    assertThat(documentFiles()).containsExactly(String.valueOf(kept));
    assertThat(jdbc.sql("SELECT id FROM document").query(Long.class).list()).containsExactly(kept);
    assertThat(mvc.delete().uri("/api/documents/{id}", gone).with(ada()).with(csrfToken()))
        .hasStatus(404);
  }

  @Test
  void onlyAnAdminReachesTheDocuments() {
    long there = id(upload(ada(), "notes.md", A_NOTE, "Notes", suv, 2026));

    for (var who : List.of(signedInAs(Role.AUTHOR, "ana"), signedInAs(Role.MANAGER, "mia"))) {
      assertThat(mvc.get().uri("/api/documents").with(who)).hasStatus(403);
      assertThat(upload(who, "more.md", A_NOTE, "More", suv, 2026)).hasStatus(403);
      assertThat(mvc.delete().uri("/api/documents/{id}", there).with(who).with(csrfToken()))
          .hasStatus(403);
    }
    assertThat(mvc.get().uri("/api/documents")).hasStatus(401);
    assertThat(upload(null, "more.md", A_NOTE, "More", suv, 2026)).hasStatus(401);
    assertThat(mvc.delete().uri("/api/documents/{id}", there).with(csrfToken())).hasStatus(401);

    assertThat(documents()).isEqualTo(1);
  }

  private static long id(MvcTestResult uploaded) {
    assertThat(uploaded).hasStatus(201);
    return ApplicationIT.<Integer>read(uploaded, "$.id");
  }
}
