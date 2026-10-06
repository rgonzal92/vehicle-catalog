package dev.rgonz.catalog.library;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.library.Feature.Kind;
import dev.rgonz.catalog.library.Features.NewFeature;
import dev.rgonz.catalog.library.Regions.NewRegion;
import dev.rgonz.catalog.library.Trims.NewTrim;
import dev.rgonz.catalog.vehicleline.VehicleLines.NewVehicleLine;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Checks that an empty library starts with the seeded entries and a filled one is left alone. */
class LibrarySeedIT extends ApplicationIT {
  private static final List<String> TABLES = List.of("trim", "region", "vehicle_line", "feature");

  @Autowired LibrarySeed seed;
  @Autowired Validator validator;

  @BeforeEach
  void emptyLibrary() {
    emptyLibraryAndCatalogs();
  }

  @Test
  void anEmptyLibraryStartsWithTheSeededEntries() {
    seed.loadIfEmpty();

    assertThat(column("SELECT name FROM trim ORDER BY sort_order"))
        .containsExactly("Base", "Sport", "Touring", "Luxury", "Off-Road", "Performance");
    assertThat(column("SELECT code || ' ' || name FROM region ORDER BY sort_order"))
        .containsExactly("NA North America", "SA South America", "EU Europe", "ASIA Asia");
    assertThat(column("SELECT name || ' ' || vehicle_type_code FROM vehicle_line ORDER BY name"))
        .containsExactly(
            "Compact SUV SUV",
            "Full-Size SUV SUV",
            "Pickup Truck TRUCK",
            "Sedan CAR",
            "Sports Coupe CAR");
    assertThat(count("feature")).isBetween(200L, 500L);
    assertThat(column("SELECT name FROM feature"))
        .contains(
            "Panoramic Roof",
            "Removable Roof",
            "Manual Transmission",
            "Hybrid Powertrain",
            "Tow Package",
            "Heavy-Duty Cooling");
    assertThat(column("SELECT code FROM feature WHERE status <> 'ACTIVE'")).isEmpty();
    assertThat(column("SELECT name FROM trim WHERE NOT active")).isEmpty();
  }

  @Test
  void everyCategoryHasSeededFeaturesAndPackagesAreAmongThem() {
    seed.loadIfEmpty();

    assertThat(
            column(
                "SELECT code FROM category WHERE NOT EXISTS"
                    + " (SELECT 1 FROM feature WHERE category_code = category.code)"))
        .isEmpty();
    assertThat(column("SELECT name FROM feature WHERE kind = 'PACKAGE'"))
        .contains(
            "Technology Package",
            "Luxury Package",
            "Off-Road Package",
            "Performance Package",
            "Tow Package");
  }

  @Test
  void startingAgainAddsNothing() {
    seed.loadIfEmpty();
    var loaded = TABLES.stream().map(this::count).toList();

    seed.loadIfEmpty();

    assertThat(TABLES.stream().map(this::count)).containsExactlyElementsOf(loaded);
  }

  @Test
  void aLibraryHoldingAnythingAtAllIsLeftAlone() {
    var oneEntry =
        Map.of(
            "trim",
            "INSERT INTO trim (name, sort_order) VALUES ('Base', 1)",
            "region",
            "INSERT INTO region (code, name, sort_order) VALUES ('NA', 'North America', 1)",
            "vehicle_line",
            "INSERT INTO vehicle_line (code, name, vehicle_type_code)"
                + " VALUES ('SEDAN', 'Sedan', 'CAR')",
            "feature",
            "INSERT INTO feature (code, name, category_code, kind)"
                + " VALUES ('ROOF_FIXED', 'Fixed Roof', 'EXTERIOR', 'FEATURE')");

    for (var holding : TABLES) {
      emptyLibrary();
      jdbc.sql(oneEntry.get(holding)).update();

      seed.loadIfEmpty();

      for (var table : TABLES) {
        assertThat(count(table))
            .as("%s when only %s holds an entry", table, holding)
            .isEqualTo(table.equals(holding) ? 1 : 0);
      }
    }
  }

  @Test
  void everySeededEntrySatisfiesTheRulesOfTheAdminEndpoints() {
    var features = seed.read("features.json", NewFeature.class);

    for (var entries :
        List.of(
            seed.read("trims.json", NewTrim.class),
            seed.read("regions.json", NewRegion.class),
            seed.read("vehicle-lines.json", NewVehicleLine.class),
            features)) {
      assertThat(entries)
          .isNotEmpty()
          .allSatisfy(entry -> assertThat(validator.validate(entry)).isEmpty());
    }
    assertThat(features)
        .allSatisfy(
            feature ->
                assertThat(feature.kind() == Kind.PACKAGE)
                    .as(feature.code())
                    .isEqualTo(feature.categoryCode().equals("PACKAGES")));
  }

  private List<String> column(String sql) {
    return jdbc.sql(sql).query(String.class).list();
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
