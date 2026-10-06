package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks that the seeded catalogs show every way a working copy can start, and that each is a sound
 * example: uneven across its regions, complete, and consistent in the combinations the seeded rules
 * will hold it to. No test here changes anything, so the catalogs are seeded once for the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeededCatalogsIT extends ApplicationIT {
  @Autowired Catalogs catalogs;

  @BeforeAll
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void fourLineagesHaveAnApprovedVersionAndNoOtherLineageExists() {
    var lineages = mvc.get().uri("/api/lineages").with(signedInAs(Role.AUTHOR)).exchange();

    assertThat(
            ApplicationIT.<List<Map<String, Object>>>read(lineages, "$").stream()
                .map(
                    lineage ->
                        "%s %s version %s"
                            .formatted(
                                lineage.get("vehicleLine"),
                                lineage.get("modelYear"),
                                lineage.get("versionNumber"))))
        .containsExactlyInAnyOrder(
            "Compact SUV 2026 version 2",
            "Compact SUV 2027 version 1",
            "Pickup Truck 2026 version 1",
            "Sedan 2027 version 1");
    assertThat(jdbc.sql("SELECT count(*) FROM lineage").query(Long.class).single())
        .as("a lineage without an Approved version would start a working copy differently")
        .isEqualTo(4);
  }

  @Test
  void compactSuv2027WasCarriedOverFromTheNewest2026Version() {
    assertThat(
            jdbc.sql(
                    """
                    SELECT bv.name || ' ' || bl.model_year || ' version ' || b.version_number
                    FROM catalog c
                    JOIN lineage l ON l.id = c.lineage_id
                    JOIN vehicle_line v ON v.id = l.vehicle_line_id
                    JOIN catalog b ON b.id = c.base_catalog_id
                    JOIN lineage bl ON bl.id = b.lineage_id
                    JOIN vehicle_line bv ON bv.id = bl.vehicle_line_id
                    WHERE v.code = 'COMPACT_SUV' AND l.model_year = 2027
                    """)
                .query(String.class)
                .list())
        .containsExactly("Compact SUV 2026 version 2");
  }

  @Test
  void everySeededCatalogCoversTwoOrThreeRegionsUnevenly() {
    var all = new SoftAssertions();

    for (var catalog : seeded()) {
      var snapshot = catalog.snapshot();
      all.assertThat(snapshot.regions()).as(catalog.title()).hasSizeBetween(2, 3);
      all.assertThat(
              snapshot.trims().stream()
                  .anyMatch(
                      trim ->
                          snapshot.regions().stream()
                              .anyMatch(
                                  region ->
                                      !snapshot
                                          .offerings()
                                          .contains(new Offering(trim.id(), region.code())))))
          .as("%s has a trim that is not sold in one of its regions", catalog.title())
          .isTrue();
      all.assertThat(
              snapshot.featureRows().stream()
                  .anyMatch(
                      feature ->
                          snapshot.trims().stream()
                              .anyMatch(
                                  trim ->
                                      snapshot.offerings().stream()
                                              .filter(offering -> offering.trimId() == trim.id())
                                              .map(offering -> catalog.at(feature, offering))
                                              .distinct()
                                              .count()
                                          > 1)))
          .as("%s has a cell that differs between regions", catalog.title())
          .isTrue();
    }
    all.assertAll();
  }

  @Test
  void everySeededCatalogSellsEveryTrimAndRegionAndLeavesNoOfferingEmpty() {
    var all = new SoftAssertions();

    for (var catalog : seeded()) {
      var snapshot = catalog.snapshot();
      all.assertThat(snapshot.offerings().stream().map(Offering::trimId).distinct())
          .as("%s sells every trim somewhere", catalog.title())
          .hasSameSizeAs(snapshot.trims());
      all.assertThat(snapshot.offerings().stream().map(Offering::regionCode).distinct())
          .as("%s sells a trim in every region", catalog.title())
          .hasSameSizeAs(snapshot.regions());
      for (var offering : snapshot.offerings()) {
        all.assertThat(snapshot.cells())
            .as("%s has a Standard or Available feature in %s", catalog.title(), offering)
            .anyMatch(
                cell ->
                    cell.trimId() == offering.trimId()
                        && cell.regionCode().equals(offering.regionCode()));
      }
    }
    all.assertAll();
  }

  @Test
  void everySeededCatalogKeepsTheCombinationsConsistentInEveryOffering() {
    var all = new SoftAssertions();

    for (var catalog : seeded()) {
      for (var offering : catalog.snapshot().offerings()) {
        var where = "%s, %s".formatted(catalog.title(), offering);
        var tow = catalog.at("Tow Package", offering);
        var cooling = catalog.at("Heavy-Duty Cooling", offering);
        if (tow != Availability.N) {
          all.assertThat(cooling)
              .as("Heavy-Duty Cooling with Tow Package in " + where)
              .isNotEqualTo(Availability.N);
        }
        if (tow == Availability.S) {
          all.assertThat(cooling)
              .as("Heavy-Duty Cooling with a Standard Tow Package in " + where)
              .isEqualTo(Availability.S);
        }
        for (var pair :
            List.of(
                List.of("Panoramic Roof", "Removable Roof"),
                List.of("Manual Transmission", "Hybrid Powertrain"))) {
          for (var one : pair) {
            var other = pair.get(1 - pair.indexOf(one));
            if (catalog.at(one, offering) == Availability.S) {
              all.assertThat(catalog.at(other, offering))
                  .as("%s beside a Standard %s in %s", other, one, where)
                  .isEqualTo(Availability.N);
            }
          }
        }
      }
    }
    all.assertAll();
  }

  @Test
  void aPickupOffersTowingEverywhereAndASedanOffersNoRemovableRoof() {
    for (var catalog : seeded()) {
      for (var offering : catalog.snapshot().offerings()) {
        if (catalog.title().startsWith("Pickup Truck")) {
          assertThat(catalog.at("Tow Package", offering))
              .as("%s, %s", catalog.title(), offering)
              .isNotEqualTo(Availability.N);
        }
        if (catalog.title().startsWith("Sedan")) {
          assertThat(catalog.at("Removable Roof", offering))
              .as("%s, %s", catalog.title(), offering)
              .isEqualTo(Availability.N);
        }
      }
    }
  }

  /** Every catalog in the database, which a first start leaves holding only the seeded ones. */
  private List<Seeded> seeded() {
    var ids = jdbc.sql("SELECT id FROM catalog ORDER BY id").query(Long.class).list();
    assertThat(ids).hasSize(5);

    return ids.stream()
        .map(id -> catalogs.find(id).orElseThrow())
        .map(
            catalog ->
                new Seeded(
                    "%s %d version %d"
                        .formatted(
                            catalog.vehicleLine(), catalog.modelYear(), catalog.versionNumber()),
                    catalog.snapshot()))
        .toList();
  }

  /** A seeded catalog, with a way to ask what any feature is in any offering. */
  private record Seeded(String title, CatalogSnapshot snapshot) {
    Availability at(FeatureRow feature, Offering offering) {
      return snapshot.cells().stream()
          .filter(
              cell ->
                  cell.featureId() == feature.id()
                      && cell.trimId() == offering.trimId()
                      && cell.regionCode().equals(offering.regionCode()))
          .map(Cell::availability)
          .findFirst()
          .orElse(Availability.N);
    }

    /** A feature that is not a row of the catalog is Not offered. */
    Availability at(String featureName, Offering offering) {
      var byName =
          snapshot.featureRows().stream()
              .collect(Collectors.toMap(FeatureRow::name, Function.identity()));

      return byName.containsKey(featureName)
          ? at(byName.get(featureName), offering)
          : Availability.N;
    }
  }
}
