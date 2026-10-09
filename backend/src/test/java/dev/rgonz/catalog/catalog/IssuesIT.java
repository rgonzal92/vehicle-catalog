package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.util.List;
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
}
