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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * What the backend reports of itself. Each metric that is kept is one that is paid for, so the list
 * is short: whether the backend is ready, its requests by outcome, the memory its heap uses, and
 * how long an edit and a copy of a catalog take. Every other metric is turned down here, the many
 * that the libraries offer among them, so a new one is added to this list and to the dashboard that
 * shows it.
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

  @Bean
  MeterFilter onlyTheMetricsThatAreLookedAt() {
    return new MeterFilter() {
      @Override
      public MeterFilterReply accept(Meter.Id id) {
        return switch (id.getName()) {
          case "health", "http.server.requests", "jvm.heap.used", "catalog.edit", "catalog.copy" ->
              MeterFilterReply.ACCEPT;
          default -> MeterFilterReply.DENY;
        };
      }

      /** A request is told apart by its outcome alone: every other tag would be a metric more. */
      @Override
      public Meter.Id map(Meter.Id id) {
        var outcome = id.getTag("outcome");
        return id.getName().equals("http.server.requests") && outcome != null
            ? id.replaceTags(Tags.of("outcome", outcome))
            : id;
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
