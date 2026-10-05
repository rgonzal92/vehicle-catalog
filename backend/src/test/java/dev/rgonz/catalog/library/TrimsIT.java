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

/** Checks that admins maintain the library's trims and everyone with a role can list them. */
class TrimsIT extends ApplicationIT {
  @BeforeEach
  void noTrims() {
    jdbc.sql("DELETE FROM trim").update();
  }

  @Test
  void anAdminAddsTrimsAndEveryRoleListsThemInOrder() {
    assertThat(add(Role.ADMIN, "Base")).hasStatus(201);
    add(Role.ADMIN, "Sport");
    add(Role.ADMIN, "Luxury");

    for (var role : Role.values()) {
      assertThat(names(role)).as(role.name()).containsExactly("Base", "Sport", "Luxury");
    }
    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].active").isEqualTo(true);
    assertThat(list(Role.AUTHOR))
        .bodyJson()
        .extractingPath("$[*].sortOrder")
        .asArray()
        .containsExactly(1, 2, 3);
  }

  @Test
  void anAdminRenamesDeactivatesAndReactivatesATrim() {
    var id = idOf(add(Role.ADMIN, "Sport"));

    assertThat(change(Role.ADMIN, id, "Sport Plus", 1, false)).hasStatusOk();

    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].name").isEqualTo("Sport Plus");
    assertThat(list(Role.AUTHOR)).bodyJson().extractingPath("$[0].active").isEqualTo(false);
    assertThat(change(Role.ADMIN, id, "Sport Plus", 1, true))
        .bodyJson()
        .extractingPath("$.active")
        .isEqualTo(true);
  }

  @Test
  void anAdminMovesATrimAndTheOthersCloseUp() {
    add(Role.ADMIN, "Base");
    add(Role.ADMIN, "Sport");
    var luxury = idOf(add(Role.ADMIN, "Luxury"));

    change(Role.ADMIN, luxury, "Luxury", 1, true);

    assertThat(names(Role.AUTHOR)).containsExactly("Luxury", "Base", "Sport");
    assertThat(list(Role.AUTHOR))
        .bodyJson()
        .extractingPath("$[*].sortOrder")
        .asArray()
        .containsExactly(1, 2, 3);

    change(Role.ADMIN, luxury, "Luxury", 99, true);

    assertThat(names(Role.AUTHOR)).containsExactly("Base", "Sport", "Luxury");
  }

  @Test
  void aNameAlreadyInUseIsRefusedWhateverItsCase() {
    add(Role.ADMIN, "Sport");
    var base = idOf(add(Role.ADMIN, "Base"));

    for (var refused :
        List.of(add(Role.ADMIN, "sport"), change(Role.ADMIN, base, "SPORT", 2, true))) {
      assertThat(refused)
          .hasStatus(409)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NAME_TAKEN");
    }
  }

  @Test
  void aNameThatIsEmptyOrTooLongIsRefused() {
    var id = idOf(add(Role.ADMIN, "Base"));

    for (var refused :
        List.of(
            add(Role.ADMIN, ""),
            add(Role.ADMIN, "T".repeat(41)),
            change(Role.ADMIN, id, " ", 1, true),
            change(Role.ADMIN, id, "T".repeat(41), 1, true))) {
      assertThat(refused)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
    }
    assertThat(add(Role.ADMIN, "T".repeat(40))).hasStatus(201);
  }

  @Test
  void anAuthorAndAManagerCannotAddOrChangeATrim() {
    var id = idOf(add(Role.ADMIN, "Base"));

    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      assertThat(add(role, "Sport")).as(role.name()).hasStatus(403);
      assertThat(change(role, id, "Basic", 1, true)).as(role.name()).hasStatus(403);
    }
    assertThat(names(Role.AUTHOR)).containsExactly("Base");
  }

  @Test
  void aTrimCannotBeDeletedAndAnUnknownOneIsNotFound() {
    var id = idOf(add(Role.ADMIN, "Base"));

    assertThat(mvc.delete().uri("/api/trims/{id}", id).with(signedInAs(Role.ADMIN)).with(csrf()))
        .hasStatus(405);
    assertThat(change(Role.ADMIN, 987654321, "Ghost", 1, true)).hasStatus(404);
    assertThat(names(Role.ADMIN)).containsExactly("Base");
  }

  private MvcTestResult add(Role as, String name) {
    return mvc.post()
        .uri("/api/trims")
        .with(signedInAs(as))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\": \"%s\"}".formatted(name))
        .exchange();
  }

  private MvcTestResult change(Role as, long id, String name, int sortOrder, boolean active) {
    return mvc.put()
        .uri("/api/trims/{id}", id)
        .with(signedInAs(as))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"name\": \"%s\", \"sortOrder\": %d, \"active\": %s}"
                .formatted(name, sortOrder, active))
        .exchange();
  }

  private MvcTestResult list(Role as) {
    return mvc.get().uri("/api/trims").with(signedInAs(as)).exchange();
  }

  private List<String> names(Role as) {
    return JsonPath.parse(contentOf(list(as))).read("$[*].name");
  }

  private static long idOf(MvcTestResult added) {
    return JsonPath.parse(contentOf(added)).read("$.id", Long.class);
  }

  private static String contentOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
