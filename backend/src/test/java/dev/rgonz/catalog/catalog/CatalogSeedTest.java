package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.catalog.CatalogSeed.SeededCatalog;
import dev.rgonz.catalog.catalog.CatalogSeed.SeededCell;
import dev.rgonz.catalog.catalog.CatalogSeed.SeededOffering;
import dev.rgonz.catalog.catalog.CatalogSeed.SeededRule;
import dev.rgonz.catalog.catalog.CatalogSeed.StoredRule;
import dev.rgonz.catalog.library.RuleKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks how a seeded catalog's feature rows are read into cells, and its rules into rows. */
class CatalogSeedTest {
  private static final SeededOffering BASE_IN_NA = new SeededOffering("Base", "NA");
  private static final SeededOffering SPORT_IN_NA = new SeededOffering("Sport", "NA");
  private static final SeededOffering SPORT_IN_EU = new SeededOffering("Sport", "EU");

  @Test
  void aRowSpellsOneCellPerOfferingRegionByRegionAndADashIsNoCell() {
    var catalog = catalog(Map.of("ROOF_PANORAMIC", "-S A"));

    assertThat(catalog.offeringsInOrder()).containsExactly(BASE_IN_NA, SPORT_IN_NA, SPORT_IN_EU);
    assertThat(catalog.cells())
        .containsExactly(
            new SeededCell("ROOF_PANORAMIC", SPORT_IN_NA, 'S'),
            new SeededCell("ROOF_PANORAMIC", SPORT_IN_EU, 'A'));
  }

  @Test
  void aRowWithTooFewTooManyOrUnknownCharactersIsRefusedByName() {
    for (var row : List.of("-S", "-S AA", "-S X", "")) {
      assertThatThrownBy(() -> catalog(Map.of("ROOF_PANORAMIC", row)).cells())
          .as(row)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ROOF_PANORAMIC");
    }
  }

  @Test
  void anExclusionIsStoredAsAPairAndARuleKeepsItsKeyFromOneVersionToTheNext() {
    var excludes =
        new SeededRule(
            "roofs", RuleKind.EXCLUDES, "ROOF_PANORAMIC", List.of("ROOF_SUNROOF"), null, null);
    var requires =
        new SeededRule(
            "heat",
            RuleKind.REQUIRES,
            "SEAT_HEATED_REAR",
            List.of("SEAT_HEATED_FRONT"),
            List.of("Sport"),
            List.of("EU"));

    var stored = catalog(Map.of(), excludes, requires).storedRules();

    assertThat(stored).hasSize(3);
    assertThat(stored.get(0).pairKey()).isNotNull().isEqualTo(stored.get(1).pairKey());
    assertThat(stored.get(1).source()).isEqualTo("ROOF_SUNROOF");
    assertThat(stored.get(1).targets()).containsExactly("ROOF_PANORAMIC");
    assertThat(stored.get(2).pairKey()).isNull();
    assertThat(stored.get(2).trims()).containsExactly("Sport");
    assertThat(stored.get(0).regions()).as("a scope that covers everything").isEmpty();
    assertThat(stored.stream().map(StoredRule::key)).doesNotHaveDuplicates();
    assertThat(catalog(Map.of(), requires).storedRules().getFirst().key())
        .as("the same name in another version")
        .isEqualTo(stored.get(2).key());
  }

  @Test
  void anExclusionWithSeveralTargetsIsRefusedByName() {
    var excludes =
        new SeededRule(
            "roofs",
            RuleKind.EXCLUDES,
            "ROOF_PANORAMIC",
            List.of("ROOF_SUNROOF", "ROOF_FIXED"),
            null,
            null);

    assertThatThrownBy(() -> catalog(Map.of(), excludes).storedRules())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("roofs");
  }

  private static SeededCatalog catalog(Map<String, String> features, SeededRule... rules) {
    var offerings = new LinkedHashMap<String, List<String>>();
    offerings.put("NA", List.of("Base", "Sport"));
    offerings.put("EU", List.of("Sport"));

    return new SeededCatalog(
        "COMPACT_SUV", 2026, 1, "Launch", null, null, offerings, List.of(rules), features);
  }
}
