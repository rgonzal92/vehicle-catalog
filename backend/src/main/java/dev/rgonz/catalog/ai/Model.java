package dev.rgonz.catalog.ai;

import dev.rgonz.catalog.core.ApiException;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The language model, and the one way the application has to it. It is asked once for each thing a
 * person or a job wants of it: with a time to answer in, with no second try, and with a limit on
 * how much it may say. Before it is asked, the most the request can cost is reserved against the
 * day's allowance, and a request that would pass the allowance is not sent. What a person typed is
 * given to the model as data beside its instructions, never as part of them, and nothing that is
 * sent or received is written to the log. Without a key the application runs as it does otherwise,
 * and the model cannot be asked.
 */
@Component
public class Model {
  private static final Logger log = LoggerFactory.getLogger(Model.class);

  private static final String NO_KEY = "No key for the model is set.";

  /** What is added to a request's size for what frames it: the roles of its messages and such. */
  private static final int FRAMING_BYTES = 200;

  /** The model, or null when there is no key to ask it with. */
  private final ChatModel chat;

  private final String name;
  private final Duration timeout;
  private final Allowance allowance;
  private final JsonMapper json;

  Model(
      @Value("${app.ai.api-key}") String key,
      @Value("${app.ai.base-url}") String baseUrl,
      @Value("${app.ai.model}") String name,
      @Value("${app.ai.timeout}") Duration timeout,
      ObservationRegistry observations,
      Allowance allowance,
      JsonMapper json) {
    this.name = name;
    this.timeout = timeout;
    this.allowance = allowance;
    this.json = json;
    this.chat =
        key.isBlank()
            ? null
            : OpenAiChatModel.builder()
                .options(
                    OpenAiChatOptions.builder()
                        .apiKey(key)
                        .baseUrl(baseUrl)
                        .model(name)
                        .timeout(timeout)
                        .maxRetries(0)
                        .build())
                .observationRegistry(observations)
                .build();
  }

  /**
   * Whether the model can be asked now, and why not when it cannot: there is no key, or the day's
   * allowance or the account's is spent.
   *
   * @param accountId who would ask, or null for the application itself
   */
  public Availability availability(Long accountId) {
    if (chat == null) {
      return new Availability(false, NO_KEY, null);
    }
    return allowance
        .spentFor(accountId)
        .map(why -> new Availability(false, why, Allowance.renewsAt()))
        .orElse(new Availability(true, null, null));
  }

  /**
   * Refuses, as a request to the model would be refused, when the model cannot be asked now. It
   * lets what asks the model say so before it does anything else.
   */
  public void refuseUnlessAvailable(Long accountId) {
    var availability = availability(accountId);
    if (availability.available()) {
      return;
    }
    throw availability.renewsAt() == null
        ? ApiException.unavailable("AI_UNAVAILABLE", availability.reason())
        : ApiException.allowanceSpent(availability.reason(), availability.renewsAt());
  }

  /**
   * Asks the model once and answers with what it said, which is JSON of the shape that was asked
   * for unless the model did otherwise.
   *
   * @throws ApiException when the model cannot be asked, when the allowance does not cover the
   *     request, and when the model failed or was too slow
   */
  public String ask(Question question) {
    if (chat == null) {
      throw ApiException.unavailable("AI_UNAVAILABLE", NO_KEY);
    }
    var given = json.writeValueAsString(question.given());
    // A token is never less than a byte, so the request's size in bytes is the most tokens it can
    // be, and no tokens need counting.
    long size =
        FRAMING_BYTES
            + bytes(question.instructions())
            + bytes(given)
            + bytes(question.answerSchema());
    long reservation =
        allowance.reserve(
            question.accountId(), question.purpose(), size, question.mostOutputTokens());

    // Without reasoning the model says what it is asked for and no more, so the limit on what it
    // says is a limit on what it costs.
    var options =
        OpenAiChatOptions.builder()
            .model(name)
            // The client takes each request's time to answer in from the request's own options.
            .timeout(timeout)
            .maxRetries(0)
            .maxCompletionTokens(question.mostOutputTokens())
            .reasoningEffort("none")
            .outputSchema(question.answerSchema())
            .build();
    try {
      var answer =
          chat.call(
              new Prompt(
                  List.of(new SystemMessage(question.instructions()), new UserMessage(given)),
                  options));
      var usage = answer.getMetadata().getUsage();
      if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
        allowance.spent(
            reservation,
            usage.getPromptTokens(),
            usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
      }
      return answer.getResult().getOutput().getText();
    } catch (RuntimeException failure) {
      // What kind of failure it was, and nothing of what was said to the model or by it. What the
      // request cost is not known, so what was reserved for it stays reserved.
      log.warn("The model did not answer: {}", failure.getClass().getSimpleName());
      throw ApiException.unavailable("AI_FAILED", "The model did not answer.");
    }
  }

  private static int bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * Whether the model can be asked, and why not.
   *
   * @param renewsAt when a spent allowance is whole again, or null when that is not why
   */
  public record Availability(boolean available, String reason, Instant renewsAt) {}

  /**
   * One thing to ask the model.
   *
   * @param accountId whose request it is, or null for one of the application's own
   * @param purpose what the model is asked for, as the record of spending names it
   * @param instructions what the model is to do, which holds nothing that a person typed
   * @param given what it is to do it with, which is sent as JSON
   * @param answerSchema the JSON schema its answer has to fit
   * @param mostOutputTokens the most tokens it may answer with
   */
  public record Question(
      Long accountId,
      String purpose,
      String instructions,
      Object given,
      String answerSchema,
      int mostOutputTokens) {}
}
