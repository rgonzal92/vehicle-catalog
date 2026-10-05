package dev.rgonz.catalog.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import dev.rgonz.catalog.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Checks the local record of people who signed in and what the app says about them. */
class UserIT extends ApplicationIT {
  @Autowired AppUsers users;

  @Test
  void firstLoginCreatesTheRowAndLaterLoginsUpdateIt() {
    var first = users.recordLogin("sub-returning", "rita", "rita@example.test", "Rita");
    var firstLogin = lastLogin(first.id());

    var second = users.recordLogin("sub-returning", "rita", "rita@new.example.test", "Rita R.");

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.email()).isEqualTo("rita@new.example.test");
    assertThat(second.displayName()).isEqualTo("Rita R.");
    assertThat(lastLogin(first.id())).isAfter(firstLogin);
    assertThat(
            jdbc.sql("SELECT count(*) FROM app_user WHERE cognito_sub = 'sub-returning'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void meDescribesTheSignedInPerson() {
    var person = users.recordLogin("sub-me", "maya", "maya@example.test", "Maya");

    var result =
        mvc.get().uri("/api/me").with(oidcLogin().idToken(token -> token.subject("sub-me")));

    assertThat(result).hasStatusOk().bodyJson().extractingPath("$.id").isEqualTo((int) person.id());
    assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("Maya");
    assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("maya@example.test");
    assertThat(result).bodyJson().extractingPath("$.roles").asArray().isEmpty();
  }

  @Test
  void demoAccountsAreListedWithoutASession() {
    var result = mvc.get().uri("/api/demo-accounts");

    assertThat(result).hasStatusOk().bodyJson().extractingPath("$[0].role").isEqualTo("author");
    assertThat(result).bodyJson().extractingPath("$[0].username").isEqualTo("demo-author");
    assertThat(result).bodyJson().extractingPath("$[0].password").isEqualTo("demo-password");
  }

  private java.time.Instant lastLogin(long id) {
    return jdbc.sql("SELECT last_login_at FROM app_user WHERE id = ?")
        .param(id)
        .query(java.sql.Timestamp.class)
        .single()
        .toInstant();
  }
}
