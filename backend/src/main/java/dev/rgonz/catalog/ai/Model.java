package dev.rgonz.catalog.ai;

import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
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

  /** The name under which a tool is given the person who is signed in. */
  private static final String ACCOUNT = "accountId";

  /** What a tool answers with is cut at this many bytes, and says so. */
  public static final int LARGEST_TOOL_ANSWER_BYTES = 20_000;

  private static final String CUT =
      "\n[Cut off here: what was looked up is longer than can be given. Ask for less.]";

  /** The model, or null when there is no key to ask it with. */
  private final ChatModel chat;

  /** The model that says what a text means, or null when there is no key to ask it with. */
  private final EmbeddingModel embedding;

  private final String name;
  private final String embeddingName;
  private final Duration timeout;
  private final MeterRegistry metrics;
  private final Allowance allowance;
  private final JsonMapper json;

  /** Runs the tools the model asks for, giving each the person who is signed in. */
  private final ToolCallingManager toolCalling = ToolCallingManager.builder().build();

  Model(
      @Value("${app.ai.api-key}") String key,
      @Value("${app.ai.base-url}") String baseUrl,
      @Value("${app.ai.model}") String name,
      @Value("${app.ai.embedding-model}") String embeddingName,
      @Value("${app.ai.timeout}") Duration timeout,
      ObservationRegistry observations,
      MeterRegistry metrics,
      Allowance allowance,
      JsonMapper json) {
    this.name = name;
    this.embeddingName = embeddingName;
    this.timeout = timeout;
    this.metrics = metrics;
    this.embedding =
        key.isBlank()
            ? null
            : new OpenAiEmbeddingModel(
                OpenAiEmbeddingOptions.builder()
                    .apiKey(key)
                    .baseUrl(baseUrl)
                    .model(embeddingName)
                    .timeout(timeout)
                    .maxRetries(0)
                    .build(),
                observations);
    this.allowance = allowance;
    this.json = json;
    if (!key.isBlank()) {
      // Without a key nothing is spent, and what is never sent is never paid for.
      Gauge.builder("ai.spent", allowance, spent -> spent.usedToday().doubleValue())
          .description("What asking the model has come to today, reserved and spent, in US dollars")
          .register(metrics);
    }
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
        .map(why -> new Availability(false, why.words, Allowance.renewsAt()))
        .orElse(new Availability(true, null, null));
  }

  /**
   * Refuses, as a request to the model would be refused, when the model cannot be asked now. It
   * lets what asks the model say so before it does anything else.
   */
  public void refuseUnlessAvailable(Long accountId) {
    if (chat == null) {
      throw noKey();
    }
    allowance
        .spentFor(accountId)
        .ifPresent(
            why -> {
              throw allowance.refused(why);
            });
  }

  /**
   * What each text means, as the numbers the embedding model gives it, in the order of the texts.
   * It is one request, reserved against the allowance first by what is sent, since that is all it
   * is paid for by, and timed and counted as every request is.
   *
   * @param accountId whose request it is, or null for one of the application's own
   * @param purpose what it is for, as the record of spending names it
   * @throws ApiException when the model cannot be asked, when the allowance does not cover the
   *     request, and when the model failed or was too slow
   */
  public List<float[]> meaningsOf(Long accountId, String purpose, List<String> texts) {
    if (embedding == null) {
      throw noKey();
    }
    long size = FRAMING_BYTES + texts.stream().mapToLong(text -> bytes(text)).sum();
    long reservation = allowance.reserveForMeanings(accountId, purpose, size);

    var timing = Timer.start(metrics);
    EmbeddingResponse answer;
    try {
      answer =
          embedding.call(
              new EmbeddingRequest(
                  texts,
                  OpenAiEmbeddingOptions.builder()
                      .model(embeddingName)
                      .timeout(timeout)
                      .maxRetries(0)
                      .build()));
      if (answer.getResults().size() != texts.size()) {
        throw new IllegalStateException("The model answered for another number of texts.");
      }
    } catch (RuntimeException failure) {
      timing.stop(metrics.timer("ai.call", "purpose", purpose, "outcome", "FAILURE"));
      log.warn("The embedding model did not answer: {}", failure.getClass().getSimpleName());
      throw ApiException.unavailable("AI_FAILED", "The model did not answer.");
    }
    timing.stop(metrics.timer("ai.call", "purpose", purpose, "outcome", "SUCCESS"));
    var usage = answer.getMetadata().getUsage();
    if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
      allowance.spentOnMeanings(reservation, usage.getPromptTokens());
      metrics.counter("ai.tokens", "direction", "in").increment(usage.getPromptTokens());
    }

    return answer.getResults().stream()
        .sorted(Comparator.comparing(Embedding::getIndex))
        .map(Embedding::getOutput)
        .toList();
  }

  /** The refusal of a request for want of a key, which is counted as one. */
  private ApiException noKey() {
    metrics.counter("ai.refused", "reason", "NO_KEY").increment();
    return ApiException.unavailable("AI_UNAVAILABLE", NO_KEY);
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
      throw noKey();
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
    var answer =
        sent(
            question.purpose(),
            new Prompt(
                List.of(new SystemMessage(question.instructions()), new UserMessage(given)),
                options),
            reservation);

    return answer.getResult().getOutput().getText();
  }

  /**
   * Sends one request to the model and answers with what it said. The request is timed under what
   * it was for and how it ended, the tokens it used are counted, and its reservation becomes what
   * it did cost.
   */
  private ChatResponse sent(String purpose, Prompt prompt, long reservation) {
    var timing = Timer.start(metrics);
    ChatResponse answer;
    try {
      answer = chat.call(prompt);
      if (answer.getResult() == null) {
        throw new IllegalStateException("The model's answer holds nothing that it said.");
      }
    } catch (RuntimeException failure) {
      timing.stop(metrics.timer("ai.call", "purpose", purpose, "outcome", "FAILURE"));
      // What kind of failure it was, and nothing of what was said to the model or by it. What the
      // request cost is not known, so what was reserved for it stays reserved.
      log.warn("The model did not answer: {}", failure.getClass().getSimpleName());
      throw ApiException.unavailable("AI_FAILED", "The model did not answer.");
    }
    timing.stop(metrics.timer("ai.call", "purpose", purpose, "outcome", "SUCCESS"));
    var usage = answer.getMetadata().getUsage();
    if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
      long out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
      allowance.spent(reservation, usage.getPromptTokens(), out);
      metrics.counter("ai.tokens", "direction", "in").increment(usage.getPromptTokens());
      metrics.counter("ai.tokens", "direction", "out").increment(out);
    }
    return answer;
  }

  /**
   * Has the model answer a person, with tools it may ask the application to use on the way. Each
   * request to the model is reserved against the allowance like any other, and there are only so
   * many of them: an answer that would take more ends with what it has.
   *
   * @throws ApiException when the model cannot be asked, when the allowance does not cover a
   *     request, and when the model failed or was too slow
   */
  public Answer converse(Conversation conversation) {
    if (chat == null) {
      throw noKey();
    }
    var callbacks = conversation.tools().stream().map(this::callback).toList();
    long definitions =
        conversation.tools().stream()
            .mapToLong(
                tool -> bytes(tool.name()) + bytes(tool.description()) + bytes(tool.arguments()))
            .sum();
    var options =
        OpenAiChatOptions.builder()
            .model(name)
            .timeout(timeout)
            .maxRetries(0)
            .maxCompletionTokens(conversation.mostOutputTokens())
            // The model's page says that calling tools over this API needs it.
            .reasoningEffort("none")
            .toolCallbacks(callbacks)
            // Given to the tools by the application. It is no part of what the model is sent.
            .toolContext(ACCOUNT, conversation.accountId())
            .build();

    List<Message> messages = new ArrayList<>();
    messages.add(new SystemMessage(conversation.instructions()));
    for (var turn : conversation.turns()) {
      messages.add(
          turn.byThePerson()
              ? new UserMessage(json.writeValueAsString(Map.of("question", turn.text())))
              : new AssistantMessage(turn.text()));
    }
    var called = new ArrayList<Called>();
    for (int request = 1; ; request++) {
      var prompt = new Prompt(messages, options);
      long size =
          FRAMING_BYTES * (long) messages.size()
              + definitions
              + messages.stream().mapToLong(Model::bytes).sum();
      long reservation =
          allowance.reserve(
              conversation.accountId(),
              conversation.purpose(),
              size,
              conversation.mostOutputTokens());
      var response = sent(conversation.purpose(), prompt, reservation);
      var said = response.getResult().getOutput();
      var text = said.getText() == null ? "" : said.getText();
      if (!said.hasToolCalls()) {
        return new Answer(text, called, false);
      }
      if (request == conversation.mostRequests()) {
        return new Answer(text, called, true);
      }
      said.getToolCalls().forEach(call -> called.add(new Called(call.name(), call.arguments())));
      metrics.counter("ai.tool.calls").increment(said.getToolCalls().size());
      messages =
          new ArrayList<>(toolCalling.executeToolCalls(prompt, response).conversationHistory());
    }
  }

  /** A tool as the client for the model takes one: what it is, and how the application runs it. */
  private ToolCallback callback(Tool tool) {
    var definition =
        ToolDefinition.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(tool.arguments())
            .build();

    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String asked) {
        throw new IllegalStateException("A tool is run for the person who is signed in.");
      }

      @Override
      public String call(String asked, ToolContext context) {
        Object answer;
        try {
          answer = tool.answer(json.readTree(asked), (Long) context.getContext().get(ACCOUNT));
        } catch (RuntimeException failure) {
          log.warn("The tool {} could not answer: {}", tool.name(), failure.toString());
          answer = Map.of("error", "This could not be looked up.");
        }
        var said = json.writeValueAsString(answer).getBytes(StandardCharsets.UTF_8);
        if (said.length <= LARGEST_TOOL_ANSWER_BYTES) {
          return new String(said, StandardCharsets.UTF_8);
        }
        // A character cut in half reads as one that stands for what cannot be read, and is dropped.
        var start =
            new String(
                    said, 0, LARGEST_TOOL_ANSWER_BYTES - CUT.length() - 3, StandardCharsets.UTF_8)
                .replaceFirst("\uFFFD$", "");
        return start + CUT;
      }
    };
  }

  private static long bytes(Message message) {
    long size = message.getText() == null ? 0 : bytes(message.getText());
    if (message instanceof AssistantMessage said) {
      size += said.getToolCalls().stream().mapToLong(call -> bytes(call.arguments())).sum();
    }
    if (message instanceof ToolResponseMessage answers) {
      size += answers.getResponses().stream().mapToLong(one -> bytes(one.responseData())).sum();
    }
    return size;
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
   * A person's conversation with the model, to be answered.
   *
   * @param accountId whose it is
   * @param purpose what it is for, as the record of spending names it
   * @param instructions what the model is to do, which holds nothing that a person typed
   * @param turns what was said so far, the person's question last
   * @param tools what the model may ask the application to look up
   * @param mostRequests the most requests to the model one answer may take
   * @param mostOutputTokens the most tokens the model may say in one of them
   */
  public record Conversation(
      long accountId,
      String purpose,
      String instructions,
      List<Turn> turns,
      List<Tool> tools,
      int mostRequests,
      int mostOutputTokens) {}

  /** One thing that was said in a conversation, by the person or by the model. */
  public record Turn(boolean byThePerson, String text) {}

  /**
   * The model's answer to a person.
   *
   * @param called the tools the model had the application use on the way, in order
   * @param stopped whether the answer ended because it had taken as many requests as it may
   */
  public record Answer(String text, List<Called> called, boolean stopped) {}

  /** A tool the model had the application use, and what it asked it with, as JSON. */
  public record Called(String tool, String arguments) {}

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
