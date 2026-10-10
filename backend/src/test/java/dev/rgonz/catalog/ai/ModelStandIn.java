package dev.rgonz.catalog.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
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
  private static final HttpServer SERVER = started();

  private ModelStandIn() {}

  /** What it does with the next request: answers, fails, or keeps the caller waiting. */
  private record Answer(int status, String content, long afterMillis) {}

  private static HttpServer started() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/v1/chat/completions", ModelStandIn::answer);
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
      answer = new Answer(500, "No test said what the model answers here.", 0);
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
                    .set(
                        "choices",
                        JSON.createArrayNode()
                            .add(
                                JSON.createObjectNode()
                                    .put("index", 0)
                                    .put("finish_reason", "stop")
                                    .set(
                                        "message",
                                        JSON.createObjectNode()
                                            .put("role", "assistant")
                                            .put("content", answer.content())))))
            : "{\"error\": {\"message\": \"%s\", \"type\": \"server_error\"}}"
                .formatted(answer.content());
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(answer.status(), bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** Where the application finds it, as it would find OpenAI. */
  public static String url() {
    return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1";
  }

  /** The model answers the next request with this content. */
  public static void says(String content) {
    ANSWERS.add(new Answer(200, content, 0));
  }

  /** The model answers the next request with an error. */
  public static void fails() {
    ANSWERS.add(new Answer(500, "The model is not well.", 0));
  }

  /** The model keeps the next request waiting for longer than the application waits. */
  public static void keepsWaiting() {
    ANSWERS.add(new Answer(200, "{}", 4000));
  }

  /** The requests it was sent since it last forgot them, oldest first. */
  public static List<JsonNode> asked() {
    return new ArrayList<>(ASKED);
  }

  /** Forgets what it was asked and what it was told to say. */
  public static void forgets() {
    ASKED.clear();
    ANSWERS.clear();
  }
}
