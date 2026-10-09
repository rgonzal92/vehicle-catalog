package dev.rgonz.catalog.library;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks that admins keep the library's global rules, that everyone with a role can read them, and
 * that a rule holds what every rule must. Each test starts from the seeded library.
 */
class GlobalRulesIT extends ApplicationIT {
  @BeforeEach
  void theSeededLibrary() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void theSeededRulesAreThereForEveryRoleToRead() {
    for (var role : Role.values()) {
      var listed = list(role);

      assertThat(listed).as(role.name()).hasStatusOk();
      assertThat(sentences(listed))
          .as(role.name())
          .contains(
              "PACKAGE_TOW REQUIRES COOLING_HEAVY_DUTY",
              "PACKAGE_TOW INCLUDES TOW_HITCH_RECEIVER,WIRING_TRAILER_7_PIN",
              "SAFETY_TRAILER_SWAY_CONTROL REQUIRES_ONE_OF"
                  + " PACKAGE_TOW,PACKAGE_TOW_HEAVY_DUTY,TOW_HITCH_RECEIVER");
    }
    assertThat(
            ApplicationIT.<List<String>>read(
                list(Role.AUTHOR), "$[?(@.source.code == 'WINDSHIELD_HEATED')].regions[*].name"))
        .as("a rule limited to a region names it")
        .containsExactly("Europe");
  }

  @Test
  void anAdminAddsChangesAndDeletesARule() {
    var added = add(rule("REQUIRES", "ROOF_PANORAMIC", null, "SEAT_LEATHER"));

    assertThat(added).hasStatus(201);
    assertThat(added).bodyJson().extractingPath("$.source.name").isEqualTo("Panoramic Roof");
    assertThat(added).bodyJson().extractingPath("$.allRegions").isEqualTo(true);
    long id = ApplicationIT.<Integer>read(added, "$.id");

    var changed =
        change(
            id,
            rule(
                "REQUIRES",
                "ROOF_PANORAMIC",
                List.of("EU", "NA"),
                "SEAT_LEATHER",
                "AUDIO_PREMIUM"));

    assertThat(changed).hasStatusOk();
    assertThat(ApplicationIT.<List<String>>read(changed, "$.targets[*].code"))
        .containsExactly("AUDIO_PREMIUM", "SEAT_LEATHER");
    assertThat(ApplicationIT.<List<String>>read(changed, "$.regions[*].code"))
        .as("in the library's order")
        .containsExactly("NA", "EU");
    assertThat(sentences(list(Role.AUTHOR)))
        .contains("ROOF_PANORAMIC REQUIRES AUDIO_PREMIUM,SEAT_LEATHER");

    assertThat(delete(Role.ADMIN, id)).hasStatus(204);

    assertThat(sentences(list(Role.AUTHOR))).noneMatch(rule -> rule.startsWith("ROOF_PANORAMIC "));
    assertThat(delete(Role.ADMIN, id)).hasStatus(404);
  }

  @Test
  void onlyAnAdminChangesTheRules() {
    var content = rule("REQUIRES", "ROOF_PANORAMIC", null, "SEAT_LEATHER");
    long seeded = ApplicationIT.<Integer>read(list(Role.ADMIN), "$[0].id");

    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      assertThat(send(role, mvc.post().uri("/api/global-rules"), content)).hasStatus(403);
      assertThat(send(role, mvc.put().uri("/api/global-rules/{id}", seeded), content))
          .hasStatus(403);
      assertThat(delete(role, seeded)).hasStatus(403);
    }
  }

  @Test
  void aRuleIsRefusedForEachThingThatMustHoldOfIt() {
    var refusals =
        List.of(
            new String[] {
              rule("REQUIRES", "ROOF_PANORAMIC", null, "ROOF_PANORAMIC"),
              "A rule's source cannot be one of its targets."
            },
            new String[] {
              rule("REQUIRES", "ROOF_PANORAMIC", null, "SEAT_LEATHER", "SEAT_LEATHER"),
              "Name each target once."
            },
            new String[] {rule("REQUIRES", "ROOF_PANORAMIC", null), "Choose at least one target."},
            new String[] {
              rule("REQUIRES_ONE_OF", "ROOF_PANORAMIC", null, "SEAT_LEATHER"),
              "A Requires one of rule has at least 2 targets."
            },
            new String[] {
              rule("INCLUDES", "ROOF_PANORAMIC", null, "SEAT_LEATHER"),
              "Only a package includes other features."
            },
            new String[] {
              rule("REQUIRES", "ROOF_PANORAMIC", List.of(), "SEAT_LEATHER"),
              "Choose the regions the rule applies in, or every region."
            },
            new String[] {
              rule("REQUIRES", "ROOF_PANORAMIC", List.of("ZZ"), "SEAT_LEATHER"),
              "Choose active regions from the library."
            },
            new String[] {
              rule("REQUIRES", "PACKAGE_TOW", null, "COOLING_HEAVY_DUTY"),
              "This rule already exists."
            });

    for (var refusal : refusals) {
      var refused = add(refusal[0]);

      assertThat(refused).as(refusal[1]).hasStatus(422);
      assertThat(refused).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION");
      assertThat(refused).bodyJson().extractingPath("$.detail").isEqualTo(refusal[1]);
    }
  }

  @Test
  void aRuleCannotNameARetiredFeatureOrAnInactiveRegion() {
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'SEAT_LEATHER'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'EU'").update();

    assertThat(add(rule("REQUIRES", "ROOF_PANORAMIC", null, "SEAT_LEATHER")))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Leather Seats is retired, and a rule cannot name it.");
    assertThat(add(rule("REQUIRES", "ROOF_PANORAMIC", List.of("EU"), "AUDIO_PREMIUM")))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Choose active regions from the library.");
  }

  @Test
  void aRuleHasAtMostTwentyTargets() {
    var features =
        jdbc.sql("SELECT id FROM feature WHERE code <> 'ROOF_PANORAMIC' ORDER BY code LIMIT 21")
            .query(Long.class)
            .list();
    var twentyOne = content("REQUIRES", feature("ROOF_PANORAMIC"), null, features);
    var twenty = content("REQUIRES", feature("ROOF_PANORAMIC"), null, features.subList(0, 20));

    assertThat(add(twentyOne))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(add(twenty)).hasStatus(201);
  }

  @Test
  void theSameFeaturesMakeAnotherRuleWhenTheKindOrTheRegionsDiffer() {
    assertThat(add(rule("REQUIRES", "PACKAGE_TOW", List.of("EU"), "COOLING_HEAVY_DUTY")))
        .hasStatus(201);
    assertThat(add(rule("INCLUDES", "PACKAGE_TOW", null, "COOLING_HEAVY_DUTY"))).hasStatus(201);
  }

  @Test
  void theKindOfARuleCannotBeChanged() {
    long id =
        ApplicationIT.<Integer>read(
            add(rule("REQUIRES", "PACKAGE_TOW", null, "SEAT_LEATHER")), "$.id");

    var changed = change(id, rule("INCLUDES", "PACKAGE_TOW", null, "SEAT_LEATHER"));

    assertThat(changed).hasStatus(422);
    assertThat(changed)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("A rule's kind cannot be changed. Delete the rule and add another.");
  }

  @Test
  void aFeatureThatARuleNamesCannotBeRetiredUntilTheRuleIsGone() {
    var asSource = retire("PACKAGE_PREMIUM_AUDIO");
    var asTarget = retire("COOLING_HEAVY_DUTY");

    assertThat(asSource).hasStatus(409);
    assertThat(asSource).bodyJson().extractingPath("$.code").isEqualTo("IN_USE");
    assertThat(ApplicationIT.<List<String>>read(asSource, "$.usedBy"))
        .containsExactly("Premium Audio Package includes Premium Audio");
    assertThat(asTarget).hasStatus(409);
    assertThat(ApplicationIT.<List<String>>read(asTarget, "$.usedBy"))
        .containsExactlyInAnyOrder(
            "Tow Package requires Heavy-Duty Cooling",
            "Heavy-Duty Tow Package requires Heavy-Duty Cooling");
    assertThat(asTarget)
        .bodyJson()
        .extractingPath("$.detail")
        .isEqualTo("Heavy-Duty Cooling is named by 2 global rules. Change or delete them first.");

    long rule =
        ApplicationIT.<List<Integer>>read(
                list(Role.ADMIN), "$[?(@.source.code == 'PACKAGE_PREMIUM_AUDIO')].id")
            .getFirst();
    delete(Role.ADMIN, rule);

    assertThat(retire("PACKAGE_PREMIUM_AUDIO")).hasStatusOk();
  }

  /** Each listed rule as "SOURCE KIND TARGET,TARGET", by the features' codes. */
  private static List<String> sentences(MvcTestResult listed) {
    List<String> kinds = read(listed, "$[*].kind");
    List<String> sources = read(listed, "$[*].source.code");
    List<List<String>> targets =
        LongStream.range(0, kinds.size())
            .mapToObj(
                index ->
                    ApplicationIT.<List<String>>read(
                        listed, "$[%d].targets[*].code".formatted(index)))
            .toList();

    return LongStream.range(0, kinds.size())
        .mapToObj(
            index ->
                "%s %s %s"
                    .formatted(
                        sources.get((int) index),
                        kinds.get((int) index),
                        String.join(",", targets.get((int) index))))
        .toList();
  }

  /** A rule as an admin sends it, with its features named by their codes. */
  private String rule(String kind, String source, List<String> regions, String... targets) {
    return content(kind, feature(source), regions, Stream.of(targets).map(this::feature).toList());
  }

  private static String content(
      String kind, long source, List<String> regions, List<Long> targets) {
    return """
        {"kind": "%s", "sourceFeatureId": %d, "targetFeatureIds": %s, "allRegions": %s,
         "regionCodes": %s}
        """
        .formatted(
            kind,
            source,
            targets,
            regions == null,
            regions == null
                ? "[]"
                : regions.stream()
                    .map("\"%s\""::formatted)
                    .collect(Collectors.joining(",", "[", "]")));
  }

  private long feature(String code) {
    return jdbc.sql("SELECT id FROM feature WHERE code = :code")
        .param("code", code)
        .query(Long.class)
        .single();
  }

  private MvcTestResult list(Role as) {
    return mvc.get().uri("/api/global-rules").with(signedInAs(as)).exchange();
  }

  private MvcTestResult add(String content) {
    return send(Role.ADMIN, mvc.post().uri("/api/global-rules"), content);
  }

  private MvcTestResult change(long id, String content) {
    return send(Role.ADMIN, mvc.put().uri("/api/global-rules/{id}", id), content);
  }

  private MvcTestResult send(
      Role as,
      org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder request,
      String content) {
    return request
        .with(signedInAs(as))
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(content)
        .exchange();
  }

  private MvcTestResult delete(Role as, long id) {
    return mvc.delete()
        .uri("/api/global-rules/{id}", id)
        .with(signedInAs(as))
        .with(csrfToken())
        .exchange();
  }

  private MvcTestResult retire(String featureCode) {
    return mvc.post()
        .uri("/api/features/{id}/retire", feature(featureCode))
        .with(signedInAs(Role.ADMIN))
        .with(csrfToken())
        .exchange();
  }
}
