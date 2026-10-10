package dev.rgonz.catalog.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for OpenAI in tests: it answers a chat request with what a test has told it to say, and
 * keeps the requests it was sent for the test to read. No test reaches OpenAI itself.
 */
public final class ModelStandIn {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<JsonNode> ASKED = new CopyOnWriteArrayList<>();
  private static final ConcurrentLinkedDeque<Answer> ANSWERS = new ConcurrentLinkedDeque<>();
  private static final List<JsonNode> EMBEDDED = new CopyOnWriteArrayList<>();
  private static final AtomicBoolean EMBEDDINGS_FAIL = new AtomicBoolean();
  private static final AtomicLong EMBEDDINGS_TAKE = new AtomicLong();
  private static final HttpServer SERVER = started();

  private ModelStandIn() {}

  /**
   * What it does with the next request: answers, asks for a tool, fails, or keeps the caller
   * waiting. An answer says how many tokens the request was and how many it answered with.
   *
   * @param tool the tool it asks for, with the content as what it asks it with, or null
   */
  private record Answer(
      int status,
      String content,
      String tool,
      long afterMillis,
      int inputTokens,
      int outputTokens) {}

  private static HttpServer started() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", ModelStandIn::answer);
      server.createContext("/v1/embeddings", ModelStandIn::meanings);
      server.setExecutor(Executors.newCachedThreadPool());
      server.start();
      return server;
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void answer(HttpExchange exchange) throws IOException {
    ASKED.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
    var answer = ANSWERS.poll();
    if (answer == null) {
      answer = new Answer(500, "No test said what the model answers here.", null, 0, 0, 0);
    }
    try {
      Thread.sleep(answer.afterMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    var body =
        answer.status() == 200
            ? JSON.writeValueAsString(
                JSON.createObjectNode()
                    .put("id", "chatcmpl-a-test")
                    .put("object", "chat.completion")
                    .put("created", 1)
                    .put("model", "the-model-of-the-tests")
                    .<tools.jackson.databind.node.ObjectNode>set(
                        "usage",
                        JSON.createObjectNode()
                            .put("prompt_tokens", answer.inputTokens())
                            .put("completion_tokens", answer.outputTokens())
                            .put("total_tokens", answer.inputTokens() + answer.outputTokens()))
                    .set(
                        "choices",
                        JSON.createArrayNode()
                            .add(
                                JSON.createObjectNode()
                                    .put("index", 0)
                                    .put(
                                        "finish_reason",
                                        answer.tool() == null ? "stop" : "tool_calls")
                                    .set("message", message(answer)))))
            : "{\"error\": {\"message\": \"%s\", \"type\": \"server_error\"}}"
                .formatted(answer.content());
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(answer.status(), bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** What the model says: words, or a tool for the application to use and what to ask it with. */
  private static JsonNode message(Answer answer) {
    var message = JSON.createObjectNode().put("role", "assistant");
    if (answer.tool() == null) {
      return message.put("content", answer.content());
    }
    message.putNull("content");
    return message.set(
        "tool_calls",
        JSON.createArrayNode()
            .add(
                JSON.createObjectNode()
                    .put("id", "call_" + ASKED.size())
                    .put("type", "function")
                    .set(
                        "function",
                        JSON.createObjectNode()
                            .put("name", answer.tool())
                            .put("arguments", answer.content()))));
  }

  /** How many numbers the embedding model it stands in for gives a text. */
  public static final int NUMBERS = 1536;

  /**
   * Answers a request for what texts mean. A text's meaning here is made of its words and nothing
   * else: each word adds to one of the numbers, so that texts with the same words mean the same and
   * texts that share words are alike, which is enough to find one by another.
   */
  private static void meanings(HttpExchange exchange) throws IOException {
    var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
    EMBEDDED.add(request);
    var texts = new ArrayList<String>();
    if (request.path("input").isArray()) {
      request.path("input").forEach(text -> texts.add(text.asString()));
    } else {
      texts.add(request.path("input").asString());
    }
    try {
      Thread.sleep(EMBEDDINGS_TAKE.getAndSet(0));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    String body;
    int status = EMBEDDINGS_FAIL.getAndSet(false) ? 500 : 200;
    if (status == 200) {
      var data = JSON.createArrayNode();
      int tokens = 0;
      for (int index = 0; index < texts.size(); index++) {
        tokens += Math.max(1, texts.get(index).length() / 4);
        var one = JSON.createObjectNode().put("object", "embedding").put("index", index);
        var numbers = meaningOf(texts.get(index));
        if (request.path("encoding_format").asString("float").equals("base64")) {
          var bytes = ByteBuffer.allocate(NUMBERS * 4).order(ByteOrder.LITTLE_ENDIAN);
          for (float number : numbers) {
            bytes.putFloat(number);
          }
          one.put("embedding", Base64.getEncoder().encodeToString(bytes.array()));
        } else {
          var listed = one.putArray("embedding");
          for (float number : numbers) {
            listed.add(number);
          }
        }
        data.add(one);
      }
      body =
          JSON.writeValueAsString(
              JSON.createObjectNode()
                  .put("object", "list")
                  .put("model", "the-embedding-model-of-the-tests")
                  .<tools.jackson.databind.node.ObjectNode>set("data", data)
                  .set(
                      "usage",
                      JSON.createObjectNode()
                          .put("prompt_tokens", tokens)
                          .put("total_tokens", tokens)));
    } else {
      body = "{\"error\": {\"message\": \"The model is not well.\", \"type\": \"server_error\"}}";
    }
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** What a text means to the stand-in: its words, each counted in one of the numbers. */
  public static float[] meaningOf(String text) {
    var numbers = new float[NUMBERS];
    for (var word : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (!word.isEmpty()) {
        numbers[Math.floorMod(word.hashCode(), NUMBERS)] += 1;
      }
    }
    double length = 0;
    for (float number : numbers) {
      length += number * number;
    }
    if (length == 0) {
      numbers[0] = 1;
      return numbers;
    }
    for (int at = 0; at < NUMBERS; at++) {
      numbers[at] /= (float) Math.sqrt(length);
    }
    return numbers;
  }

  /** The requests for what texts mean that it was sent since it last forgot them, oldest first. */
  public static List<JsonNode> embedded() {
    return new ArrayList<>(EMBEDDED);
  }

  /** The model takes this long over the next request for what texts mean. */
  public static void takesToSayWhatTextsMean(long millis) {
    EMBEDDINGS_TAKE.set(millis);
  }

  /** The model answers the next request for what texts mean with an error. */
  public static void cannotSayWhatTextsMean() {
    EMBEDDINGS_FAIL.set(true);
  }

  /** Where the application finds it, as it would find OpenAI. */
  public static String url() {
    return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1";
  }

  /** The model answers the next request with this content, and says it used a thousand tokens. */
  public static void says(String content) {
    says(content, 900, 100);
  }

  /** The model answers the next request with this content, having used so many tokens. */
  public static void says(String content, int inputTokens, int outputTokens) {
    ANSWERS.add(new Answer(200, content, null, 0, inputTokens, outputTokens));
  }

  /**
   * The model answers the next request by asking for a tool, with what it asks it as JSON, and says
   * it used a thousand tokens.
   */
  public static void asksFor(String tool, String asked) {
    ANSWERS.add(new Answer(200, asked, tool, 0, 900, 100));
  }

  /** The model answers the next request with an error. */
  public static void fails() {
    ANSWERS.add(new Answer(500, "The model is not well.", null, 0, 0, 0));
  }

  /** The model keeps the next request waiting for longer than the application waits. */
  public static void keepsWaiting() {
    ANSWERS.add(new Answer(200, "{}", null, 4000, 0, 0));
  }

  /** The requests it was sent since it last forgot them, oldest first. */
  public static List<JsonNode> asked() {
    return new ArrayList<>(ASKED);
  }

  /** Forgets what it was asked and what it was told to say. */
  public static void forgets() {
    ASKED.clear();
    ANSWERS.clear();
    EMBEDDED.clear();
    EMBEDDINGS_FAIL.set(false);
    EMBEDDINGS_TAKE.set(0);
  }
}
