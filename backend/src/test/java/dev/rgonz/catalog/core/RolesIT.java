package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import dev.rgonz.catalog.ApplicationIT;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Checks that a person's role decides which endpoints answer them. */
class RolesIT extends ApplicationIT {
  private static final String ADMIN_ONLY = "/api/admin/check";

  @Test
  void adminOnlyEndpointAnswersAnAdmin() {
    assertThat(mvc.get().uri(ADMIN_ONLY).with(signedInAs("ADMIN"))).hasStatus2xxSuccessful();
  }

  @Test
  void adminOnlyEndpointRefusesAnAuthorAndAManager() {
    for (var role : new String[] {"AUTHOR", "MANAGER"}) {
      assertThat(mvc.get().uri(ADMIN_ONLY).with(signedInAs(role)))
          .as(role)
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("FORBIDDEN");
    }
  }

  @Test
  void adminOnlyEndpointNeedsASession() {
    assertThat(mvc.get().uri(ADMIN_ONLY)).hasStatus(401);
  }

  @Test
  void everyRoleReachesWhatIsOpenToAuthors() {
    for (var role : new String[] {"AUTHOR", "MANAGER", "ADMIN"}) {
      assertThat(mvc.get().uri("/api/nothing-here").with(signedInAs(role)))
          .as("%s is let through to a path that does not exist", role)
          .hasStatus(404);
    }
  }

  @Test
  void aPersonWithoutARoleIsRefusedEverythingElse() {
    for (var path : new String[] {"/api/nothing-here", ADMIN_ONLY}) {
      assertThat(mvc.get().uri(path).with(oidcLogin()))
          .as(path)
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("FORBIDDEN");
    }
  }

  private static RequestPostProcessor signedInAs(String role) {
    return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
