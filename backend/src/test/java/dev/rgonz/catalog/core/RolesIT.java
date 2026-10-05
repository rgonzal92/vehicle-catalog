package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import dev.rgonz.catalog.ApplicationIT;
import org.junit.jupiter.api.Test;

/** Checks that holding a role, any role, opens what is open to authors. */
class RolesIT extends ApplicationIT {
  private static final String OPEN_TO_AUTHORS = "/api/reference";

  @Test
  void everyRoleReachesWhatIsOpenToAuthors() {
    for (var role : Role.values()) {
      assertThat(mvc.get().uri(OPEN_TO_AUTHORS).with(signedInAs(role)))
          .as(role.name())
          .hasStatusOk();
    }
  }

  @Test
  void aPersonWithoutARoleIsRefusedWhatIsOpenToAuthors() {
    assertThat(mvc.get().uri(OPEN_TO_AUTHORS).with(oidcLogin()))
        .hasStatus(403)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("FORBIDDEN");
  }
}
