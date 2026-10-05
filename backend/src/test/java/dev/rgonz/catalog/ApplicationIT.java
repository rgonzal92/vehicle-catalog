package dev.rgonz.catalog;

import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the application against one shared PostgreSQL container and one shared mock login server.
 * The containers start once and are never stopped between classes, so the cached Spring context
 * stays connected to them.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "app.public-url=" + ApplicationIT.PUBLIC_URL,
      "app.logout-url=https://login.example.test/logout",
      "app.demo-accounts[0].role=author",
      "app.demo-accounts[0].username=demo-author",
      "app.demo-accounts[0].password=demo-password",
      "spring.security.oauth2.client.registration.cognito.client-id=" + ApplicationIT.CLIENT_ID,
      "spring.security.oauth2.client.registration.cognito.client-secret=test-secret"
    })
@AutoConfigureMockMvc
public abstract class ApplicationIT {
  /** The address visitors use, which differs from the address the tests call. */
  protected static final String PUBLIC_URL = "https://catalog.example.test";

  protected static final String CLIENT_ID = "catalog-test";

  @ServiceConnection
  static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6");

  /** Signing in as "author" yields the claims Amazon Cognito would send for a demo author. */
  static final GenericContainer<?> LOGIN_SERVER =
      new GenericContainer<>("ghcr.io/navikt/mock-oauth2-server:6.0.4")
          .withExposedPorts(8080)
          .withEnv(
              "JSON_CONFIG",
              """
              {
                "interactiveLogin": true,
                "tokenCallbacks": [
                  {
                    "issuerId": "default",
                    "requestMappings": [
                      {
                        "requestParam": "subject",
                        "match": "author",
                        "claims": {
                          "cognito:username": "demo-author",
                          "email": "author@example.test",
                          "name": "Demo Author",
                          "cognito:groups": ["author"]
                        }
                      }
                    ]
                  }
                ]
              }
              """);

  static {
    DATABASE.start();
    LOGIN_SERVER.start();
  }

  @DynamicPropertySource
  static void loginProvider(DynamicPropertyRegistry registry) {
    var provider = "spring.security.oauth2.client.provider.cognito.";
    registry.add(provider + "authorization-uri", () -> loginServerUrl() + "/authorize");
    registry.add(provider + "token-uri", () -> loginServerUrl() + "/token");
    registry.add(provider + "jwk-set-uri", () -> loginServerUrl() + "/jwks");
  }

  protected static String loginServerUrl() {
    return "http://127.0.0.1:" + LOGIN_SERVER.getMappedPort(8080) + "/default";
  }

  @LocalServerPort protected int port;
  @Autowired protected MockMvcTester mvc;
  @Autowired protected JdbcClient jdbc;

  /**
   * Sends the CSRF token the way the frontend does: the cookie's value echoed in a header. Spring
   * Security's own {@code csrf()} test helper is not used, because it replaces the application's
   * token store for every later test that shares this context, including the ones over real HTTP.
   */
  protected static RequestPostProcessor csrfToken() {
    return request -> {
      request.setCookies(new Cookie("XSRF-TOKEN", "csrf-test-token"));
      request.addHeader("X-XSRF-TOKEN", "csrf-test-token");
      return request;
    };
  }
}
