package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signs in through the mock login server over real HTTP, the way a browser does: start at the app,
 * sign in at the provider, come back with a code. The test calls the app on its own address while
 * the app is configured with a different public URL, as it is behind the production proxy.
 */
class LoginFlowIT extends ApplicationIT {
  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<String, String> cookies = new HashMap<>();

  @Test
  void aVisitorSignsInSeesWhoTheyAreAndSignsOut() throws Exception {
    var start = app("GET", "/api/oauth2/authorization/cognito");
    var sessionCookie = setCookie(start, "__Host-SESSION");
    assertThat(sessionCookie).contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
    assertThat(sessionCookie).contains("Path=/").doesNotContain("Domain");

    var signedIn = finishSignIn(start, "author");
    assertThat(signedIn.statusCode()).isEqualTo(302);
    assertThat(location(signedIn)).isEqualTo(PUBLIC_URL + "/dashboard");

    var me = app("GET", "/api/me");
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.body()).contains("\"name\":\"Demo Author\"", "\"email\":\"author@example.test\"");
    assertThat(me.body()).contains("\"roles\":[\"author\"]");
    assertThat(
            jdbc.sql("SELECT username FROM app_user WHERE cognito_sub = 'author'")
                .query(String.class)
                .single())
        .isEqualTo("demo-author");
    assertThat(storedSessions()).as("no token is kept in the session").doesNotContain("eyJ");
    assertThat(
            jdbc.sql("SELECT max_inactive_interval FROM spring_session")
                .query(Integer.class)
                .list())
        .as("sessions end after 2 hours idle")
        .containsOnly(7200);

    var signedOut = app("POST", "/api/logout");
    assertThat(signedOut.statusCode()).isEqualTo(200);
    assertThat(signedOut.body()).contains("logoutUrl");
    assertThat(app("GET", "/api/me").statusCode()).isEqualTo(401);
  }

  @Test
  void aPersonInTheAdminGroupHoldsEveryRole() throws Exception {
    finishSignIn(app("GET", "/api/oauth2/authorization/cognito"), "admin");

    assertThat(app("GET", "/api/me").body())
        .contains("\"roles\":[\"admin\",\"manager\",\"author\"]");
    assertThat(app("GET", "/api/vehicle-lines").statusCode()).isEqualTo(200);
  }

  @Test
  void aPersonInTheManagerGroupIsAlsoAnAuthor() throws Exception {
    finishSignIn(app("GET", "/api/oauth2/authorization/cognito"), "manager");

    assertThat(app("GET", "/api/me").body()).contains("\"roles\":[\"manager\",\"author\"]");
  }

  @Test
  void aPersonInNoGroupHoldsNoRole() throws Exception {
    finishSignIn(app("GET", "/api/oauth2/authorization/cognito"), "someone-without-a-group");

    assertThat(app("GET", "/api/me").body()).contains("\"roles\":[]");
    assertThat(app("GET", "/api/vehicle-lines").statusCode()).isEqualTo(403);
    assertThat(app("POST", "/api/logout").statusCode()).as("sign-out").isEqualTo(200);
  }

  /** Signs in at the login server under the given username and returns to the app with the code. */
  private HttpResponse<String> finishSignIn(HttpResponse<String> start, String username)
      throws Exception {
    var providerLogin =
        http.send(
            HttpRequest.newBuilder(URI.create(location(start)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var callback = URI.create(location(providerLogin));
    assertThat(callback.toString()).startsWith(PUBLIC_URL + "/api/login/oauth2/code/cognito?");

    return app("GET", callback.getRawPath() + "?" + callback.getRawQuery());
  }

  /** Calls the app on its local address, sending and keeping cookies as a browser would. */
  private HttpResponse<String> app(String method, String path) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (!cookies.isEmpty()) {
      var header = new StringBuilder();
      cookies.forEach((name, value) -> header.append(name).append('=').append(value).append("; "));
      request.header("Cookie", header.toString());
    }
    if (cookies.containsKey("XSRF-TOKEN")) {
      request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
    }

    var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    for (var cookie : response.headers().allValues("Set-Cookie")) {
      var pair = cookie.substring(0, cookie.indexOf(';')).split("=", 2);
      if (pair[1].isEmpty()) {
        cookies.remove(pair[0]);
      } else {
        cookies.put(pair[0], pair[1]);
      }
    }
    return response;
  }

  private static String setCookie(HttpResponse<String> response, String name) {
    return response.headers().allValues("Set-Cookie").stream()
        .filter(cookie -> cookie.startsWith(name + "="))
        .findFirst()
        .orElseThrow();
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElseThrow();
  }

  private String storedSessions() {
    return jdbc
        .sql("SELECT attribute_bytes FROM spring_session_attributes")
        .query(byte[].class)
        .list()
        .stream()
        .map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
        .reduce("", String::concat);
  }
}
