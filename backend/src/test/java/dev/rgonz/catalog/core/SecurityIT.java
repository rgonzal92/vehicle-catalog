package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import dev.rgonz.catalog.ApplicationIT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Checks what the API answers to visitors with and without a session. */
class SecurityIT extends ApplicationIT {
  @Test
  void healthAnswersWithoutASession() {
    assertThat(mvc.get().uri("/api/health"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void apiRefusesAVisitorWithoutASession() {
    assertThat(mvc.get().uri("/api/me"))
        .hasStatus(401)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void unknownPathIsAProblemWithACode() {
    assertThat(mvc.get().uri("/api/no-such-thing").with(signedInAs(Role.AUTHOR)))
        .hasStatus(404)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void signInStartsAtTheProviderWithACallbackOnThePublicUrl() {
    var result = mvc.get().uri("/api/oauth2/authorization/cognito").header("Host", "evil.example");

    assertThat(result).hasStatus(302);
    var location = result.exchange().getResponse().getHeader("Location");
    assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8))
        .startsWith(loginServerUrl() + "/authorize?")
        .contains("client_id=" + CLIENT_ID)
        .contains("redirect_uri=" + PUBLIC_URL + "/api/login/oauth2/code/cognito")
        .doesNotContain("evil.example");
  }

  @Test
  void stateChangingRequestWithoutACsrfTokenIsRefused() {
    assertThat(mvc.post().uri("/api/logout").with(oidcLogin()))
        .hasStatus(403)
        .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void signOutPointsAtTheProviderLogout() {
    var result = mvc.post().uri("/api/logout").with(oidcLogin()).with(csrfToken());

    assertThat(result)
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.logoutUrl")
        .isEqualTo(
            "https://login.example.test/logout?client_id="
                + CLIENT_ID
                + "&logout_uri="
                + PUBLIC_URL
                + "/");
  }
}
