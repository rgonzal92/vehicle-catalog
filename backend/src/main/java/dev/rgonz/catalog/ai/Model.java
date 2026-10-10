package dev.rgonz.catalog.ai;

import dev.rgonz.catalog.core.ApiException;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
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
 * how much it may say. What a person typed is given to it as data beside its instructions, never as
 * part of them, and nothing that is sent or received is written to the log. Without a key the
 * application runs as it does otherwise, and the model cannot be asked.
 */
@Component
public class Model {
  private static final Logger log = LoggerFactory.getLogger(Model.class);

  /** The model, or null when there is no key to ask it with. */
  private final ChatModel chat;

  private final String name;
  private final Duration timeout;
  private final JsonMapper json;

  Model(
      @Value("${app.ai.api-key}") String key,
      @Value("${app.ai.base-url}") String baseUrl,
      @Value("${app.ai.model}") String name,
      @Value("${app.ai.timeout}") Duration timeout,
      ObservationRegistry observations,
      JsonMapper json) {
    this.name = name;
    this.timeout = timeout;
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

  /** Whether the model can be asked now, and why not when it cannot. */
  public Availability availability() {
    return chat == null
        ? new Availability(false, "No key for the model is set.")
        : new Availability(true, null);
  }

  /**
   * Asks the model once and answers with what it said, which is JSON of the shape that was asked
   * for unless the model did otherwise.
   *
   * @throws ApiException when the model cannot be asked, and when it failed or was too slow
   */
  public String ask(Question question) {
    var availability = availability();
    if (!availability.available()) {
      throw ApiException.unavailable("AI_UNAVAILABLE", availability.reason());
    }
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
      return chat.call(
              new Prompt(
                  List.of(
                      new SystemMessage(question.instructions()),
                      new UserMessage(json.writeValueAsString(question.given()))),
                  options))
          .getResult()
          .getOutput()
          .getText();
    } catch (RuntimeException failure) {
      // What kind of failure it was, and nothing of what was said to the model or by it.
      log.warn("The model did not answer: {}", failure.getClass().getSimpleName());
      throw ApiException.unavailable("AI_FAILED", "The model did not answer.");
    }
  }

  /** Whether the model can be asked, and why not. */
  public record Availability(boolean available, String reason) {}

  /**
   * One thing to ask the model.
   *
   * @param instructions what the model is to do, which holds nothing that a person typed
   * @param given what it is to do it with, which is sent as JSON
   * @param answerSchema the JSON schema its answer has to fit
   * @param mostOutputTokens the most tokens it may answer with
   */
  public record Question(
      String instructions, Object given, String answerSchema, int mostOutputTokens) {}
}
