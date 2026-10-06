package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.catalog.CatalogSeed.SeededCatalog;
import dev.rgonz.catalog.catalog.CatalogSeed.SeededCell;
import dev.rgonz.catalog.catalog.CatalogSeed.SeededOffering;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks how a seeded catalog's feature rows are read into cells. */
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

  private static SeededCatalog catalog(Map<String, String> features) {
    var offerings = new LinkedHashMap<String, List<String>>();
    offerings.put("NA", List.of("Base", "Sport"));
    offerings.put("EU", List.of("Sport"));

    return new SeededCatalog("COMPACT_SUV", 2026, 1, "Launch", null, null, offerings, features);
  }
}
