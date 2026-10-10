package dev.rgonz.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Checks what the application reports of asking the model: each request timed under what it was for
 * and how it ended, the tokens in and out, the tool calls, the refusals by their reason, and what
 * the day has cost. A stand-in answers for the model.
 */
class ModelMetricsIT extends ApplicationIT {
  @Autowired Model model;
  @Autowired Allowance allowance;
  @Autowired MeterRegistry meters;

  private long ana;

  @BeforeEach
  void anaAsks() {
    ana = person("ana-who-asks");
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  @AfterEach
  void nothingSpent() {
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  private String ask(Long accountId, String purpose) {
    return model.ask(
        new Model.Question(
            accountId,
            purpose,
            "Say something.",
            Map.of("of", "this"),
            "{\"type\": \"object\"}",
            100));
  }

  /** How many requests for this have been timed with this outcome. */
  private long timed(String purpose, String outcome) {
    var timer = meters.find("ai.call").tag("purpose", purpose).tag("outcome", outcome).timer();
    return timer == null ? 0 : timer.count();
  }

  private double counted(String counter, String... tags) {
    var found = meters.find(counter).tags(tags).counter();
    return found == null ? 0 : found.count();
  }

  @Test
  void aRequestThatSucceedsAndOneThatFailsAreEachTimedOnceUnderWhatTheyWereForAndHowTheyEnded() {
    var in = counted("ai.tokens", "direction", "in");
    var out = counted("ai.tokens", "direction", "out");
    ModelStandIn.says("{}", 700, 30);
    ModelStandIn.fails();

    ask(ana, "TIMED_ONCE");
    assertThatThrownBy(() -> ask(ana, "TIMED_ONCE")).isInstanceOf(ApiException.class);

    assertThat(timed("TIMED_ONCE", "SUCCESS")).isEqualTo(1);
    assertThat(timed("TIMED_ONCE", "FAILURE")).isEqualTo(1);
    assertThat(meters.find("ai.call").meters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .extracting(Tag::getKey)
                    .containsExactly("outcome", "purpose"));
    // The request that failed said nothing of what it used.
    assertThat(counted("ai.tokens", "direction", "in") - in).isEqualTo(700);
    assertThat(counted("ai.tokens", "direction", "out") - out).isEqualTo(30);
  }

  @Test
  void eachRequestOfAnAnswerIsTimedAndItsToolCallsAreCounted() {
    var toolCalls = counted("ai.tool.calls");
    ModelStandIn.asksFor("look_up", "{}");
    ModelStandIn.says("An answer.");
    var tool =
        new Tool() {
          @Override
          public String name() {
            return "look_up";
          }

          @Override
          public String description() {
            return "Looks something up for a test.";
          }

          @Override
          public String arguments() {
            return "{\"type\": \"object\", \"properties\": {}}";
          }

          @Override
          public Object answer(JsonNode asked, long accountId) {
            return Map.of("found", "it");
          }
        };

    model.converse(
        new Model.Conversation(
            ana,
            "WITH_A_TOOL",
            "Answer from what the tool returns.",
            List.of(new Model.Turn(true, "What is there?")),
            List.of(tool),
            6,
            100));

    assertThat(timed("WITH_A_TOOL", "SUCCESS")).isEqualTo(2);
    assertThat(counted("ai.tool.calls") - toolCalls).isEqualTo(1);
  }

  @Test
  void aRefusalIsCountedUnderItsReasonAndNothingIsSent() {
    var account = counted("ai.refused", "reason", "ACCOUNT_ALLOWANCE");
    var daily = counted("ai.refused", "reason", "DAILY_ALLOWANCE");

    // All of Ana's quarter of a dollar.
    allowance.reserve(ana, "A_TEST", 2_500_000, 0);
    assertThatThrownBy(() -> ask(ana, "A_TEST")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> model.refuseUnlessAvailable(ana)).isInstanceOf(ApiException.class);
    // And the rest of the day's dollar, by the application itself.
    allowance.reserve(null, "A_TEST", 7_500_000, 0);
    assertThatThrownBy(() -> ask(person("ben-who-asks"), "A_TEST"))
        .isInstanceOf(ApiException.class);

    assertThat(counted("ai.refused", "reason", "ACCOUNT_ALLOWANCE") - account).isEqualTo(2);
    assertThat(counted("ai.refused", "reason", "DAILY_ALLOWANCE") - daily).isEqualTo(1);
    assertThat(ModelStandIn.asked()).isEmpty();
  }

  @Test
  void whatTheDayHasCostIsReportedInDollars() {
    // Five cents, and a request that came to a thousand tokens in and a hundred out.
    allowance.reserve(ana, "A_TEST", 500_000, 0);
    ModelStandIn.says("{}", 1000, 100);
    ask(ana, "A_TEST");

    assertThat(meters.get("ai.spent").gauge().value()).isEqualTo(0.05015);
  }
}
