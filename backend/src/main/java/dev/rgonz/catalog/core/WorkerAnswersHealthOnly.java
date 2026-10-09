package dev.rgonz.catalog.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The worker runs jobs and serves no one. It answers whoever asks after its health, which is how a
 * release tells that it has come up, and has nothing at any other address.
 */
@Component
@Profile("worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorkerAnswersHealthOnly extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (Telemetry.isHealthCheck(request)) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    }
  }
}
