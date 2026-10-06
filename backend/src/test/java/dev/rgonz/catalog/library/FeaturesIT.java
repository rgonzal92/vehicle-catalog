package dev.rgonz.catalog.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Checks that admins maintain the feature library and everyone with a role can search it. */
class FeaturesIT extends ApplicationIT {
  @BeforeEach
  void noFeatures() {
    jdbc.sql("DELETE FROM feature").update();
  }

  @Test
  void anAdminAddsAFeatureAndAPackageAndEveryRoleFindsThem() {
    var wheel =
        add(Role.ADMIN, "WHEEL_20_BLACK", "20-inch black wheels", "WHEELS_TIRES", "FEATURE");
    var tow = add(Role.ADMIN, "TOW_PACKAGE", "Tow Package", "PACKAGES", "PACKAGE");

    assertThat(wheel).hasStatus(201);
    assertThat(wheel).bodyJson().extractingPath("$.code").isEqualTo("WHEEL_20_BLACK");
    assertThat(wheel).bodyJson().extractingPath("$.kind").isEqualTo("FEATURE");
    assertThat(wheel).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    assertThat(wheel).bodyJson().extractingPath("$.description").isEqualTo("About WHEEL_20_BLACK");
    assertThat(tow).hasStatus(201);
    assertThat(tow).bodyJson().extractingPath("$.kind").isEqualTo("PACKAGE");

    for (var role : Role.values()) {
      var found = search(role, "");
      assertThat(found).as(role.name()).hasStatusOk();
      assertThat(codes(found)).containsExactly("TOW_PACKAGE", "WHEEL_20_BLACK");
      assertThat(found).bodyJson().extractingPath("$.total").isEqualTo(2);
    }
  }

  @Test
  void anAdminEditsNameDescriptionAndCategoryButCodeAndKindStay() {
    var added = add(Role.ADMIN, "ROOF_PANORAMIC", "Panoramic Roof", "EXTERIOR", "FEATURE");

    var changed =
        mvc.put()
            .uri("/api/features/{id}", idOf(added))
            .with(signedInAs(Role.ADMIN))
            .with(csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"code": "ROOF_GLASS", "kind": "PACKAGE", "name": " Glass Roof ",
                 "description": "A fixed glass roof.", "categoryCode": "INTERIOR", "version": %d}
                """
                    .formatted(versionOf(added)))
            .exchange();

    assertThat(changed).hasStatusOk();
    var found = search(Role.AUTHOR, "");
    assertThat(found).bodyJson().extractingPath("$.items[0].code").isEqualTo("ROOF_PANORAMIC");
    assertThat(found).bodyJson().extractingPath("$.items[0].kind").isEqualTo("FEATURE");
    assertThat(found).bodyJson().extractingPath("$.items[0].name").isEqualTo("Glass Roof");
    assertThat(found)
        .bodyJson()
        .extractingPath("$.items[0].description")
        .isEqualTo("A fixed glass roof.");
    assertThat(found).bodyJson().extractingPath("$.items[0].categoryCode").isEqualTo("INTERIOR");
  }

  @Test
  void anAdminRetiresAndReactivatesAFeature() {
    var id = idOf(add(Role.ADMIN, "SEAT_HEATED", "Heated seats", "INTERIOR", "FEATURE"));

    assertThat(setStatus(Role.ADMIN, id, "retire"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("RETIRED");
    assertThat(search(Role.AUTHOR, "status=RETIRED"))
        .bodyJson()
        .extractingPath("$.total")
        .isEqualTo(1);

    assertThat(setStatus(Role.ADMIN, id, "reactivate"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.status")
        .isEqualTo("ACTIVE");
    assertThat(search(Role.AUTHOR, "status=RETIRED"))
        .bodyJson()
        .extractingPath("$.total")
        .isEqualTo(0);
  }

  @Test
  void aCodeThatBreaksThePatternIsRefused() {
    for (var code : List.of("wheel", "W", "1WHEEL", "WHEEL-20", "W".repeat(41), "")) {
      assertThat(add(Role.ADMIN, code, "Wheels", "WHEELS_TIRES", "FEATURE"))
          .as(code)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
    }
    assertThat(add(Role.ADMIN, "W".repeat(40), "Wheels", "WHEELS_TIRES", "FEATURE")).hasStatus(201);
  }

  @Test
  void aCodeAlreadyInUseIsRefused() {
    add(Role.ADMIN, "WHEEL_20_BLACK", "20-inch black wheels", "WHEELS_TIRES", "FEATURE");

    assertThat(add(Role.ADMIN, "WHEEL_20_BLACK", "Other wheels", "WHEELS_TIRES", "FEATURE"))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NAME_TAKEN");
    assertThat(search(Role.ADMIN, "")).bodyJson().extractingPath("$.total").isEqualTo(1);
  }

  @Test
  void aNameDescriptionOrCategoryThatBreaksARuleIsRefused() {
    var added = add(Role.ADMIN, "SEAT_HEATED", "Heated seats", "INTERIOR", "FEATURE");

    for (var refused :
        List.of(
            add(Role.ADMIN, "SEAT_COOLED", " ", "INTERIOR", "FEATURE"),
            add(Role.ADMIN, "SEAT_COOLED", "S".repeat(81), "INTERIOR", "FEATURE"),
            add(Role.ADMIN, "SEAT_COOLED", "Cooled seats", "FURNITURE", "FEATURE"),
            change(Role.ADMIN, added, "", "Warm.", "INTERIOR"),
            change(Role.ADMIN, added, "Heated seats", "D".repeat(501), "INTERIOR"),
            change(Role.ADMIN, added, "Heated seats", "Warm.", "FURNITURE"))) {
      assertThat(refused)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
      assertThat(refused).bodyJson().extractingPath("$.detail").asString().isNotBlank();
    }
    assertThat(change(Role.ADMIN, added, "S".repeat(80), "D".repeat(500), "INTERIOR"))
        .hasStatusOk();
  }

  @Test
  void aPackageStaysInPackagesAndNoOtherFeatureEntersIt() {
    var tow = add(Role.ADMIN, "TOW_PACKAGE", "Tow Package", "PACKAGES", "PACKAGE");
    var hitch = add(Role.ADMIN, "TOW_HITCH", "Tow hitch", "CHASSIS", "FEATURE");

    for (var refused :
        List.of(
            add(Role.ADMIN, "LUXURY_PACKAGE", "Luxury Package", "INTERIOR", "PACKAGE"),
            add(Role.ADMIN, "FLOOR_MATS", "Floor mats", "PACKAGES", "FEATURE"),
            change(Role.ADMIN, tow, "Tow Package", "", "CHASSIS"),
            change(Role.ADMIN, hitch, "Tow hitch", "", "PACKAGES"))) {
      assertThat(refused)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
    }
    assertThat(categoryOf("TOW_PACKAGE")).isEqualTo("PACKAGES");
    assertThat(categoryOf("TOW_HITCH")).isEqualTo("CHASSIS");
  }

  @Test
  void theDatabaseRefusesAKindThatDisagreesWithTheCategory() {
    add(Role.ADMIN, "TOW_PACKAGE", "Tow Package", "PACKAGES", "PACKAGE");
    add(Role.ADMIN, "TOW_HITCH", "Tow hitch", "CHASSIS", "FEATURE");

    for (var statement :
        List.of(
            "INSERT INTO feature (code, name, category_code, kind)"
                + " VALUES ('LUXURY_PACKAGE', 'Luxury Package', 'INTERIOR', 'PACKAGE')",
            "INSERT INTO feature (code, name, category_code, kind)"
                + " VALUES ('FLOOR_MATS', 'Floor mats', 'PACKAGES', 'FEATURE')",
            "UPDATE feature SET category_code = 'CHASSIS' WHERE code = 'TOW_PACKAGE'",
            "UPDATE feature SET category_code = 'PACKAGES' WHERE code = 'TOW_HITCH'")) {
      assertThatThrownBy(() -> jdbc.sql(statement).update())
          .as(statement)
          .isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void searchFiltersAndPagingWorkTogether() {
    add(Role.ADMIN, "ENGINE_20_TURBO", "2.0L Turbo", "POWERTRAIN", "FEATURE");
    add(Role.ADMIN, "ENGINE_HYBRID", "Hybrid Powertrain", "POWERTRAIN", "FEATURE");
    add(Role.ADMIN, "ROOF_PANORAMIC", "Panoramic Roof", "EXTERIOR", "FEATURE");
    add(Role.ADMIN, "ROOF_REMOVABLE", "Removable roof", "EXTERIOR", "FEATURE");
    add(Role.ADMIN, "TOW_PACKAGE", "Tow Package", "PACKAGES", "PACKAGE");
    var turbo = idOf(search(Role.ADMIN, "query=ENGINE_20"), "$.items[0].id");
    setStatus(Role.ADMIN, turbo, "retire");

    assertThat(codes(search(Role.AUTHOR, "query=roof")))
        .as("by name or code, whatever the case")
        .containsExactly("ROOF_PANORAMIC", "ROOF_REMOVABLE");
    assertThat(codes(search(Role.AUTHOR, "query=powertrain")))
        .as("by name")
        .containsExactly("ENGINE_HYBRID");
    assertThat(codes(search(Role.AUTHOR, "query=E_")))
        .as("an underscore stands for itself")
        .containsExactly("ENGINE_20_TURBO", "ENGINE_HYBRID");
    assertThat(codes(search(Role.AUTHOR, "category=POWERTRAIN")))
        .containsExactly("ENGINE_20_TURBO", "ENGINE_HYBRID");
    assertThat(codes(search(Role.AUTHOR, "kind=PACKAGE"))).containsExactly("TOW_PACKAGE");
    assertThat(codes(search(Role.AUTHOR, "status=RETIRED"))).containsExactly("ENGINE_20_TURBO");
    assertThat(codes(search(Role.AUTHOR, "query=engine&category=POWERTRAIN&status=ACTIVE")))
        .containsExactly("ENGINE_HYBRID");

    var secondPage = search(Role.AUTHOR, "kind=FEATURE&page=1&size=3");
    assertThat(codes(secondPage)).containsExactly("ROOF_REMOVABLE");
    assertThat(secondPage).bodyJson().extractingPath("$.total").isEqualTo(4);
    assertThat(codes(search(Role.AUTHOR, "kind=FEATURE&page=0&size=3")))
        .containsExactly("ENGINE_20_TURBO", "ENGINE_HYBRID", "ROOF_PANORAMIC");
  }

  @Test
  void aSearchThatIsNotUnderstoodIsABadRequest() {
    for (var query : List.of("kind=BOAT", "status=GONE", "page=-1", "size=0", "size=101")) {
      assertThat(search(Role.AUTHOR, query))
          .as(query)
          .hasStatus(400)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("BAD_REQUEST");
    }
  }

  @Test
  void theSecondOfTwoEditsFromTheSameVersionIsRefusedAndOverwritesNothing() {
    var added = add(Role.ADMIN, "SEAT_HEATED", "Heated seats", "INTERIOR", "FEATURE");

    assertThat(change(Role.ADMIN, added, "Heated front seats", "First.", "INTERIOR")).hasStatusOk();
    var second = change(Role.ADMIN, added, "Warm seats", "Second.", "CLIMATE");

    assertThat(second).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("CONFLICT");
    var found = search(Role.ADMIN, "");
    assertThat(found).bodyJson().extractingPath("$.items[0].name").isEqualTo("Heated front seats");
    assertThat(found).bodyJson().extractingPath("$.items[0].description").isEqualTo("First.");
    assertThat(found).bodyJson().extractingPath("$.items[0].categoryCode").isEqualTo("INTERIOR");
  }

  @Test
  void anEditFromTheVersionAnEarlierEditReturnedIsAccepted() {
    var added = add(Role.ADMIN, "SEAT_HEATED", "Heated seats", "INTERIOR", "FEATURE");
    var first = change(Role.ADMIN, added, "Heated front seats", "First.", "INTERIOR");

    assertThat(change(Role.ADMIN, first, "Warm seats", "Second.", "CLIMATE")).hasStatusOk();
  }

  @Test
  void anAuthorAndAManagerCannotWriteButCanSearch() {
    var added = add(Role.ADMIN, "SEAT_HEATED", "Heated seats", "INTERIOR", "FEATURE");

    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      for (var refused :
          List.of(
              add(role, "SEAT_COOLED", "Cooled seats", "INTERIOR", "FEATURE"),
              add(role, "not a code", "", "FURNITURE", "BOAT"),
              change(role, added, "Warm seats", "", "INTERIOR"),
              setStatus(role, idOf(added), "retire"),
              setStatus(role, idOf(added), "reactivate"))) {
        assertThat(refused)
            .as(role.name())
            .hasStatus(403)
            .bodyJson()
            .extractingPath("$.code")
            .isEqualTo("FORBIDDEN");
      }
      assertThat(codes(search(role, "status=ACTIVE"))).containsExactly("SEAT_HEATED");
    }
  }

  @Test
  void noRequestDeletesAFeatureAndAnUnknownOneIsNotFound() {
    var added = add(Role.ADMIN, "SEAT_HEATED", "Heated seats", "INTERIOR", "FEATURE");

    assertThat(
            mvc.delete()
                .uri("/api/features/{id}", idOf(added))
                .with(signedInAs(Role.ADMIN))
                .with(csrfToken()))
        .hasStatus(405);
    assertThat(codes(search(Role.ADMIN, ""))).containsExactly("SEAT_HEATED");

    assertThat(setStatus(Role.ADMIN, 987654321, "retire"))
        .hasStatus(404)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_FOUND");
    assertThat(
            mvc.put()
                .uri("/api/features/987654321")
                .with(signedInAs(Role.ADMIN))
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\": \"Ghost\", \"description\": \"\", \"categoryCode\": \"INTERIOR\","
                        + " \"version\": 0}"))
        .hasStatus(404);
  }

  private MvcTestResult add(Role as, String code, String name, String categoryCode, String kind) {
    return mvc.post()
        .uri("/api/features")
        .with(signedInAs(as))
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"code": "%s", "name": "%s", "description": "About %s", "categoryCode": "%s",
             "kind": "%s"}
            """
                .formatted(code, name, code, categoryCode, kind))
        .exchange();
  }

  /** Edits a feature from the version the given response showed. */
  private MvcTestResult change(
      Role as, MvcTestResult seen, String name, String description, String categoryCode) {
    return mvc.put()
        .uri("/api/features/{id}", idOf(seen))
        .with(signedInAs(as))
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"name": "%s", "description": "%s", "categoryCode": "%s", "version": %d}
            """
                .formatted(name, description, categoryCode, versionOf(seen)))
        .exchange();
  }

  private MvcTestResult setStatus(Role as, long id, String action) {
    return mvc.post()
        .uri("/api/features/{id}/{action}", id, action)
        .with(signedInAs(as))
        .with(csrfToken())
        .exchange();
  }

  private MvcTestResult search(Role as, String query) {
    return mvc.get().uri("/api/features?" + query).with(signedInAs(as)).exchange();
  }

  private static List<String> codes(MvcTestResult found) {
    return read(found, "$.items[*].code");
  }

  private String categoryOf(String code) {
    return jdbc.sql("SELECT category_code FROM feature WHERE code = :code")
        .param("code", code)
        .query(String.class)
        .single();
  }

  private static long idOf(MvcTestResult feature) {
    return idOf(feature, "$.id");
  }

  private static long idOf(MvcTestResult result, String path) {
    return ApplicationIT.<Number>read(result, path).longValue();
  }

  private static long versionOf(MvcTestResult feature) {
    return ApplicationIT.<Number>read(feature, "$.version").longValue();
  }
}
