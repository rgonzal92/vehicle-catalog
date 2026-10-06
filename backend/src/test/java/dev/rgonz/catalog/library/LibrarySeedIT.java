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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Checks that an empty library starts with the seeded entries and a filled one is left alone. */
class LibrarySeedIT extends ApplicationIT {
  @Autowired LibrarySeed seed;
  @Autowired Validator validator;

  @BeforeEach
  void emptyLibrary() {
    jdbc.sql("TRUNCATE trim, region, vehicle_line, feature CASCADE").update();
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
    assertThat(count("SELECT count(*) FROM feature")).isBetween(200L, 500L);
    assertThat(count("SELECT count(*) FROM feature WHERE status <> 'ACTIVE'")).isZero();
    assertThat(count("SELECT count(*) FROM trim WHERE NOT active")).isZero();
  }

  @Test
  void everyCategoryHasSeededFeaturesAndPackagesAreAmongThem() {
    seed.loadIfEmpty();

    assertThat(
            count(
                "SELECT count(*) FROM category WHERE NOT EXISTS"
                    + " (SELECT 1 FROM feature WHERE category_code = category.code)"))
        .isZero();
    assertThat(column("SELECT name FROM feature WHERE kind = 'PACKAGE'"))
        .contains(
            "Technology Package",
            "Luxury Package",
            "Off-Road Package",
            "Performance Package",
            "Tow Package");
  }

  @Test
  void theFeaturesTheSeededRulesAndCatalogsRelyOnArePresent() {
    seed.loadIfEmpty();

    assertThat(column("SELECT name FROM feature"))
        .contains(
            "Panoramic Roof",
            "Removable Roof",
            "Manual Transmission",
            "Hybrid Powertrain",
            "Tow Package",
            "Heavy-Duty Cooling");
  }

  @Test
  void aLibraryThatAlreadyHasContentIsLeftAlone() {
    seed.loadIfEmpty();
    var features = count("SELECT count(*) FROM feature");

    seed.loadIfEmpty();

    assertThat(count("SELECT count(*) FROM feature")).isEqualTo(features);
    assertThat(count("SELECT count(*) FROM trim")).isEqualTo(6);

    jdbc.sql("TRUNCATE region, vehicle_line, feature CASCADE").update();
    seed.loadIfEmpty();

    assertThat(count("SELECT count(*) FROM feature")).as("trims are content enough").isZero();
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
    assertThat(features).extracting(NewFeature::code).doesNotHaveDuplicates();
  }

  private List<String> column(String sql) {
    return jdbc.sql(sql).query(String.class).list();
  }

  private long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }
}
