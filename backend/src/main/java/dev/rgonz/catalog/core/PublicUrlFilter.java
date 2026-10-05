package dev.rgonz.catalog.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Presents every request as if it arrived at the configured public URL. The proxy in front of the
 * backend does not forward the visitor's Host header, so scheme, host, and port come from
 * configuration; login callbacks and secure cookies are then right whatever address the request
 * reached the backend on.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PublicUrlFilter extends OncePerRequestFilter {
  private final String scheme;
  private final String host;
  private final int port;

  PublicUrlFilter(@Value("${app.public-url}") URI publicUrl) {
    scheme = publicUrl.getScheme();
    host = publicUrl.getHost();
    port = publicUrl.getPort() != -1 ? publicUrl.getPort() : "https".equals(scheme) ? 443 : 80;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(new AtPublicUrl(request), response);
  }

  private final class AtPublicUrl extends HttpServletRequestWrapper {
    AtPublicUrl(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getScheme() {
      return scheme;
    }

    @Override
    public String getServerName() {
      return host;
    }

    @Override
    public int getServerPort() {
      return port;
    }

    @Override
    public boolean isSecure() {
      return "https".equals(scheme);
    }

    @Override
    public StringBuffer getRequestURL() {
      var url = new StringBuffer(scheme).append("://").append(host);
      if (port != ("https".equals(scheme) ? 443 : 80)) {
        url.append(':').append(port);
      }
      return url.append(getRequestURI());
    }
  }
}
