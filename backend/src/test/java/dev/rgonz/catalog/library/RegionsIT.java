package dev.rgonz.catalog.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.io.UnsupportedEncodingException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Checks that admins maintain the library's regions and everyone with a role can list them. */
class RegionsIT extends ApplicationIT {
  @BeforeEach
  void noRegions() {
    jdbc.sql("DELETE FROM region").update();
  }

  @Test
  void anAdminAddsRegionsAndEveryRoleListsThemInOrder() {
    assertThat(add(Role.ADMIN, "NA", "North America")).hasStatus(201);
    add(Role.ADMIN, "EU", "Europe");

    for (var role : Role.values()) {
      assertThat(names(role)).as(role.name()).containsExactly("North America", "Europe");
    }
    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].code").isEqualTo("NA");
    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].active").isEqualTo(true);
  }

  @Test
  void anAdminRenamesMovesDeactivatesAndReactivatesARegionButItsCodeStays() {
    add(Role.ADMIN, "NA", "North America");
    add(Role.ADMIN, "EU", "Europe");

    assertThat(change(Role.ADMIN, "EU", "European Union", 1, false)).hasStatusOk();

    assertThat(names(Role.AUTHOR)).containsExactly("European Union", "North America");
    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].code").isEqualTo("EU");
    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].active").isEqualTo(false);
    assertThat(list(Role.AUTHOR))
        .bodyJson()
        .extractingPath("$[*].sortOrder")
        .asArray()
        .containsExactly(1, 2);
    assertThat(change(Role.ADMIN, "EU", "European Union", 1, true))
        .bodyJson()
        .extractingPath("$.active")
        .isEqualTo(true);
  }

  @Test
  void aCodeOrNameAlreadyInUseIsRefused() {
    add(Role.ADMIN, "NA", "North America");
    add(Role.ADMIN, "EU", "Europe");

    for (var refused :
        List.of(
            add(Role.ADMIN, "NA", "Northern America"),
            add(Role.ADMIN, "EUR", "Europe"),
            change(Role.ADMIN, "EU", "North America", 2, true))) {
      assertThat(refused)
          .hasStatus(409)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NAME_TAKEN");
    }
    assertThat(names(Role.AUTHOR)).containsExactly("North America", "Europe");
  }

  @Test
  void aCodeOrNameThatBreaksARuleIsRefused() {
    add(Role.ADMIN, "NA", "North America");

    for (var refused :
        List.of(
            add(Role.ADMIN, "europe", "Europe"),
            add(Role.ADMIN, "EU", ""),
            add(Role.ADMIN, "EU", "E".repeat(81)),
            change(Role.ADMIN, "NA", " ", 1, true))) {
      assertThat(refused)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
    }
  }

  @Test
  void anAuthorAndAManagerCannotAddOrChangeARegion() {
    add(Role.ADMIN, "NA", "North America");

    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      assertThat(add(role, "EU", "Europe")).as(role.name()).hasStatus(403);
      assertThat(change(role, "NA", "America", 1, true)).as(role.name()).hasStatus(403);
    }
    assertThat(names(Role.AUTHOR)).containsExactly("North America");
  }

  @Test
  void aRegionCannotBeDeletedAndAnUnknownOneIsNotFound() {
    add(Role.ADMIN, "NA", "North America");

    assertThat(mvc.delete().uri("/api/regions/NA").with(signedInAs(Role.ADMIN)).with(csrf()))
        .hasStatus(405);
    assertThat(change(Role.ADMIN, "XX", "Nowhere", 1, true)).hasStatus(404);
    assertThat(names(Role.ADMIN)).containsExactly("North America");
  }

  private MvcTestResult add(Role as, String code, String name) {
    return mvc.post()
        .uri("/api/regions")
        .with(signedInAs(as))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\": \"%s\", \"name\": \"%s\"}".formatted(code, name))
        .exchange();
  }

  private MvcTestResult change(Role as, String code, String name, int sortOrder, boolean active) {
    return mvc.put()
        .uri("/api/regions/{code}", code)
        .with(signedInAs(as))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"name\": \"%s\", \"sortOrder\": %d, \"active\": %s}"
                .formatted(name, sortOrder, active))
        .exchange();
  }

  private MvcTestResult list(Role as) {
    return mvc.get().uri("/api/regions").with(signedInAs(as)).exchange();
  }

  private List<String> names(Role as) {
    try {
      return JsonPath.parse(list(as).getResponse().getContentAsString()).read("$[*].name");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
