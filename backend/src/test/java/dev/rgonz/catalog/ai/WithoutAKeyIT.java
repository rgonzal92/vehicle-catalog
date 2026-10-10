package dev.rgonz.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * Checks the application without a key for the model: it starts, it says that the model cannot be
 * asked and why, and it asks the model for nothing.
 */
@TestPropertySource(properties = "app.ai.api-key=")
class WithoutAKeyIT extends ApplicationIT {
  @Autowired io.micrometer.core.instrument.MeterRegistry meters;
  @Autowired org.springframework.context.ApplicationContext application;

  @Test
  void theApplicationStartsAndSaysThatTheModelCannotBeAskedAndWhy() {
    var said = mvc.get().uri("/api/ai").with(signedInAs(Role.AUTHOR, "ana")).exchange();

    assertThat(said).hasStatusOk().bodyJson().extractingPath("$.available").isEqualTo(false);
    assertThat(said)
        .bodyJson()
        .extractingPath("$.reason")
        .isEqualTo("No key for the model is set.");
  }

  @Test
  void aRuleSuggestionIsRefusedAndTheModelIsNotAsked() {
    ModelStandIn.forgets();

    var refused =
        mvc.post()
            .uri("/api/catalogs/{id}/rule-suggestions", 1)
            .with(signedInAs(Role.AUTHOR, "ana"))
            .with(csrfToken())
            .contentType("application/json")
            .content("{\"sentence\": \"Leather needs premium audio\"}")
            .exchange();

    assertThat(refused)
        .hasStatus(503)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("AI_UNAVAILABLE");
    assertThat(ModelStandIn.asked()).isEmpty();
    assertThat(meters.get("ai.refused").tag("reason", "NO_KEY").counter().count())
        .as("refusals counted for want of a key")
        .isEqualTo(1);
    assertThat(meters.find("ai.spent").gauge())
        .as("what is reported of a day's cost where nothing can be spent")
        .isNull();
  }

  @Test
  void anUploadedDocumentEndsAsFailedForWantOfAKeyAndNothingIsSent() throws Exception {
    seedLibraryAndCatalogs();
    Worker.forgets(application);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM document").update();
    long line = jdbc.sql("SELECT min(id) FROM vehicle_line").query(Long.class).single();
    var admin = signedInAs(Role.ADMIN, "ada");

    assertThat(
            mvc.post()
                .uri("/api/documents")
                .multipart()
                .file(new MockMultipartFile("file", "notes.md", null, "A note.".getBytes()))
                .param("title", "Notes")
                .param("vehicleLineId", String.valueOf(line))
                .param("modelYear", "2026")
                .with(csrfToken())
                .with(admin))
        .hasStatus(201);
    Worker.runs(application);

    var listed = mvc.get().uri("/api/documents").with(admin).exchange();
    assertThat(listed).bodyJson().extractingPath("$[0].status").isEqualTo("FAILED");
    assertThat(listed)
        .bodyJson()
        .extractingPath("$[0].reason")
        .isEqualTo("No key for the model is set.");
    assertThat(ModelStandIn.embedded()).isEmpty();
    jdbc.sql("DELETE FROM document").update();
    Worker.forgets(application);
  }
}
