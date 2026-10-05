package dev.rgonz.catalog.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import org.junit.jupiter.api.Test;

/** Checks the fixed lists every signed-in person with a role can read. */
class ReferenceIT extends ApplicationIT {
  @Test
  void anAuthorReadsVehicleTypesCategoriesAndModelYears() {
    var result = mvc.get().uri("/api/reference").with(signedInAs(Role.AUTHOR));

    assertThat(result).hasStatusOk();
    assertThat(result)
        .bodyJson()
        .extractingPath("$.vehicleTypes[*].code")
        .asArray()
        .containsExactlyInAnyOrder("CAR", "SUV", "TRUCK", "VAN");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.vehicleTypes[?(@.code == 'SUV')].name")
        .asArray()
        .containsExactly("SUV");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.categories[*].code")
        .asArray()
        .containsExactly(
            "POWERTRAIN",
            "CHASSIS",
            "STEERING",
            "BRAKING",
            "WHEELS_TIRES",
            "EXTERIOR",
            "INTERIOR",
            "ELECTRICAL",
            "CLIMATE",
            "SAFETY_ADAS",
            "INFOTAINMENT",
            "THERMAL",
            "PACKAGES");
    assertThat(result).bodyJson().extractingPath("$.categories[0].name").isEqualTo("Powertrain");
    assertThat(result)
        .bodyJson()
        .extractingPath("$.modelYears")
        .asArray()
        .containsExactly(2024, 2025, 2026, 2027, 2028);
  }

  @Test
  void aPersonWithoutARoleCannotReadThem() {
    assertThat(mvc.get().uri("/api/reference").with(oidcLogin())).hasStatus(403);
  }
}
