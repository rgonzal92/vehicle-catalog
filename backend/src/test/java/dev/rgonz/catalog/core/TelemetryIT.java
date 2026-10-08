package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.ApplicationIT;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;

/** What the backend reports of itself: a line for each request, and a short list of metrics. */
@ExtendWith(OutputCaptureExtension.class)
class TelemetryIT extends ApplicationIT {
  /** A trace id and a span id, as a log line carries them. */
  private static final String TRACE = "\\[[0-9a-f]{32}-[0-9a-f]{16}\\]";

  private final HttpClient http = HttpClient.newHttpClient();

  @Autowired private MeterRegistry meters;
  @Autowired private ApplicationContext application;

  @Test
  void aRequestLeavesALineWithItsTraceId(CapturedOutput output) throws Exception {
    get("/api/me");

    await().untilAsserted(() -> assertThat(output).containsPattern(requestLine("GET /api/me 401")));
  }

  @Test
  void theLineLeavesOutWhatFollowsThePath(CapturedOutput output) throws Exception {
    get("/api/me?code=what-the-login-provider-sent");

    await().untilAsserted(() -> assertThat(output).containsPattern(requestLine("GET /api/me 401")));
    assertThat(output).doesNotContain("what-the-login-provider-sent");
  }

  @Test
  void aHealthCheckLeavesNoLineAndIsNotCounted(CapturedOutput output) throws Exception {
    var answered = counted("SUCCESS");
    var refused = counted("CLIENT_ERROR");

    get("/api/health/readiness");
    get("/api/me");

    await().untilAsserted(() -> assertThat(counted("CLIENT_ERROR")).isGreaterThan(refused));
    assertThat(counted("SUCCESS")).isEqualTo(answered);
    assertThat(output).doesNotContain("/api/health");
  }

  @Test
  void requestsAreCountedByOutcomeAndByNothingElse() throws Exception {
    get("/api/me");

    await().untilAsserted(() -> assertThat(counted("CLIENT_ERROR")).isPositive());
    assertThat(meters.find("http.server.requests").meters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .extracting(Tag::getKey)
                    .containsExactly("outcome"));
  }

  @Test
  void onlyTheMetricsThatAreLookedAtAreKept() throws Exception {
    get("/api/me");

    await().untilAsserted(() -> assertThat(counted("CLIENT_ERROR")).isPositive());
    assertThat(meters.getMeters().stream().map(Meter::getId).map(Meter.Id::getName).distinct())
        .containsExactlyInAnyOrder(
            "health", "http.server.requests", "jvm.heap.used", "catalog.edit", "catalog.copy");
  }

  @Test
  void theHeapIsOneNumber() {
    assertThat(meters.get("jvm.heap.used").gauges()).hasSize(1);
    assertThat(meters.get("jvm.heap.used").gauge().value()).isPositive();
  }

  @Test
  void healthIsOneWhileTheBackendIsReadyAndZeroWhileItIsNot() {
    assertThat(meters.get("health").gauge().value()).isEqualTo(1);

    AvailabilityChangeEvent.publish(application, ReadinessState.REFUSING_TRAFFIC);
    try {
      assertThat(meters.get("health").gauge().value()).isZero();
    } finally {
      AvailabilityChangeEvent.publish(application, ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  private void get(String path) throws IOException, InterruptedException {
    http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
        HttpResponse.BodyHandlers.discarding());
  }

  private static Pattern requestLine(String request) {
    return Pattern.compile(TRACE + ".* : " + request + " in \\d+ ms$", Pattern.MULTILINE);
  }

  /** How many requests with the outcome have been counted. */
  private long counted(String outcome) {
    var timer = meters.find("http.server.requests").tag("outcome", outcome).timer();
    return timer == null ? 0 : timer.count();
  }
}
