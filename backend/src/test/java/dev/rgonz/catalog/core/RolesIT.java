package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import dev.rgonz.catalog.ApplicationIT;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks that holding a role, any role, opens what is open to authors. */
class RolesIT extends ApplicationIT {
  private static final List<String> OPEN_TO_AUTHORS =
      List.of("/api/reference", "/api/vehicle-lines");

  @Test
  void everyRoleReachesWhatIsOpenToAuthors() {
    for (var role : Role.values()) {
      for (var path : OPEN_TO_AUTHORS) {
        assertThat(mvc.get().uri(path).with(signedInAs(role)))
            .as("%s reads %s", role, path)
            .hasStatusOk();
      }
    }
  }

  @Test
  void aPersonWithoutARoleIsRefusedEverythingElse() {
    for (var path : OPEN_TO_AUTHORS) {
      assertThat(mvc.get().uri(path).with(oidcLogin()))
          .as(path)
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("FORBIDDEN");
    }
  }
}
