package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Kind;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.catalog.Issue.Code;
import dev.rgonz.catalog.catalog.Issue.Severity;
import dev.rgonz.catalog.catalog.Validation.Library;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Checks each issue that needs no rule: when it is raised, what it is about, and when it is not.
 * The catalog the tests start from has none: Base and Sport, each sold in North America and in
 * Europe, and a roof that is Standard in every offering.
 */
class ValidationTest {
  private static final Trim BASE = new Trim(1, "Base", 1);
  private static final Trim SPORT = new Trim(2, "Sport", 2);
  private static final Region NA = new Region("NA", "North America");
  private static final Region EU = new Region("EU", "Europe");
  private static final FeatureRow ROOF =
      new FeatureRow(7, "ROOF_PANORAMIC", Kind.FEATURE, "Panoramic Roof", "EXTERIOR");
  private static final FeatureRow TOW =
      new FeatureRow(8, "TOW_PACKAGE", Kind.PACKAGE, "Tow Package", "PACKAGES");

  private static final Library NOTHING_RETIRED = new Library(Set.of(), Set.of(), Set.of());

  private static final List<Offering> EVERY_OFFERING =
      List.of(
          new Offering(1, "NA"),
          new Offering(1, "EU"),
          new Offering(2, "NA"),
          new Offering(2, "EU"));

  /** The roof, Standard in every offering. */
  private static final List<Cell> ROOF_EVERYWHERE =
      EVERY_OFFERING.stream()
          .map(offering -> new Cell(7, offering.trimId(), offering.regionCode(), Availability.S))
          .toList();

  private static CatalogSnapshot catalog(
      List<Trim> trims,
      List<Region> regions,
      List<Offering> offerings,
      List<FeatureRow> featureRows,
      List<Cell> cells) {
    return new CatalogSnapshot(
        41, 3, Status.DRAFT, 4, trims, regions, offerings, featureRows, cells, List.of());
  }

  private static CatalogSnapshot sound() {
    return catalog(
        List.of(BASE, SPORT), List.of(NA, EU), EVERY_OFFERING, List.of(ROOF), ROOF_EVERYWHERE);
  }

  private static List<Code> codes(List<Issue> issues) {
    return issues.stream().map(Issue::code).toList();
  }

  @Test
  void aCatalogWithContentInEveryOfferingHasNoIssues() {
    assertThat(Validation.issues(sound(), NOTHING_RETIRED)).isEmpty();
  }

  @Test
  void anEmptyCatalogLacksTrimsRegionsAndFeatureRows() {
    var issues =
        Validation.issues(
            catalog(List.of(), List.of(), List.of(), List.of(), List.of()), NOTHING_RETIRED);

    assertThat(codes(issues)).containsExactly(Code.NO_TRIMS, Code.NO_REGIONS, Code.NO_FEATURES);
    assertThat(issues).allSatisfy(issue -> assertThat(issue.severity()).isEqualTo(Severity.ERROR));
    assertThat(issues.getFirst().message()).isEqualTo("The catalog has no trims.");
  }

  @Test
  void aTrimSoldInNoRegionIsAnError() {
    var offerings = List.of(new Offering(1, "NA"), new Offering(1, "EU"));
    var cells = ROOF_EVERYWHERE.stream().filter(cell -> cell.trimId() == 1).toList();

    var issues =
        Validation.issues(
            catalog(List.of(BASE, SPORT), List.of(NA, EU), offerings, List.of(ROOF), cells),
            NOTHING_RETIRED);

    assertThat(issues)
        .containsExactly(
            new Issue(
                Code.TRIM_NOT_SOLD,
                Severity.ERROR,
                2L,
                null,
                null,
                List.of(),
                null,
                "Sport is sold in no region."));
  }

  @Test
  void aRegionWithNoTrimSoldInItIsAnError() {
    var offerings = List.of(new Offering(1, "NA"), new Offering(2, "NA"));
    var cells = ROOF_EVERYWHERE.stream().filter(cell -> cell.regionCode().equals("NA")).toList();

    var issues =
        Validation.issues(
            catalog(List.of(BASE, SPORT), List.of(NA, EU), offerings, List.of(ROOF), cells),
            NOTHING_RETIRED);

    assertThat(issues)
        .containsExactly(
            new Issue(
                Code.REGION_WITHOUT_TRIMS,
                Severity.ERROR,
                null,
                "EU",
                null,
                List.of(),
                null,
                "No trim is sold in Europe."));
  }

  @Test
  void anOfferingWithNoStandardOrAvailableFeatureIsEmpty() {
    var cells =
        ROOF_EVERYWHERE.stream()
            .filter(cell -> !(cell.trimId() == 2 && cell.regionCode().equals("EU")))
            .toList();

    var issues =
        Validation.issues(
            catalog(List.of(BASE, SPORT), List.of(NA, EU), EVERY_OFFERING, List.of(ROOF), cells),
            NOTHING_RETIRED);

    assertThat(issues)
        .containsExactly(
            new Issue(
                Code.OFFERING_EMPTY,
                Severity.ERROR,
                2L,
                "EU",
                null,
                List.of(),
                null,
                "Sport in Europe has no Standard or Available feature."));
  }

  @Test
  void oneAvailableFeatureIsEnoughForAnOffering() {
    var cells =
        ROOF_EVERYWHERE.stream()
            .map(
                cell ->
                    cell.trimId() == 2 && cell.regionCode().equals("EU")
                        ? new Cell(7, 2, "EU", Availability.A)
                        : cell)
            .toList();

    assertThat(
            Validation.issues(
                catalog(
                    List.of(BASE, SPORT), List.of(NA, EU), EVERY_OFFERING, List.of(ROOF), cells),
                NOTHING_RETIRED))
        .isEmpty();
  }

  @Test
  void aFeatureRowThatIsOfferedNowhereIsAWarning() {
    var issues =
        Validation.issues(
            catalog(
                List.of(BASE, SPORT),
                List.of(NA, EU),
                EVERY_OFFERING,
                List.of(ROOF, TOW),
                ROOF_EVERYWHERE),
            NOTHING_RETIRED);

    assertThat(issues)
        .containsExactly(
            new Issue(
                Code.FEATURE_NEVER_OFFERED,
                Severity.WARNING,
                null,
                null,
                8L,
                List.of(),
                null,
                "Tow Package is not offered in any offering."));
  }

  @Test
  void aCatalogWithoutOfferingsDoesNotAlsoSayThatEachFeatureRowIsOfferedNowhere() {
    var issues =
        Validation.issues(
            catalog(List.of(BASE), List.of(), List.of(), List.of(ROOF), List.of()),
            NOTHING_RETIRED);

    assertThat(codes(issues)).containsExactly(Code.NO_REGIONS, Code.TRIM_NOT_SOLD);
  }

  @Test
  void aRetiredFeatureRowIsAnErrorEvenWhenItIsOfferedNowhere() {
    var issues =
        Validation.issues(
            catalog(
                List.of(BASE, SPORT),
                List.of(NA, EU),
                EVERY_OFFERING,
                List.of(ROOF, TOW),
                ROOF_EVERYWHERE),
            new Library(Set.of(8L), Set.of(), Set.of()));

    assertThat(codes(issues)).containsExactly(Code.FEATURE_RETIRED, Code.FEATURE_NEVER_OFFERED);
    assertThat(issues.getFirst().featureId()).isEqualTo(8L);
    assertThat(issues.getFirst().message()).isEqualTo("Tow Package is retired.");
  }

  @Test
  void anInactiveTrimAndAnInactiveRegionAreErrors() {
    var issues = Validation.issues(sound(), new Library(Set.of(), Set.of(2L), Set.of("EU")));

    assertThat(issues)
        .containsExactly(
            new Issue(
                Code.TRIM_INACTIVE,
                Severity.ERROR,
                2L,
                null,
                null,
                List.of(),
                null,
                "Sport is inactive."),
            new Issue(
                Code.REGION_INACTIVE,
                Severity.ERROR,
                null,
                "EU",
                null,
                List.of(),
                null,
                "Europe is inactive."));
  }

  @Test
  void errorsComeBeforeWarnings() {
    var cells = ROOF_EVERYWHERE.stream().filter(cell -> cell.trimId() == 1).toList();

    var issues =
        Validation.issues(
            catalog(
                List.of(BASE, SPORT),
                List.of(NA, EU),
                List.of(new Offering(1, "NA"), new Offering(1, "EU")),
                List.of(TOW, ROOF),
                cells),
            NOTHING_RETIRED);

    assertThat(codes(issues)).containsExactly(Code.TRIM_NOT_SOLD, Code.FEATURE_NEVER_OFFERED);
  }
}
