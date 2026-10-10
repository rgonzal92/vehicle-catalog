package dev.rgonz.catalog.core;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.management.ManagementFactory;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * What the backend reports of itself. Each metric that is kept is one that is paid for, so the list
 * is short. Of the API: whether it is ready, its requests by outcome, the memory its heap uses, how
 * long an edit and a copy of a catalog take, and how many catalogs were refused at their submit,
 * approved, rejected, and updated from Approved. Of the worker: how long each job took, by its type
 * and its outcome. Of both, what they ask the language model: its requests by what they were for
 * and how they ended, the tokens in and out, and the requests that were refused; and of the API
 * also the tool calls and what the day has cost. Every other metric is turned down here, the many
 * that the libraries offer among them, so a new one is added to its list here and to the dashboard
 * that shows it.
 */
@Configuration(proxyBeanMethods = false)
class Telemetry {
  /**
   * Whether a request only asks after the backend's health. Such requests arrive every few seconds
   * for as long as the backend runs, so they are neither traced, nor counted, nor logged.
   */
  static boolean isHealthCheck(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/health");
  }

  @Bean
  ObservationPredicate healthChecksAreNotObserved() {
    return (name, context) ->
        !(context instanceof ServerRequestObservationContext request
            && isHealthCheck(request.getCarrier()));
  }

  /** What the API reports of itself. */
  private static final Set<String> OF_THE_API =
      Set.of(
          "health",
          "http.server.requests",
          "jvm.heap.used",
          "catalog.edit",
          "catalog.copy",
          "catalog.submit.refused",
          "catalog.approved",
          "catalog.rejected",
          "catalog.merged",
          "ai.call",
          "ai.tokens",
          "ai.tool.calls",
          "ai.refused",
          "ai.spent");

  /**
   * What the worker reports of itself: how long each job took, and of the model what a job asks of
   * it. It calls no tools, and the API says what the day has cost.
   */
  private static final Set<String> OF_THE_WORKER =
      Set.of("job.run", "ai.call", "ai.tokens", "ai.refused");

  @Bean
  MeterFilter onlyTheMetricsThatAreLookedAt(Environment environment) {
    var kept = environment.matchesProfiles("worker") ? OF_THE_WORKER : OF_THE_API;

    return new MeterFilter() {
      @Override
      public MeterFilterReply accept(Meter.Id id) {
        return kept.contains(id.getName()) ? MeterFilterReply.ACCEPT : MeterFilterReply.DENY;
      }

      /**
       * A request is told apart by its outcome alone, and a job by its type and its outcome: every
       * other tag would be a metric more.
       */
      @Override
      public Meter.Id map(Meter.Id id) {
        var outcome = id.getTag("outcome");
        if (outcome == null) {
          return id;
        }
        return switch (id.getName()) {
          case "http.server.requests" -> id.replaceTags(Tags.of("outcome", outcome));
          case "job.run" -> id.replaceTags(Tags.of("type", id.getTag("type"), "outcome", outcome));
          default -> id;
        };
      }
    };
  }

  /**
   * One while the backend is ready, which is what its health check answers, and zero while it is
   * not. A backend that is stopped reports neither.
   */
  @Bean
  MeterBinder health(ObjectProvider<HealthEndpoint> health) {
    return registry ->
        Gauge.builder("health", () -> isReady(health.getObject()) ? 1 : 0).register(registry);
  }

  private static boolean isReady(HealthEndpoint health) {
    return Status.UP.equals(health.healthForPath("readiness").getStatus());
  }

  /**
   * The memory the heap uses, as one number. The libraries report it for each part of the heap,
   * which is a metric for each part.
   */
  @Bean
  MeterBinder heapUsed() {
    return registry ->
        Gauge.builder(
                "jvm.heap.used",
                () -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed())
            .baseUnit("bytes")
            .register(registry);
  }
}
