package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks which lineages are seeded with an Approved version, and that each seeded catalog is a
 * sound example: uneven across its regions, complete, and consistent where its features depend on
 * or exclude each other. A failure names the catalog and the offering, so the seed file can be put
 * right from it. No test here changes anything, so the catalogs are seeded once for the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeededCatalogsIT extends ApplicationIT {
  private static final String TOW_PACKAGE = "PACKAGE_TOW";
  private static final String HEAVY_DUTY_COOLING = "COOLING_HEAVY_DUTY";
  private static final String REMOVABLE_ROOF = "ROOF_REMOVABLE";

  /** Where the first of a pair is Standard, the second is Not offered. */
  private static final Map<String, String> NOT_BESIDE_A_STANDARD =
      Map.of(
          "ROOF_PANORAMIC",
          REMOVABLE_ROOF,
          REMOVABLE_ROOF,
          "ROOF_PANORAMIC",
          "TRANS_MANUAL",
          "POWERTRAIN_HYBRID",
          "POWERTRAIN_HYBRID",
          "TRANS_MANUAL");

  @Autowired Catalogs catalogs;

  /** The seeded catalogs: Compact SUV 2026 in two versions, and three more. */
  private List<Seeded> seeded;

  @BeforeAll
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
    seeded =
        jdbc.sql("SELECT id FROM catalog ORDER BY id").query(Long.class).list().stream()
            .map(id -> catalogs.find(id, person("a-reader")).orElseThrow())
            .map(
                catalog ->
                    new Seeded(
                        "%s %d version %d"
                            .formatted(
                                catalog.vehicleLine(),
                                catalog.modelYear(),
                                catalog.versionNumber()),
                        catalog.snapshot()))
            .toList();
  }

  @Test
  void theFeaturesTheseChecksAskAboutAreInTheLibraryUnderTheirCodes() {
    assertThat(
            jdbc.sql("SELECT code || ' ' || name FROM feature WHERE code IN (:codes)")
                .param(
                    "codes",
                    Stream.concat(
                            Stream.of(TOW_PACKAGE, HEAVY_DUTY_COOLING),
                            NOT_BESIDE_A_STANDARD.keySet().stream())
                        .toList())
                .query(String.class)
                .list())
        .containsExactlyInAnyOrder(
            "PACKAGE_TOW Tow Package",
            "COOLING_HEAVY_DUTY Heavy-Duty Cooling",
            "ROOF_PANORAMIC Panoramic Roof",
            "ROOF_REMOVABLE Removable Roof",
            "TRANS_MANUAL Manual Transmission",
            "POWERTRAIN_HYBRID Hybrid Powertrain");
    assertThat(seeded).hasSize(5);
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
        .as("every lineage that exists has an Approved version")
        .isEqualTo(4);
  }

  @Test
  void compactSuv2027WasCarriedOverFromTheCurrentApprovedOf2026() {
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

    for (var catalog : seeded) {
      all.assertThat(catalog.snapshot().regions()).as(catalog.title()).hasSizeBetween(2, 3);
      all.assertThat(catalog.hasATrimNotSoldInOneOfItsRegions())
          .as("%s has a trim that is not sold in one of its regions", catalog.title())
          .isTrue();
      all.assertThat(catalog.hasACellThatDiffersBetweenRegions())
          .as("%s has a feature that one trim offers differently in two regions", catalog.title())
          .isTrue();
    }
    all.assertAll();
  }

  @Test
  void everySeededCatalogSellsEveryTrimAndRegionAndLeavesNoOfferingEmpty() {
    var all = new SoftAssertions();

    for (var catalog : seeded) {
      var snapshot = catalog.snapshot();
      for (var trim : snapshot.trims()) {
        all.assertThat(catalog.offeringsOf(trim))
            .as("%s sells %s somewhere", catalog.title(), trim.name())
            .isNotEmpty();
      }
      for (var region : snapshot.regions()) {
        all.assertThat(snapshot.offerings())
            .as("%s sells a trim in %s", catalog.title(), region.code())
            .anyMatch(offering -> offering.regionCode().equals(region.code()));
      }
      for (var offering : snapshot.offerings()) {
        all.assertThat(snapshot.featureRows())
            .as("%s, %s: a Standard or Available feature", catalog.title(), catalog.name(offering))
            .anyMatch(feature -> catalog.at(feature, offering) != Availability.N);
      }
    }
    all.assertAll();
  }

  @Test
  void everySeededCatalogKeepsTowingWithCoolingInEveryOffering() {
    var all = new SoftAssertions();

    for (var catalog : seeded) {
      for (var offering : catalog.snapshot().offerings()) {
        var where = "%s, %s".formatted(catalog.title(), catalog.name(offering));
        var tow = catalog.at(TOW_PACKAGE, offering);
        var cooling = catalog.at(HEAVY_DUTY_COOLING, offering);

        if (tow != Availability.N) {
          all.assertThat(cooling)
              .as("%s: Heavy-Duty Cooling, where Tow Package is offered", where)
              .isNotEqualTo(Availability.N);
        }
        if (tow == Availability.S) {
          all.assertThat(cooling)
              .as("%s: Heavy-Duty Cooling, where Tow Package is Standard", where)
              .isEqualTo(Availability.S);
        }
      }
    }
    all.assertAll();
  }

  @Test
  void everySeededCatalogLeavesAFeatureOutWhereTheOneItExcludesIsStandard() {
    var all = new SoftAssertions();

    for (var catalog : seeded) {
      for (var offering : catalog.snapshot().offerings()) {
        NOT_BESIDE_A_STANDARD.forEach(
            (standard, excluded) -> {
              if (catalog.at(standard, offering) == Availability.S) {
                all.assertThat(catalog.at(excluded, offering))
                    .as(
                        "%s, %s: %s, where %s is Standard",
                        catalog.title(), catalog.name(offering), excluded, standard)
                    .isEqualTo(Availability.N);
              }
            });
      }
    }
    all.assertAll();
  }

  @Test
  void aPickupOffersTowingEverywhereAndASedanOffersNoRemovableRoof() {
    var all = new SoftAssertions();

    for (var catalog : seeded) {
      for (var offering : catalog.snapshot().offerings()) {
        var where = "%s, %s".formatted(catalog.title(), catalog.name(offering));
        if (catalog.title().startsWith("Pickup Truck")) {
          all.assertThat(catalog.at(TOW_PACKAGE, offering))
              .as("%s: Tow Package", where)
              .isNotEqualTo(Availability.N);
        }
        if (catalog.title().startsWith("Sedan")) {
          all.assertThat(catalog.at(REMOVABLE_ROOF, offering))
              .as("%s: Removable Roof", where)
              .isEqualTo(Availability.N);
        }
      }
    }
    all.assertAll();
  }

  /** A seeded catalog, with what the checks ask of it. */
  private record Seeded(String title, CatalogSnapshot snapshot) {
    /** What the feature row states for the offering. A missing cell is Not offered. */
    Availability at(FeatureRow feature, Offering offering) {
      return snapshot.cells().stream()
          .filter(cell -> cell.featureId() == feature.id())
          .filter(cell -> cell.trimId() == offering.trimId())
          .filter(cell -> cell.regionCode().equals(offering.regionCode()))
          .map(Cell::availability)
          .findFirst()
          .orElse(Availability.N);
    }

    /**
     * What the feature with the code states for the offering. One that is no row is Not offered.
     */
    Availability at(String featureCode, Offering offering) {
      return snapshot.featureRows().stream()
          .filter(feature -> feature.code().equals(featureCode))
          .findFirst()
          .map(feature -> at(feature, offering))
          .orElse(Availability.N);
    }

    List<Offering> offeringsOf(Trim trim) {
      return snapshot.offerings().stream()
          .filter(offering -> offering.trimId() == trim.id())
          .toList();
    }

    /** The offering in words, such as "Sport in NA". */
    String name(Offering offering) {
      var trim =
          snapshot.trims().stream()
              .filter(candidate -> candidate.id() == offering.trimId())
              .map(Trim::name)
              .findFirst()
              .orElseThrow();

      return trim + " in " + offering.regionCode();
    }

    boolean hasATrimNotSoldInOneOfItsRegions() {
      return snapshot.offerings().size() < snapshot.trims().size() * snapshot.regions().size();
    }

    /** Whether some feature row states two different things for one trim in two regions. */
    boolean hasACellThatDiffersBetweenRegions() {
      return snapshot.featureRows().stream()
          .anyMatch(
              feature ->
                  snapshot.trims().stream()
                      .anyMatch(trim -> statedDifferentlyAcrossRegions(feature, trim)));
    }

    private boolean statedDifferentlyAcrossRegions(FeatureRow feature, Trim trim) {
      return offeringsOf(trim).stream().map(offering -> at(feature, offering)).distinct().count()
          > 1;
    }
  }
}
