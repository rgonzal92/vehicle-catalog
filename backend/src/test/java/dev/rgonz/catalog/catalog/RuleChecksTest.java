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
import dev.rgonz.catalog.catalog.Issue.RuleReference;
import dev.rgonz.catalog.catalog.Issue.Severity;
import dev.rgonz.catalog.catalog.Validation.Library;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks an offering against a rule of each kind, for every pairing of what its source and its
 * targets may be: S is Standard, A is Available, and N is Not offered. The catalog sells Sport in
 * North America and in Europe, and has a feature that is Standard everywhere, so that it has no
 * issue of its own.
 */
class RuleChecksTest {
  private static final long TOW = 8;
  private static final long COOLING = 9;
  private static final long HITCH = 10;

  /** A feature the library has and the catalog has no row for. */
  private static final long WIRING = 11;

  private static final Map<Long, String> NAMES =
      Map.of(
          TOW, "Tow Package",
          COOLING, "Heavy-Duty Cooling",
          HITCH, "Trailer Hitch Receiver",
          WIRING, "Trailer Wiring");

  private static Rule global(Rule.Kind kind, long source, Long... targets) {
    return new Rule(
        Rule.Origin.GLOBAL,
        "12",
        kind,
        source,
        List.of(targets),
        true,
        Set.of(),
        true,
        Set.of(),
        kind == Rule.Kind.EXCLUDES ? "a-pair" : null);
  }

  /** What validation finds from rules when the features have the cells given, in North America. */
  private static List<Issue> found(List<Rule> rules, Map<Long, String> inNorthAmerica) {
    return found(rules, inNorthAmerica, Map.of());
  }

  private static List<Issue> found(
      List<Rule> rules, Map<Long, String> inNorthAmerica, Map<Long, String> inEurope) {
    var cells = new ArrayList<Cell>();
    cells.add(new Cell(1, 2, "NA", Availability.S));
    cells.add(new Cell(1, 2, "EU", Availability.S));
    inNorthAmerica.forEach((feature, value) -> add(cells, feature, "NA", value));
    inEurope.forEach((feature, value) -> add(cells, feature, "EU", value));
    var catalog =
        new CatalogSnapshot(
            41,
            3,
            Status.DRAFT,
            4,
            List.of(new Trim(2, "Sport", 2)),
            List.of(new Region("NA", "North America"), new Region("EU", "Europe")),
            List.of(new Offering(2, "NA"), new Offering(2, "EU")),
            List.of(
                new FeatureRow(1, "ENGINE", Kind.FEATURE, "Engine", "POWERTRAIN"),
                new FeatureRow(TOW, "PACKAGE_TOW", Kind.PACKAGE, "Tow Package", "PACKAGES"),
                new FeatureRow(COOLING, "COOLING", Kind.FEATURE, "Heavy-Duty Cooling", "THERMAL"),
                new FeatureRow(HITCH, "HITCH", Kind.FEATURE, "Trailer Hitch Receiver", "CHASSIS")),
            cells);

    return Validation.issues(catalog, new Library(Set.of(), Set.of(), Set.of(), rules, NAMES))
        .stream()
        .filter(issue -> issue.rule() != null)
        .toList();
  }

  private static void add(List<Cell> cells, long feature, String region, String value) {
    if (!value.equals("N")) {
      cells.add(new Cell(feature, 2, region, Availability.valueOf(value)));
    }
  }

  private static List<String> codes(List<Issue> issues) {
    return issues.stream().map(issue -> issue.code().name()).toList();
  }

  private static List<String> expected(String code) {
    return code == null ? List.of() : List.of(code);
  }

  @ParameterizedTest(name = "source {0}, target {1}: {2}")
  @CsvSource({
    "S, S,",
    "S, A, REQUIRED_NOT_STANDARD",
    "S, N, REQUIRED_NOT_OFFERED",
    "A, S,",
    "A, A,",
    "A, N, REQUIRED_NOT_OFFERED",
    "N, S,",
    "N, A,",
    "N, N,",
  })
  void requires(String source, String target, String code) {
    var issues =
        found(
            List.of(global(Rule.Kind.REQUIRES, TOW, COOLING)),
            Map.of(TOW, source, COOLING, target));

    assertThat(codes(issues)).isEqualTo(expected(code));
  }

  @ParameterizedTest(name = "one side {0}, other side {1}: {2}")
  @CsvSource({
    "S, S, EXCLUDED_BOTH_STANDARD",
    "S, A, EXCLUDED_BY_STANDARD",
    "A, S, EXCLUDED_BY_STANDARD",
    "A, A,",
    "N, S,",
    "N, A,",
    "S, N,",
    "A, N,",
    "N, N,",
  })
  void excludes(String one, String other, String code) {
    var issues =
        found(List.of(global(Rule.Kind.EXCLUDES, TOW, COOLING)), Map.of(TOW, one, COOLING, other));

    assertThat(codes(issues)).isEqualTo(expected(code));
  }

  @ParameterizedTest(name = "package {0}, target {1}: {2}")
  @CsvSource({
    "S, S,",
    "S, A, INCLUDED_NOT_STANDARD",
    "S, N, INCLUDED_NOT_OFFERED",
    "A, N, INCLUDED_NOT_OFFERED",
    "A, A,",
    "A, S, INCLUDED_ALREADY_STANDARD",
    "N, S,",
    "N, A,",
    "N, N,",
  })
  void includes(String pack, String target, String code) {
    var issues =
        found(List.of(global(Rule.Kind.INCLUDES, TOW, HITCH)), Map.of(TOW, pack, HITCH, target));

    assertThat(codes(issues)).isEqualTo(expected(code));
  }

  @ParameterizedTest(name = "source {0}, targets {1} and {2}: {3}")
  @CsvSource({
    "S, N, N, ONE_OF_NONE_OFFERED",
    "A, N, N, ONE_OF_NONE_OFFERED",
    "S, A, N,",
    "A, N, S,",
    "S, S, A,",
    "N, N, N,",
    "N, S, N,",
  })
  void requiresOneOf(String source, String first, String second, String code) {
    var issues =
        found(
            List.of(global(Rule.Kind.REQUIRES_ONE_OF, TOW, COOLING, HITCH)),
            Map.of(TOW, source, COOLING, first, HITCH, second));

    assertThat(codes(issues)).isEqualTo(expected(code));
  }

  @Test
  void anIssueNamesItsOfferingItsCellTheOtherFeatureAndItsRule() {
    var issues =
        found(List.of(global(Rule.Kind.REQUIRES, TOW, COOLING)), Map.of(TOW, "A", COOLING, "N"));

    assertThat(issues)
        .containsExactly(
            new Issue(
                Code.REQUIRED_NOT_OFFERED,
                Severity.ERROR,
                2L,
                "NA",
                TOW,
                List.of(COOLING),
                new RuleReference("GLOBAL", "12"),
                "Tow Package requires Heavy-Duty Cooling, which is not offered on Sport in North"
                    + " America."));
  }

  @Test
  void theOnlyWarningIsAPackageWhoseTargetIsAlreadyStandard() {
    var issues =
        found(List.of(global(Rule.Kind.INCLUDES, TOW, HITCH)), Map.of(TOW, "A", HITCH, "S"));

    assertThat(issues).singleElement().extracting(Issue::severity).isEqualTo(Severity.WARNING);
    assertThat(issues.getFirst().message())
        .isEqualTo(
            "Tow Package adds nothing for Trailer Hitch Receiver on Sport in North America, where"
                + " it is already Standard.");
  }

  @Test
  void anAvailableFeatureThatAStandardOneExcludesIsTheOneTheIssueIsAbout() {
    var rule = global(Rule.Kind.EXCLUDES, TOW, COOLING);

    var standardFirst = found(List.of(rule), Map.of(TOW, "S", COOLING, "A"));
    var availableFirst = found(List.of(rule), Map.of(TOW, "A", COOLING, "S"));

    assertThat(standardFirst.getFirst().featureId()).isEqualTo(COOLING);
    assertThat(standardFirst.getFirst().relatedFeatureIds()).containsExactly(TOW);
    assertThat(standardFirst.getFirst().message())
        .isEqualTo(
            "Heavy-Duty Cooling can never be ordered on Sport in North America: it excludes Tow"
                + " Package, which is Standard there.");
    assertThat(availableFirst.getFirst().featureId()).isEqualTo(TOW);
  }

  @Test
  void anExclusionIsReportedOnceForItsPairAndNotOnceForEachOfItsTwoRules() {
    var forward = global(Rule.Kind.EXCLUDES, TOW, COOLING);
    var mirrored = global(Rule.Kind.EXCLUDES, COOLING, TOW);

    var issues = found(List.of(forward, mirrored), Map.of(TOW, "S", COOLING, "S"));

    assertThat(codes(issues)).containsExactly("EXCLUDED_BOTH_STANDARD");
    assertThat(issues.getFirst().message())
        .isEqualTo(
            "Tow Package and Heavy-Duty Cooling exclude each other, and both are Standard on Sport"
                + " in North America.");
  }

  @Test
  void aFeatureWithoutARowCountsAsNotOfferedAndIsCalledWhatTheLibraryCallsIt() {
    var issues = found(List.of(global(Rule.Kind.INCLUDES, TOW, WIRING)), Map.of(TOW, "S"));

    assertThat(codes(issues)).containsExactly("INCLUDED_NOT_OFFERED");
    assertThat(issues.getFirst().message())
        .isEqualTo(
            "Tow Package includes Trailer Wiring, which is not offered on Sport in North America.");
  }

  @Test
  void aRuleLimitedToARegionIsCheckedInThatRegionAlone() {
    var inEurope =
        new Rule(
            Rule.Origin.GLOBAL,
            "13",
            Rule.Kind.REQUIRES,
            TOW,
            List.of(COOLING),
            true,
            Set.of(),
            false,
            Set.of("EU"),
            null);
    var sameCells = Map.of(TOW, "S", COOLING, "N");

    var issues = found(List.of(inEurope), sameCells, sameCells);

    assertThat(issues).singleElement().extracting(Issue::regionCode).isEqualTo("EU");
  }

  @Test
  void aRequiresWithSeveralTargetsRaisesAnIssueForEachTargetThatFails() {
    var issues =
        found(
            List.of(global(Rule.Kind.REQUIRES, TOW, COOLING, HITCH, WIRING)),
            Map.of(TOW, "S", COOLING, "S", HITCH, "A"));

    assertThat(codes(issues)).containsExactly("REQUIRED_NOT_STANDARD", "REQUIRED_NOT_OFFERED");
    assertThat(issues)
        .extracting(Issue::relatedFeatureIds)
        .containsExactly(List.of(HITCH), List.of(WIRING));
  }
}
