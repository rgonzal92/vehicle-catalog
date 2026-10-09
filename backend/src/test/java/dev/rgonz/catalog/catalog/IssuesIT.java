package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks that a catalog is read with its issues, that every edit answers with them, and that they
 * follow the library as it is today. Each test starts from the seeded catalogs, a working copy of
 * Compact SUV 2026 that Ana owns, and one of Sports Coupe 2026 with nothing in it.
 */
class IssuesIT extends WorkingCopyTests {
  private long copy;
  private long empty;

  @BeforeEach
  void anasWorkingCopies() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    empty = workingCopy(ana(), "SPORTS_COUPE", 2026);
  }

  private static List<String> codes(MvcTestResult answer) {
    return ApplicationIT.read(answer, "$.issues[*].code");
  }

  private static List<String> errors(MvcTestResult answer) {
    return ApplicationIT.read(answer, "$.issues[?(@.severity == 'ERROR')].code");
  }

  @Test
  void anEmptyWorkingCopyIsReadWithWhatItLacks() {
    var opened = open(ana(), empty);

    assertThat(codes(opened)).containsExactly("NO_TRIMS", "NO_REGIONS", "NO_FEATURES");
    assertThat(ApplicationIT.<List<String>>read(opened, "$.issues[*].message"))
        .first()
        .isEqualTo("The catalog has no trims.");
  }

  @Test
  void noSeededApprovedVersionHasAnError() {
    var approved =
        jdbc.sql("SELECT id FROM catalog WHERE status = 'APPROVED' ORDER BY id")
            .query(Long.class)
            .list();

    assertThat(approved).isNotEmpty();
    assertThat(approved).allSatisfy(id -> assertThat(errors(open(ana(), id))).isEmpty());
  }

  @Test
  void anEditAnswersWithEveryIssueTheCatalogThenHas() {
    var added =
        edit(
            ana(),
            mvc.post().uri("/api/catalogs/{id}/trims", empty),
            "\"0\"",
            "{\"trimIds\": [%d]}".formatted(trim("Sport")));

    assertThat(added).hasStatusOk();
    assertThat(codes(added)).containsExactly("NO_REGIONS", "NO_FEATURES", "TRIM_NOT_SOLD");
    assertThat(
            ApplicationIT.<List<Integer>>read(
                added, "$.issues[?(@.code == 'TRIM_NOT_SOLD')].trimId"))
        .containsExactly((int) trim("Sport"));
  }

  @Test
  void anOfferingWhoseLastCellIsSetToNotOfferedIsEmpty() {
    var offered =
        jdbc.sql(
                """
                SELECT f.code
                FROM catalog_cell c
                JOIN feature f ON f.id = c.feature_id
                WHERE c.catalog_id = :copy AND c.trim_id = :trim AND c.region_code = 'NA'
                """)
            .param("copy", copy)
            .param("trim", trim("Base"))
            .query(String.class)
            .list();
    var cleared =
        offered.stream().map(code -> cell(code, "Base", "NA", "N")).toArray(String[]::new);

    var saved = setCells(ana(), copy, "\"0\"", cleared);

    assertThat(saved).hasStatusOk();
    assertThat(errors(saved)).containsExactly("OFFERING_EMPTY");
    assertThat(
            ApplicationIT.<List<String>>read(
                saved, "$.issues[?(@.code == 'OFFERING_EMPTY')].message"))
        .containsExactly("Base in North America has no Standard or Available feature.");

    var restored = setCells(ana(), copy, "\"1\"", cell(offered.getFirst(), "Base", "NA", "A"));

    assertThat(errors(restored)).isEmpty();
  }

  @Test
  void whatTheLibraryRetiresOrDeactivatesStaysAndIsAnErrorUntilItIsBack() {
    assertThat(errors(open(ana(), copy))).isEmpty();
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'TRANS_MANUAL'").update();
    jdbc.sql("UPDATE trim SET active = false WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'EU'").update();

    var opened = open(ana(), copy);

    assertThat(errors(opened))
        .containsExactlyInAnyOrder("FEATURE_RETIRED", "TRIM_INACTIVE", "REGION_INACTIVE");
    assertThat(
            ApplicationIT.<List<Integer>>read(
                opened, "$.issues[?(@.code == 'FEATURE_RETIRED')].featureId"))
        .containsExactly((int) feature("TRANS_MANUAL"));

    jdbc.sql("UPDATE feature SET status = 'ACTIVE' WHERE code = 'TRANS_MANUAL'").update();
    jdbc.sql("UPDATE trim SET active = true WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE region SET active = true WHERE code = 'EU'").update();

    assertThat(errors(open(ana(), copy))).isEmpty();
  }

  @Test
  void aWorkingCopyMadeFromACatalogWithARetiredFeatureKeepsItAndItsError() {
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'TRANS_MANUAL'").update();

    var later = workingCopy(ben(), "COMPACT_SUV", 2026);

    assertThat(errors(open(ben(), later))).containsExactly("FEATURE_RETIRED");
  }

  /** An offering of the working copy in which the Tow Package is offered: its trim and region. */
  private Map<String, Object> whereTowIsOffered() {
    return jdbc.sql(
            """
            SELECT t.name AS trim, c.region_code AS region
            FROM catalog_cell c
            JOIN trim t ON t.id = c.trim_id
            WHERE c.catalog_id = :copy AND c.feature_id = :tow
            ORDER BY t.sort_order, c.region_code
            LIMIT 1
            """)
        .param("copy", copy)
        .param("tow", feature("PACKAGE_TOW"))
        .query()
        .singleRow();
  }

  @Test
  void aCellEditThatBreaksAGlobalRuleAnswersWithTheIssueAndPuttingItRightClearsIt() {
    var offering = whereTowIsOffered();
    var trim = (String) offering.get("trim");
    var region = (String) offering.get("region");

    var broken = setCells(ana(), copy, "\"0\"", cell("COOLING_HEAVY_DUTY", trim, region, "N"));

    assertThat(broken).hasStatusOk();
    assertThat(errors(broken)).containsExactly("REQUIRED_NOT_OFFERED");
    assertThat(
            ApplicationIT.<List<String>>read(
                broken, "$.issues[?(@.code == 'REQUIRED_NOT_OFFERED')].rule.origin"))
        .containsExactly("GLOBAL");
    assertThat(
            ApplicationIT.<List<Integer>>read(
                broken, "$.issues[?(@.code == 'REQUIRED_NOT_OFFERED')].featureId"))
        .as("the issue is about the cell of the feature that requires")
        .containsExactly((int) feature("PACKAGE_TOW"));
    assertThat(
            ApplicationIT.<List<String>>read(
                broken, "$.issues[?(@.code == 'REQUIRED_NOT_OFFERED')].message"))
        .first()
        .asString()
        .startsWith("Tow Package requires Heavy-Duty Cooling, which is not offered on " + trim);

    var restored = setCells(ana(), copy, "\"1\"", cell("COOLING_HEAVY_DUTY", trim, region, "S"));

    assertThat(errors(restored)).isEmpty();
  }

  @Test
  void removingTheRowOfARequiredFeatureRaisesTheRulesErrorAndNothingTurnsTheRuleOff() {
    var removed =
        edit(
            ana(),
            mvc.delete()
                .uri("/api/catalogs/{id}/features/{feature}", copy, feature("COOLING_HEAVY_DUTY")),
            "\"0\"",
            null);

    assertThat(removed).hasStatusOk();
    assertThat(errors(removed)).isNotEmpty().containsOnly("REQUIRED_NOT_OFFERED");
  }

  @Test
  void aGlobalRuleLimitedToARegionIsCheckedInThatRegionAlone() {
    long rule =
        jdbc.sql(
                """
                INSERT INTO global_rule (kind, source_feature_id, all_regions)
                VALUES ('REQUIRES', :source, false)
                RETURNING id
                """)
            .param("source", feature("TRANS_MANUAL"))
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO global_rule_target VALUES (:rule, :target)")
        .param("rule", rule)
        .param("target", feature("ROOF_REMOVABLE"))
        .update();
    jdbc.sql("INSERT INTO global_rule_region VALUES (:rule, 'EU')").param("rule", rule).update();

    var saved =
        setCells(
            ana(),
            copy,
            "\"0\"",
            cell("TRANS_MANUAL", "Base", "NA", "A"),
            cell("TRANS_MANUAL", "Base", "EU", "A"));

    assertThat(saved).hasStatusOk();
    assertThat(
            ApplicationIT.<List<String>>read(
                saved, "$.issues[?(@.code == 'REQUIRED_NOT_OFFERED')].regionCode"))
        .as("the same cells in the two regions of the same trim")
        .containsExactly("EU");
  }

  /** A rule of the catalog by which the manual transmission requires the panoramic roof. */
  private String manualRequiresPanoramicRoof(String regions) {
    return """
        {"kind": "REQUIRES", "sourceFeatureId": %d, "targetFeatureIds": [%d],
         "allTrims": true, "trimIds": [], "allRegions": %s, "regionCodes": [%s]}
        """
        .formatted(feature("TRANS_MANUAL"), feature("ROOF_PANORAMIC"), regions.isEmpty(), regions);
  }

  @Test
  void aRuleOfTheCatalogIsCheckedInItsScopeAndEveryEditOfItAnswersWithTheIssues() {
    // The manual transmission is Standard on Base in Europe, where no panoramic roof is offered,
    // and is not offered in North America.
    var added =
        edit(
            ana(),
            mvc.post().uri("/api/catalogs/{id}/rules", copy),
            "\"0\"",
            manualRequiresPanoramicRoof(""));

    assertThat(errors(added)).containsExactly("REQUIRED_NOT_OFFERED");
    Map<String, String> rule = read(added, "$.issues[0].rule");
    assertThat(rule).containsEntry("origin", "CATALOG");
    assertThat(ApplicationIT.<String>read(added, "$.issues[0].regionCode")).isEqualTo("EU");
    assertThat(ApplicationIT.<List<String>>read(open(ana(), copy), "$.snapshot.rules[*].key"))
        .as("the issue names the rule by its key")
        .contains(rule.get("key"));

    var elsewhere =
        edit(
            ana(),
            mvc.put().uri("/api/catalogs/{id}/rules/{key}", copy, rule.get("key")),
            "\"1\"",
            manualRequiresPanoramicRoof("\"NA\""));

    assertThat(errors(elsewhere)).as("limited to North America").isEmpty();

    var back =
        edit(
            ana(),
            mvc.put().uri("/api/catalogs/{id}/rules/{key}", copy, rule.get("key")),
            "\"2\"",
            manualRequiresPanoramicRoof("\"EU\""));

    assertThat(errors(back)).as("limited to Europe").containsExactly("REQUIRED_NOT_OFFERED");

    var deleted =
        edit(
            ana(),
            mvc.delete().uri("/api/catalogs/{id}/rules/{key}", copy, rule.get("key")),
            "\"3\"",
            null);

    assertThat(errors(deleted)).isEmpty();
  }

  @Test
  void aRuleOfTheCatalogThatNamesARetiredFeatureIsAnErrorUntilTheFeatureIsActiveAgain() {
    // The copy starts with a rule by which ventilated seats require leather seats.
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'SEAT_LEATHER'").update();

    var opened = open(ana(), copy);

    assertThat(errors(opened)).containsExactlyInAnyOrder("FEATURE_RETIRED", "RULE_FEATURE_RETIRED");
    assertThat(
            ApplicationIT.<List<String>>read(
                opened, "$.issues[?(@.code == 'RULE_FEATURE_RETIRED')].message"))
        .containsExactly(
            "Leather Seats is retired, and the rule that Ventilated Front Seats requires Leather"
                + " Seats names it.");

    jdbc.sql("UPDATE feature SET status = 'ACTIVE' WHERE code = 'SEAT_LEATHER'").update();

    assertThat(errors(open(ana(), copy))).isEmpty();
  }

  @Test
  void aConflictThatOnlyAChainOfRulesMakesIsFoundOnTheCellOfTheFeatureThatStartsIt() {
    // Ventilated seats, heated rear seats, and the hands-free liftgate are each Available on
    // Touring, in both regions, so no rule below is broken by itself.
    var chain =
        List.of(
            List.of("REQUIRES", "SEAT_VENTILATED_FRONT", "SEAT_HEATED_REAR"),
            List.of("REQUIRES", "SEAT_HEATED_REAR", "LIFTGATE_HANDS_FREE"),
            List.of("EXCLUDES", "SEAT_VENTILATED_FRONT", "LIFTGATE_HANDS_FREE"));
    MvcTestResult answer = null;

    for (var rule : chain) {
      answer =
          edit(
              ana(),
              mvc.post().uri("/api/catalogs/{id}/rules", copy),
              "\"" + revision(copy) + "\"",
              """
              {"kind": "%s", "sourceFeatureId": %d, "targetFeatureIds": [%d],
               "allTrims": true, "trimIds": [], "allRegions": true, "regionCodes": []}
              """
                  .formatted(rule.get(0), feature(rule.get(1)), feature(rule.get(2))));
      assertThat(answer).hasStatusOk();
    }

    assertThat(errors(answer)).containsExactly("FEATURE_UNSELECTABLE", "FEATURE_UNSELECTABLE");
    var unselectable = "$.issues[?(@.code == 'FEATURE_UNSELECTABLE')]";
    assertThat(ApplicationIT.<List<Integer>>read(answer, unselectable + ".featureId"))
        .containsOnly((int) feature("SEAT_VENTILATED_FRONT"));
    assertThat(ApplicationIT.<List<String>>read(answer, unselectable + ".message"))
        .first()
        .asString()
        .startsWith("Ventilated Front Seats can never be ordered on Touring in North America.")
        .contains("Ventilated Front Seats brings Heated Rear Seats, which brings");
  }
}
