package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks what the diff of two catalogs answers, and for whom. Each test starts from the seeded
 * catalogs, of which Compact SUV 2026 has two Approved versions, and Compact SUV 2027 was carried
 * over from the second.
 */
class DiffIT extends WorkingCopyTests {
  private long first;
  private long second;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
    first = approved("COMPACT_SUV", 2026, 1);
    second = approved("COMPACT_SUV", 2026, 2);
  }

  @Test
  void theSecondVersionOfTheCompactSuvIsComparedWithTheFirst() {
    var diff = diff(ana(), second, String.valueOf(first));

    assertThat(diff).hasStatusOk();
    assertThat(ApplicationIT.<List<String>>read(diff, "$.featureRowsAdded[*].code"))
        .containsExactly(
            "BADGES_BLACK",
            "BATTERY_COOLING",
            "BRAKE_REGENERATIVE",
            "PACKAGE_BLACK_APPEARANCE",
            "PAINT_TWO_TONE_ROOF",
            "POWERTRAIN_HYBRID");
    assertThat(ApplicationIT.<List<Object>>read(diff, "$.featureRowsRemoved")).isEmpty();
    assertThat(ApplicationIT.<List<Object>>read(diff, "$.trimsAdded")).isEmpty();
    assertThat(ApplicationIT.<List<String>>read(diff, "$.rulesAdded[*].rule"))
        .contains("Hybrid Powertrain requires Regenerative Braking")
        .hasSize(2);
    List<Map<String, Object>> cells = read(diff, "$.cellsChanged");
    assertThat(cells).isNotEmpty();
    assertThat(cells)
        .as("each with what it is a cell of, and its availability before and after")
        .allSatisfy(
            cell -> {
              assertThat(cell)
                  .containsKeys("featureId", "featureCode", "feature", "trimId", "trim", "region");
              assertThat(cell.get("before")).isIn("S", "A", "N").isNotEqualTo(cell.get("after"));
              assertThat(cell.get("after")).isIn("S", "A", "N");
            });
    assertThat(cells)
        .as("the cells of the rows it added are not listed one by one")
        .noneMatch(cell -> cell.get("featureCode").equals("POWERTRAIN_HYBRID"));
  }

  @Test
  void aVersionDoesNotDifferFromItselfAndTheOtherWayRoundWhatWasAddedIsRemoved() {
    var same = diff(ana(), second, String.valueOf(second));
    var backwards = diff(ana(), first, String.valueOf(second));

    assertThat(DiffIT.changes(same, "$").values()).allMatch(List::isEmpty);
    assertThat(ApplicationIT.<List<String>>read(backwards, "$.featureRowsRemoved[*].code"))
        .contains("POWERTRAIN_HYBRID");
  }

  @Test
  void aLabelTheLibraryChangesAfterApprovalChangesNothingInTheComparison() {
    var before = ApplicationIT.<Map<String, Object>>read(diff(ana(), second, "" + first), "$");
    jdbc.sql("UPDATE trim SET name = 'Sport Plus', sort_order = 99 WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE feature SET name = 'Hybrid Drive' WHERE code = 'POWERTRAIN_HYBRID'").update();
    jdbc.sql("UPDATE region SET name = 'Europe and Africa' WHERE code = 'EU'").update();

    var after = ApplicationIT.<Map<String, Object>>read(diff(ana(), second, "" + first), "$");

    assertThat(after).isEqualTo(before);
  }

  @Test
  void aCatalogIsComparedWithItsBaseAndOneThatStartedEmptyWithNothing() {
    var carriedOver = diff(ana(), approved("COMPACT_SUV", 2027, 1), "base");
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    var empty = workingCopy(ana(), "SPORTS_COUPE", 2026);
    edit(
        ana(),
        mvc.post().uri("/api/catalogs/{id}/trims", empty),
        "\"0\"",
        "{\"trimIds\": [%d]}".formatted(trim("Sport")));

    assertThat(ApplicationIT.<List<String>>read(carriedOver, "$.regionsAdded[*].code"))
        .as("what the 2027 catalog changed since the 2026 version it was carried over from")
        .containsExactly("ASIA");
    assertThat(DiffIT.changes(diff(ana(), copy, "base"), "$").values())
        .as("a copy nobody has edited")
        .allMatch(List::isEmpty);
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    assertThat(
            ApplicationIT.<List<String>>read(diff(ana(), copy, "base"), "$.cellsChanged[*].after"))
        .containsExactly("A");
    assertThat(ApplicationIT.<List<String>>read(diff(ana(), empty, "base"), "$.trimsAdded[*].name"))
        .as("everything a catalog that started empty has is added")
        .containsExactly("Sport");
  }

  @Test
  void theDiffAnswersOnlyForSomeoneWhoMayOpenBothCatalogs() {
    var anas = workingCopy(ana(), "COMPACT_SUV", 2026);

    assertThat(diff(ana(), anas, String.valueOf(second))).hasStatusOk();
    assertThat(diff(ben(), anas, String.valueOf(second)))
        .as("another person's working copy")
        .hasStatus(404);
    assertThat(diff(ben(), second, String.valueOf(anas))).as("against one").hasStatus(404);
    assertThat(diff(ana(), second, "987654321")).as("against none").hasStatus(404);
    assertThat(diff(ana(), second, "the-other-one")).hasStatus(400);
  }

  /** A diff as it was answered: each kind of change with what it lists. */
  private static Map<String, List<Object>> changes(MvcTestResult diff, String path) {
    return read(diff, path);
  }

  private MvcTestResult diff(RequestPostProcessor who, long catalog, String against) {
    return mvc.get()
        .uri("/api/catalogs/{id}/diff?against={against}", catalog, against)
        .with(who)
        .exchange();
  }
}
