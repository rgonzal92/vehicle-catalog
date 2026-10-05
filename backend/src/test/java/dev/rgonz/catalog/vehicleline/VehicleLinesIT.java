package dev.rgonz.catalog.vehicleline;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Checks that admins maintain vehicle lines and everyone with a role can list them. */
class VehicleLinesIT extends ApplicationIT {
  @BeforeEach
  void noVehicleLines() {
    jdbc.sql("DELETE FROM vehicle_line").update();
  }

  @Test
  void anAdminAddsAVehicleLineAndEveryRoleCanListIt() {
    var added = add(Role.ADMIN, "COMPACT_SUV", "Compact SUV", "SUV");

    assertThat(added).hasStatus(201);
    assertThat(added).bodyJson().extractingPath("$.code").isEqualTo("COMPACT_SUV");
    assertThat(added).bodyJson().extractingPath("$.active").isEqualTo(true);

    for (var role : Role.values()) {
      var listed = mvc.get().uri("/api/vehicle-lines").with(signedInAs(role));
      assertThat(listed).as(role.name()).hasStatusOk();
      assertThat(listed)
          .bodyJson()
          .extractingPath("$[*].name")
          .asArray()
          .containsExactly("Compact SUV");
      assertThat(listed).bodyJson().extractingPath("$[0].vehicleTypeCode").isEqualTo("SUV");
    }
  }

  @Test
  void anAuthorAndAManagerCannotAddOne() {
    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      assertThat(add(role, "SEDAN", "Sedan", "CAR"))
          .as(role.name())
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("FORBIDDEN");
    }
    assertThat(mvc.get().uri("/api/vehicle-lines").with(signedInAs(Role.AUTHOR)))
        .bodyJson()
        .extractingPath("$")
        .asArray()
        .isEmpty();
  }

  @Test
  void aVisitorWithoutASessionCannotAddOne() {
    var added =
        mvc.post()
            .uri("/api/vehicle-lines")
            .with(csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\": \"SEDAN\", \"name\": \"Sedan\", \"vehicleTypeCode\": \"CAR\"}");

    assertThat(added).hasStatus(401);
  }

  @Test
  void anAdminRenamesRetypesDeactivatesAndReactivatesAVehicleLine() {
    var id = idOf(add(Role.ADMIN, "COMPACT_SUV", "Compact SUV", "SUV"));

    assertThat(change(Role.ADMIN, id, "Small SUV", "CAR", false)).hasStatusOk();

    var listed = mvc.get().uri("/api/vehicle-lines").with(signedInAs(Role.AUTHOR));
    assertThat(listed).bodyJson().extractingPath("$[0].code").isEqualTo("COMPACT_SUV");
    assertThat(listed).bodyJson().extractingPath("$[0].name").isEqualTo("Small SUV");
    assertThat(listed).bodyJson().extractingPath("$[0].vehicleTypeCode").isEqualTo("CAR");
    assertThat(listed).bodyJson().extractingPath("$[0].active").isEqualTo(false);

    assertThat(change(Role.ADMIN, id, "Small SUV", "CAR", true))
        .bodyJson()
        .extractingPath("$.active")
        .isEqualTo(true);
  }

  @Test
  void anAuthorAndAManagerCannotChangeOne() {
    var id = idOf(add(Role.ADMIN, "SEDAN", "Sedan", "CAR"));

    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      assertThat(change(role, id, "Saloon", "CAR", true)).as(role.name()).hasStatus(403);
    }
  }

  @Test
  void aPersonWhoIsNotAnAdminIsRefusedWhateverTheySend() {
    var invalid = add(Role.AUTHOR, "not a code", "", "BOAT");
    var unreadable =
        mvc.post()
            .uri("/api/vehicle-lines")
            .with(signedInAs(Role.MANAGER))
            .with(csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ this is not JSON");

    assertThat(invalid).hasStatus(403);
    assertThat(unreadable).hasStatus(403);
  }

  @Test
  void changingAVehicleLineThatDoesNotExistIsNotFound() {
    assertThat(change(Role.ADMIN, 987654321, "Ghost", "CAR", true))
        .hasStatus(404)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void aCodeOrNameAlreadyInUseIsRefused() {
    var sedan = idOf(add(Role.ADMIN, "SEDAN", "Sedan", "CAR"));
    add(Role.ADMIN, "COUPE", "Sports Coupe", "CAR");

    for (var refused :
        List.of(
            add(Role.ADMIN, "SEDAN", "Another Sedan", "CAR"),
            add(Role.ADMIN, "SEDAN_2", "Sedan", "CAR"),
            change(Role.ADMIN, sedan, "Sports Coupe", "CAR", true))) {
      assertThat(refused)
          .hasStatus(409)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NAME_TAKEN");
    }
  }

  @Test
  void inputThatBreaksARuleIsRefusedWithTheReason() {
    var id = idOf(add(Role.ADMIN, "SEDAN", "Sedan", "CAR"));

    for (var refused :
        List.of(
            add(Role.ADMIN, "sedan two", "Sedan Two", "CAR"),
            add(Role.ADMIN, "VAN_1", "", "VAN"),
            add(Role.ADMIN, "VAN_1", "V".repeat(81), "VAN"),
            add(Role.ADMIN, "VAN_1", "Van", "BOAT"),
            change(Role.ADMIN, id, " ", "CAR", true),
            change(Role.ADMIN, id, "Sedan", "BOAT", true))) {
      assertThat(refused)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
      assertThat(refused).bodyJson().extractingPath("$.detail").asString().isNotBlank();
    }
    assertThat(add(Role.ADMIN, "VAN_1", "", "VAN"))
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Enter a name.");
  }

  @Test
  void aVehicleLineCannotBeDeleted() {
    var id = idOf(add(Role.ADMIN, "SEDAN", "Sedan", "CAR"));

    var deleted =
        mvc.delete()
            .uri("/api/vehicle-lines/{id}", id)
            .with(signedInAs(Role.ADMIN))
            .with(csrfToken());

    assertThat(deleted).hasStatus(405);
    assertThat(mvc.get().uri("/api/vehicle-lines").with(signedInAs(Role.ADMIN)))
        .bodyJson()
        .extractingPath("$[*].code")
        .asArray()
        .containsExactly("SEDAN");
  }

  private MvcTestResult change(
      Role as, long id, String name, String vehicleTypeCode, boolean active) {
    return mvc.put()
        .uri("/api/vehicle-lines/{id}", id)
        .with(signedInAs(as))
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"name": "%s", "vehicleTypeCode": "%s", "active": %s}
            """
                .formatted(name, vehicleTypeCode, active))
        .exchange();
  }

  private static long idOf(MvcTestResult added) {
    return JsonPath.parse(contentOf(added)).read("$.id", Long.class);
  }

  private static String contentOf(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private MvcTestResult add(Role as, String code, String name, String vehicleTypeCode) {
    return mvc.post()
        .uri("/api/vehicle-lines")
        .with(signedInAs(as))
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"code": "%s", "name": "%s", "vehicleTypeCode": "%s"}
            """
                .formatted(code, name, vehicleTypeCode))
        .exchange();
  }
}
