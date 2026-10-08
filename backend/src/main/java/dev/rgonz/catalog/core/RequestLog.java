package dev.rgonz.catalog.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes a line for each request: what was asked for, how it was answered, and how long that took.
 * The line carries the request's trace id, because it is written while the request is being traced,
 * so a trace leads to its line and a line to its trace. Of the address only the path is written:
 * what follows it can be a secret, as it is when the login provider sends a visitor back.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class RequestLog extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLog.class);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return Telemetry.isHealthCheck(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var started = System.nanoTime();
    // What a request is answered with when answering it fails outright.
    var status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    try {
      chain.doFilter(request, response);
      status = response.getStatus();
    } finally {
      log.info(
          "{} {} {} in {} ms",
          request.getMethod(),
          request.getRequestURI(),
          status,
          (System.nanoTime() - started) / 1_000_000);
    }
  }
}
