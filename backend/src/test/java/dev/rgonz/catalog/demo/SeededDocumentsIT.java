package dev.rgonz.catalog.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks the documents the demo starts with: that a first start and a demo reset each leave the
 * three seeded notes, uploaded as the demo's admin would upload them, and that the worker makes
 * them ready. Every other test runs with this seed turned off.
 */
@TestPropertySource(
    properties = {
      "app.documents.seeded=true",
      // The notes are uploaded as the demo's admin, who has to say who they are.
      "app.demo-accounts[2].role=admin",
      "app.demo-accounts[2].username=demo-admin",
      "app.demo-accounts[2].password=demo-password",
      "app.demo-accounts[2].subject=admin",
      "app.demo-accounts[2].display-name=Demo Admin"
    })
class SeededDocumentsIT extends ApplicationIT {
  private static final List<String> THE_THREE =
      List.of(
          "Compact SUV 2026: product notes",
          "Compact SUV 2027: product notes",
          "Sedan 2027: product notes");

  @Autowired DemoReset reset;
  @Autowired ApplicationContext application;

  @BeforeEach
  void aFirstStart() throws Exception {
    Worker.forgets(application);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
    jdbc.sql("DELETE FROM document").update();
    documentFiles().forEach(ApplicationIT::removeDocumentFile);
    seedLibraryAndCatalogs();
  }

  /** Nothing of the seed is left for the tests that follow, which run without it. */
  @AfterEach
  void noDocumentsAndNoJobs() {
    jdbc.sql("DELETE FROM document").update();
    documentFiles().forEach(ApplicationIT::removeDocumentFile);
    jdbc.sql("DELETE FROM ai_spend").update();
    Worker.forgets(application);
  }

  private RequestPostProcessor admin() {
    return signedInAs(Role.ADMIN, "admin");
  }

  /** The documents as the list has them, each as its title and how far it is. */
  private List<String> listed() {
    var documents = mvc.get().uri("/api/documents").with(admin()).exchange();
    assertThat(documents).hasStatusOk();
    return ApplicationIT.<List<Map<String, Object>>>read(documents, "$").stream()
        .map(one -> one.get("title") + ": " + one.get("status") + ", " + one.get("passages"))
        .sorted()
        .toList();
  }

  @Test
  void aFirstStartLeavesTheThreeNotesAndTheWorkerMakesThemReady() {
    assertThat(listed())
        .isEqualTo(THE_THREE.stream().map(title -> title + ": WAITING, 0").toList());
    assertThat(documentFiles()).hasSize(3);
    assertThat(
            jdbc.sql(
                    """
                    SELECT DISTINCT u.display_name FROM document d
                    JOIN app_user u ON u.id = d.uploaded_by
                    """)
                .query(String.class)
                .list())
        .as("who uploaded them")
        .containsExactly("Demo Admin");

    Worker.runs(application);

    // Each is short enough to be one passage.
    assertThat(listed()).isEqualTo(THE_THREE.stream().map(title -> title + ": READY, 1").toList());
    var choices = mvc.get().uri("/api/analyst/documents").with(admin()).exchange();
    assertThat(choices)
        .bodyJson()
        .isLenientlyEqualTo(
            """
            [
              {"vehicleLine": "Compact SUV", "modelYear": 2026, "documents": 1},
              {"vehicleLine": "Compact SUV", "modelYear": 2027, "documents": 1},
              {"vehicleLine": "Sedan", "modelYear": 2027, "documents": 1}
            ]
            """);
  }

  @Test
  void aResetWithDocumentsAVisitorUploadedLeavesExactlyTheThree() {
    Worker.runs(application);
    long line = jdbc.sql("SELECT min(id) FROM vehicle_line").query(Long.class).single();
    assertThat(
            mvc.post()
                .uri("/api/documents")
                .multipart()
                .file(
                    new MockMultipartFile("file", "mine.md", null, "A visitor's note.".getBytes()))
                .param("title", "A visitor's note")
                .param("vehicleLineId", String.valueOf(line))
                .param("modelYear", "2026")
                .with(csrfToken())
                .with(admin()))
        .hasStatus(201);
    assertThat(listed()).hasSize(4);

    reset.run();

    assertThat(listed())
        .isEqualTo(THE_THREE.stream().map(title -> title + ": WAITING, 0").toList());
    assertThat(documentFiles()).hasSize(3);
    Worker.runs(application);
    assertThat(listed()).isEqualTo(THE_THREE.stream().map(title -> title + ": READY, 1").toList());
  }

  @Test
  void whereThereAreDocumentsAlreadyNothingIsAdded() throws Exception {
    long one = jdbc.sql("SELECT min(id) FROM document").query(Long.class).single();
    assertThat(mvc.delete().uri("/api/documents/{id}", one).with(admin()).with(csrfToken()))
        .hasStatus(204);

    for (var seed : application.getBeansOfType(dev.rgonz.catalog.core.Seed.class).values()) {
      seed.run(new org.springframework.boot.DefaultApplicationArguments());
    }

    assertThat(listed()).hasSize(2);
  }
}
