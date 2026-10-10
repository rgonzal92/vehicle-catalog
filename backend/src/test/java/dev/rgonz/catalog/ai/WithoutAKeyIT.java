package dev.rgonz.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Checks the application without a key for the model: it starts, it says that the model cannot be
 * asked and why, and it asks the model for nothing.
 */
@TestPropertySource(properties = "app.ai.api-key=")
class WithoutAKeyIT extends ApplicationIT {
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
  }
}
