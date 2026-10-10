package dev.rgonz.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Checks how the model answers a person with tools on the way: that the application runs what the
 * model asks for, as the person who is signed in and without saying who that is, and what bounds an
 * answer. A stand-in answers for the model, and the tools are the tests' own.
 */
class ConversationIT extends ApplicationIT {
  @Autowired Model model;
  @Autowired Allowance allowance;

  /** Who each tool was run for, in order. */
  private final List<Long> ranFor = new ArrayList<>();

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

  /** A tool that answers with what the test makes of what it is asked. */
  private Tool tool(String name, Function<JsonNode, Object> answers) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "Looks something up for a test.";
      }

      @Override
      public String arguments() {
        return "{\"type\": \"object\", \"properties\": {\"what\": {\"type\": \"string\"}}}";
      }

      @Override
      public Object answer(JsonNode asked, long accountId) {
        ranFor.add(accountId);
        return answers.apply(asked);
      }
    };
  }

  private Model.Answer answerWith(Tool... tools) {
    return model.converse(
        new Model.Conversation(
            ana,
            "A_TEST",
            "Answer from what the tools return.",
            List.of(new Model.Turn(true, "What is there?")),
            List.of(tools),
            6,
            600));
  }

  /** What the model was sent as a tool's answer, in the given request. */
  private static String toolAnswerIn(JsonNode request) {
    for (var message : request.path("messages")) {
      if (message.path("role").asString().equals("tool")) {
        return message.path("content").asString();
      }
    }
    return null;
  }

  @Test
  void aToolTheModelAsksForIsRunForThePersonAndTheModelAnswersFromWhatItReturned() {
    ModelStandIn.asksFor("look_up", "{\"what\": \"the trims\"}");
    ModelStandIn.says("There are two trims.");

    var answer =
        answerWith(tool("look_up", asked -> Map.of("found", asked.path("what").asString())));

    assertThat(answer.text()).isEqualTo("There are two trims.");
    assertThat(answer.stopped()).isFalse();
    assertThat(answer.called())
        .containsExactly(new Model.Called("look_up", "{\"what\": \"the trims\"}"));
    assertThat(ranFor).containsExactly(ana);
    var requests = ModelStandIn.asked();
    assertThat(requests).hasSize(2);
    assertThat(requests.getFirst().path("tools").get(0).path("function").path("name").asString())
        .isEqualTo("look_up");
    assertThat(requests.getFirst().path("reasoning_effort").asString()).isEqualTo("none");
    assertThat(toolAnswerIn(requests.getLast())).isEqualTo("{\"found\":\"the trims\"}");
    // Who asks is given to the tool by the application, and is no part of what the model is sent.
    assertThat(requests.toString()).doesNotContain("accountId").doesNotContain("ana-who-asks");
    assertThat(
            jdbc.sql("SELECT count(*) FROM ai_spend WHERE user_id = ? AND purpose = 'A_TEST'")
                .param(ana)
                .query(Long.class)
                .single())
        .as("requests to the model that were reserved for")
        .isEqualTo(2);
  }

  @Test
  void anAnswerThatWouldTakeASeventhRequestEndsWithWhatItHasAndSaysThatItStopped() {
    for (int request = 0; request < 7; request++) {
      ModelStandIn.asksFor("look_up", "{\"what\": \"more\"}");
    }

    var answer = answerWith(tool("look_up", _ -> Map.of("found", "little")));

    assertThat(answer.stopped()).isTrue();
    assertThat(ModelStandIn.asked()).hasSize(6);
    assertThat(answer.called()).hasSize(5);
  }

  @Test
  void aToolsAnswerLongerThanTheLimitIsCutAndSaysSo() {
    ModelStandIn.asksFor("look_up", "{}");
    ModelStandIn.says("It is long.");

    answerWith(tool("look_up", _ -> Map.of("found", "é".repeat(20_000))));

    var sent = toolAnswerIn(ModelStandIn.asked().getLast());
    assertThat(sent.getBytes(StandardCharsets.UTF_8).length)
        .isBetween(19_900, Model.LARGEST_TOOL_ANSWER_BYTES);
    assertThat(sent).endsWith("longer than can be given. Ask for less.]").doesNotContain("�");
  }

  @Test
  void aToolThatFailsAnswersThatItCouldNotBeLookedUp() {
    ModelStandIn.asksFor("look_up", "{}");
    ModelStandIn.says("It could not be looked up.");

    var answer =
        answerWith(
            tool(
                "look_up",
                _ -> {
                  throw new IllegalStateException("a secret of the application");
                }));

    assertThat(answer.text()).isEqualTo("It could not be looked up.");
    assertThat(toolAnswerIn(ModelStandIn.asked().getLast()))
        .isEqualTo("{\"error\":\"This could not be looked up.\"}");
  }

  @Test
  void withTheAllowanceSpentInTheMiddleOfAnAnswerThePersonIsToldWhyAndNothingMoreIsSent() {
    // All of Ana's quarter of a dollar but a tenth of a cent: the question fits, and the question
    // with twenty thousand bytes of what a tool returned does not.
    allowance.reserve(ana, "A_TEST", 2_490_000, 0);
    ModelStandIn.asksFor("look_up", "{}");
    ModelStandIn.says("Never said.");

    assertThatThrownBy(() -> answerWith(tool("look_up", _ -> Map.of("found", "a".repeat(19_000)))))
        .isInstanceOfSatisfying(
            ApiException.class,
            refused -> {
              assertThat(refused.getStatusCode().value()).isEqualTo(429);
              assertThat(refused.getBody().getDetail()).contains("allowance");
            });
    assertThat(ModelStandIn.asked()).hasSize(1);
  }

  @Test
  void aToolThatIsRefusedTheModelItselfIsNoAnswerForTheModelButARefusalForThePerson() {
    ModelStandIn.asksFor("look_up", "{}");
    ModelStandIn.says("Never said.");

    assertThatThrownBy(
            () ->
                answerWith(
                    tool(
                        "look_up",
                        _ -> {
                          throw ApiException.allowanceSpent(
                              "Today's allowance for the model is spent.", Allowance.renewsAt());
                        })))
        .isInstanceOfSatisfying(
            ApiException.class,
            refused -> assertThat(refused.getStatusCode().value()).isEqualTo(429));
    assertThat(ModelStandIn.asked()).hasSize(1);
  }
}
