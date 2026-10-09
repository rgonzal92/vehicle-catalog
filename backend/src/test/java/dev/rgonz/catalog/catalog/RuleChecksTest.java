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
import dev.rgonz.catalog.library.RuleKind;
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
 * North America and in Europe and Touring in North America, and has a feature that is Standard
 * everywhere, so that it has no issue of its own. It also checks that a rule of the catalog is
 * checked as a global one is, in the offerings its scopes cover.
 */
class RuleChecksTest {
  private static final long SPORT = 2;
  private static final long TOURING = 3;
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

  private static Rule global(RuleKind kind, long source, Long... targets) {
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
        kind == RuleKind.EXCLUDES ? "a-pair" : null);
  }

  /** What validation finds from rules when the features have the cells given, in North America. */
  private static List<Issue> found(List<Rule> rules, Map<Long, String> inNorthAmerica) {
    return found(rules, inNorthAmerica, Map.of());
  }

  private static List<Issue> found(
      List<Rule> rules, Map<Long, String> inNorthAmerica, Map<Long, String> inEurope) {
    var cells = new ArrayList<Cell>();
    inNorthAmerica.forEach((feature, value) -> add(cells, feature, "NA", value));
    inEurope.forEach((feature, value) -> add(cells, feature, "EU", value));

    return found(rules, List.of(), Set.of(), cells);
  }

  /**
   * What validation finds from the global rules and from the catalog's own, when the catalog has
   * the cells given and the library has retired the features given.
   */
  private static List<Issue> found(
      List<Rule> global, List<Rule> own, Set<Long> retired, List<Cell> given) {
    var cells = new ArrayList<>(given);
    cells.add(new Cell(1, SPORT, "NA", Availability.S));
    cells.add(new Cell(1, SPORT, "EU", Availability.S));
    cells.add(new Cell(1, TOURING, "NA", Availability.S));
    var catalog =
        new CatalogSnapshot(
            41,
            3,
            Status.DRAFT,
            4,
            List.of(new Trim(SPORT, "Sport", 2), new Trim(TOURING, "Touring", 3)),
            List.of(new Region("NA", "North America"), new Region("EU", "Europe")),
            List.of(
                new Offering(SPORT, "NA"), new Offering(SPORT, "EU"), new Offering(TOURING, "NA")),
            List.of(
                new FeatureRow(1, "ENGINE", Kind.FEATURE, "Engine", "POWERTRAIN"),
                new FeatureRow(TOW, "PACKAGE_TOW", Kind.PACKAGE, "Tow Package", "PACKAGES"),
                new FeatureRow(COOLING, "COOLING", Kind.FEATURE, "Heavy-Duty Cooling", "THERMAL"),
                new FeatureRow(HITCH, "HITCH", Kind.FEATURE, "Trailer Hitch Receiver", "CHASSIS")),
            cells,
            own);

    return Validation.issues(catalog, new Library(retired, Set.of(), Set.of(), global, NAMES))
        .stream()
        .filter(issue -> issue.rule() != null)
        .toList();
  }

  /** A rule of the catalog. A scope that is null covers everything. */
  private static Rule own(
      String key, RuleKind kind, Set<Long> trims, Set<String> regions, long source, Long target) {
    return new Rule(
        Rule.Origin.CATALOG,
        key,
        kind,
        source,
        List.of(target),
        trims == null,
        trims == null ? Set.of() : trims,
        regions == null,
        regions == null ? Set.of() : regions,
        kind == RuleKind.EXCLUDES ? "a-pair-of-the-catalog" : null);
  }

  private static void add(List<Cell> cells, long feature, String region, String value) {
    if (!value.equals("N")) {
      cells.add(new Cell(feature, SPORT, region, Availability.valueOf(value)));
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
            List.of(global(RuleKind.REQUIRES, TOW, COOLING)), Map.of(TOW, source, COOLING, target));

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
        found(List.of(global(RuleKind.EXCLUDES, TOW, COOLING)), Map.of(TOW, one, COOLING, other));

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
        found(List.of(global(RuleKind.INCLUDES, TOW, HITCH)), Map.of(TOW, pack, HITCH, target));

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
            List.of(global(RuleKind.REQUIRES_ONE_OF, TOW, COOLING, HITCH)),
            Map.of(TOW, source, COOLING, first, HITCH, second));

    assertThat(codes(issues)).isEqualTo(expected(code));
  }

  @Test
  void anIssueNamesItsOfferingItsCellTheOtherFeatureAndItsRule() {
    var issues =
        found(List.of(global(RuleKind.REQUIRES, TOW, COOLING)), Map.of(TOW, "A", COOLING, "N"));

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
        found(List.of(global(RuleKind.INCLUDES, TOW, HITCH)), Map.of(TOW, "A", HITCH, "S"));

    assertThat(issues).singleElement().extracting(Issue::severity).isEqualTo(Severity.WARNING);
    assertThat(issues.getFirst().message())
        .isEqualTo(
            "Tow Package adds nothing for Trailer Hitch Receiver on Sport in North America, where"
                + " it is already Standard.");
  }

  @Test
  void anAvailableFeatureThatAStandardOneExcludesIsTheOneTheIssueIsAbout() {
    var rule = global(RuleKind.EXCLUDES, TOW, COOLING);

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
    var forward = global(RuleKind.EXCLUDES, TOW, COOLING);
    var mirrored = global(RuleKind.EXCLUDES, COOLING, TOW);

    var issues = found(List.of(forward, mirrored), Map.of(TOW, "S", COOLING, "S"));

    assertThat(codes(issues)).containsExactly("EXCLUDED_BOTH_STANDARD");
    assertThat(issues.getFirst().message())
        .isEqualTo(
            "Tow Package and Heavy-Duty Cooling exclude each other, and both are Standard on Sport"
                + " in North America.");
  }

  @Test
  void aFeatureWithoutARowCountsAsNotOfferedAndIsCalledWhatTheLibraryCallsIt() {
    var issues = found(List.of(global(RuleKind.INCLUDES, TOW, WIRING)), Map.of(TOW, "S"));

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
            RuleKind.REQUIRES,
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
            List.of(global(RuleKind.REQUIRES, TOW, COOLING, HITCH, WIRING)),
            Map.of(TOW, "S", COOLING, "S", HITCH, "A"));

    assertThat(codes(issues)).containsExactly("REQUIRED_NOT_STANDARD", "REQUIRED_NOT_OFFERED");
    assertThat(issues)
        .extracting(Issue::relatedFeatureIds)
        .containsExactly(List.of(HITCH), List.of(WIRING));
  }

  @Test
  void aRuleOfTheCatalogLimitedToARegionIsCheckedInThatRegionAlone() {
    var inEurope = own("in-europe", RuleKind.REQUIRES, null, Set.of("EU"), TOW, COOLING);
    var towEverywhere =
        List.of(
            new Cell(TOW, SPORT, "NA", Availability.A), new Cell(TOW, SPORT, "EU", Availability.A));

    var issues = found(List.of(), List.of(inEurope), Set.of(), towEverywhere);

    assertThat(issues).hasSize(1);
    assertThat(issues.getFirst().code()).isEqualTo(Code.REQUIRED_NOT_OFFERED);
    assertThat(issues.getFirst().regionCode()).isEqualTo("EU");
    assertThat(issues.getFirst().rule()).isEqualTo(new RuleReference("CATALOG", "in-europe"));
  }

  @Test
  void aRuleOfTheCatalogLimitedToATrimIsCheckedOnThatTrimAlone() {
    var onTouring = own("on-touring", RuleKind.REQUIRES, Set.of(TOURING), null, TOW, COOLING);
    var towOnBoth =
        List.of(
            new Cell(TOW, SPORT, "NA", Availability.A),
            new Cell(TOW, TOURING, "NA", Availability.A));

    var issues = found(List.of(), List.of(onTouring), Set.of(), towOnBoth);

    assertThat(issues).hasSize(1);
    assertThat(issues.getFirst().trimId()).isEqualTo(TOURING);
    assertThat(issues.getFirst().message())
        .isEqualTo(
            "Tow Package requires Heavy-Duty Cooling, which is not offered on Touring in North"
                + " America.");
  }

  @Test
  void aRuleOfTheCatalogAndAGlobalRuleThatAreBothBrokenEachRaiseTheirIssue() {
    var own = own("own", RuleKind.REQUIRES, null, null, TOW, COOLING);
    var tow = List.of(new Cell(TOW, SPORT, "NA", Availability.A));

    var issues =
        found(List.of(global(RuleKind.REQUIRES, TOW, COOLING)), List.of(own), Set.of(), tow);

    assertThat(issues.stream().map(Issue::rule))
        .containsExactly(new RuleReference("GLOBAL", "12"), new RuleReference("CATALOG", "own"));
    assertThat(codes(issues)).containsOnly("REQUIRED_NOT_OFFERED");
  }

  @Test
  void anExclusionOfTheCatalogIsReportedOnceForItsPair() {
    var forward = own("forward", RuleKind.EXCLUDES, null, null, TOW, COOLING);
    var mirrored = own("mirrored", RuleKind.EXCLUDES, null, null, COOLING, TOW);
    var bothStandard =
        List.of(
            new Cell(TOW, SPORT, "NA", Availability.S),
            new Cell(COOLING, SPORT, "NA", Availability.S));

    var issues = found(List.of(), List.of(forward, mirrored), Set.of(), bothStandard);

    assertThat(codes(issues)).containsExactly("EXCLUDED_BOTH_STANDARD");
    assertThat(issues.getFirst().rule()).isEqualTo(new RuleReference("CATALOG", "forward"));
  }

  @Test
  void aRuleOfTheCatalogThatNamesARetiredFeatureIsAnErrorUntilTheFeatureIsActiveAgain() {
    var requires = own("requires", RuleKind.REQUIRES, null, null, TOW, COOLING);
    var forward = own("forward", RuleKind.EXCLUDES, null, null, HITCH, COOLING);
    var mirrored = own("mirrored", RuleKind.EXCLUDES, null, null, COOLING, HITCH);
    var rules = List.of(requires, forward, mirrored);
    var kept =
        List.of(
            new Cell(TOW, SPORT, "NA", Availability.A),
            new Cell(COOLING, SPORT, "NA", Availability.A));

    var issues = found(List.of(), rules, Set.of(COOLING), kept);

    assertThat(codes(issues))
        .as("the rule, and the pair once")
        .containsExactly("RULE_FEATURE_RETIRED", "RULE_FEATURE_RETIRED");
    var first = issues.getFirst();
    assertThat(first.severity()).isEqualTo(Severity.ERROR);
    assertThat(first.featureId()).isEqualTo(COOLING);
    assertThat(first.trimId()).isNull();
    assertThat(first.rule()).isEqualTo(new RuleReference("CATALOG", "requires"));
    assertThat(first.message())
        .isEqualTo(
            "Heavy-Duty Cooling is retired, and the rule that Tow Package requires Heavy-Duty"
                + " Cooling names it.");
    assertThat(found(List.of(), rules, Set.of(), kept)).as("once it is active again").isEmpty();
    assertThat(
            found(
                List.of(global(RuleKind.REQUIRES, TOW, COOLING)), List.of(), Set.of(COOLING), kept))
        .as("a global rule is the library's to put right")
        .isEmpty();
  }

  /** Tow requires the hitch, the hitch requires cooling, and tow excludes cooling. */
  private static List<Rule> aChain(RuleKind first) {
    return List.of(
        own("tow-hitch", first, null, null, TOW, HITCH),
        own("hitch-cooling", RuleKind.REQUIRES, null, null, HITCH, COOLING),
        own("tow-cooling", RuleKind.EXCLUDES, null, null, TOW, COOLING),
        own("cooling-tow", RuleKind.EXCLUDES, null, null, COOLING, TOW));
  }

  private static List<Cell> onSportInNorthAmerica(Map<Long, String> values) {
    var cells = new ArrayList<Cell>();
    values.forEach((feature, value) -> add(cells, feature, "NA", value));
    return cells;
  }

  /** The issues the chains raise, each as its code and the feature it is about, if any. */
  private static List<String> chained(List<Issue> issues) {
    return issues.stream()
        .filter(
            issue ->
                issue.code() == Code.STANDARD_SET_CONFLICT
                    || issue.code() == Code.FEATURE_UNSELECTABLE)
        .map(issue -> issue.code() + " " + issue.featureId())
        .toList();
  }

  @Test
  void aConflictAmongWhatIsStandardAndWhatItBringsIsAConflictOfTheOffering() {
    var cells = onSportInNorthAmerica(Map.of(TOW, "S", HITCH, "S", COOLING, "N"));

    var issues = found(List.of(), aChain(RuleKind.REQUIRES), Set.of(), cells);

    assertThat(chained(issues)).containsExactly("STANDARD_SET_CONFLICT null");
    var conflict =
        issues.stream().filter(issue -> issue.code() == Code.STANDARD_SET_CONFLICT).findFirst();
    assertThat(conflict).isPresent();
    assertThat(conflict.get().trimId()).isEqualTo(SPORT);
    assertThat(conflict.get().regionCode()).isEqualTo("NA");
    assertThat(conflict.get().severity()).isEqualTo(Severity.ERROR);
    assertThat(conflict.get().relatedFeatureIds()).containsExactly(TOW, COOLING);
    assertThat(conflict.get().rule()).isEqualTo(new RuleReference("CATALOG", "tow-cooling"));
    assertThat(conflict.get().message())
        .isEqualTo(
            "What is Standard on Sport in North America cannot be built. Trailer Hitch Receiver"
                + " brings Heavy-Duty Cooling. Tow Package and Heavy-Duty Cooling exclude each"
                + " other.");
  }

  @Test
  void anAvailableFeatureWhoseChainLeadsToWhatItExcludesCanNeverBeOrdered() {
    var cells = onSportInNorthAmerica(Map.of(TOW, "A", HITCH, "A", COOLING, "A"));

    var issues = found(List.of(), aChain(RuleKind.REQUIRES), Set.of(), cells);

    assertThat(codes(issues))
        .as("no rule is broken by itself")
        .containsExactly("FEATURE_UNSELECTABLE");
    var unselectable = issues.getFirst();
    assertThat(unselectable.featureId()).as("the cell of the feature").isEqualTo(TOW);
    assertThat(unselectable.trimId()).isEqualTo(SPORT);
    assertThat(unselectable.message())
        .isEqualTo(
            "Tow Package can never be ordered on Sport in North America. Tow Package brings"
                + " Trailer Hitch Receiver, which brings Heavy-Duty Cooling. Tow Package and"
                + " Heavy-Duty Cooling exclude each other.");
  }

  @Test
  void aChainThroughAnIncludesIsFollowedAsOneThroughARequiresIs() {
    var cells = onSportInNorthAmerica(Map.of(TOW, "A", HITCH, "A", COOLING, "A"));

    var issues = found(List.of(), aChain(RuleKind.INCLUDES), Set.of(), cells);

    assertThat(chained(issues)).containsExactly("FEATURE_UNSELECTABLE " + TOW);
  }

  @Test
  void aConflictThatADirectCheckReportsIsNotReportedAgainByTheChains() {
    var bothStandard = onSportInNorthAmerica(Map.of(TOW, "S", HITCH, "S", COOLING, "S"));
    var byStandard = onSportInNorthAmerica(Map.of(TOW, "S", HITCH, "S", COOLING, "A"));

    assertThat(codes(found(List.of(), aChain(RuleKind.REQUIRES), Set.of(), bothStandard)))
        .containsExactly("EXCLUDED_BOTH_STANDARD");
    assertThat(codes(found(List.of(), aChain(RuleKind.REQUIRES), Set.of(), byStandard)))
        .containsExactlyInAnyOrder("REQUIRED_NOT_STANDARD", "EXCLUDED_BY_STANDARD");
  }

  @Test
  void aConflictWithinTheStandardSetIsReportedOnceHoweverManyFeaturesAreAvailable() {
    var cells = onSportInNorthAmerica(Map.of(TOW, "S", HITCH, "A", COOLING, "N", WIRING, "A"));

    var issues = found(List.of(), aChain(RuleKind.REQUIRES), Set.of(), cells);

    assertThat(chained(issues)).containsExactly("STANDARD_SET_CONFLICT null");
  }

  @Test
  void anAvailableFeatureThatExcludesWhatTheStandardSetBringsCanNeverBeOrdered() {
    // The engine is Standard and brings the hitch, which is not offered; cooling excludes the
    // hitch.
    var rules =
        List.of(
            own("engine-hitch", RuleKind.REQUIRES, null, null, 1, HITCH),
            own("cooling-hitch", RuleKind.EXCLUDES, null, null, COOLING, HITCH),
            own("hitch-cooling", RuleKind.EXCLUDES, null, null, HITCH, COOLING));
    var cells = onSportInNorthAmerica(Map.of(COOLING, "A"));

    var issues = found(List.of(), rules, Set.of(), cells);

    assertThat(chained(issues)).contains("FEATURE_UNSELECTABLE " + COOLING);
    assertThat(
            issues.stream()
                .filter(issue -> issue.code() == Code.FEATURE_UNSELECTABLE)
                .map(Issue::message))
        .contains(
            "Heavy-Duty Cooling can never be ordered on Sport in North America. Engine brings"
                + " Trailer Hitch Receiver. Heavy-Duty Cooling and Trailer Hitch Receiver exclude"
                + " each other.");
  }

  @Test
  void aChainThatIsWholeOnlyAcrossTwoRegionsRaisesNothingWherePartOfItIsNotInEffect() {
    var rules =
        List.of(
            own("tow-hitch", RuleKind.REQUIRES, null, Set.of("NA"), TOW, HITCH),
            own("hitch-cooling", RuleKind.REQUIRES, null, Set.of("EU"), HITCH, COOLING),
            own("tow-cooling", RuleKind.EXCLUDES, null, null, TOW, COOLING),
            own("cooling-tow", RuleKind.EXCLUDES, null, null, COOLING, TOW));
    var cells = new ArrayList<Cell>();
    for (var region : List.of("NA", "EU")) {
      add(cells, TOW, region, "A");
      add(cells, HITCH, region, "A");
      add(cells, COOLING, region, "A");
    }

    assertThat(found(List.of(), rules, Set.of(), cells)).isEmpty();
    assertThat(codes(found(List.of(), aChain(RuleKind.REQUIRES), Set.of(), cells)))
        .as("the same cells under a chain that is whole in each region")
        .containsExactly("FEATURE_UNSELECTABLE", "FEATURE_UNSELECTABLE");
  }

  @Test
  void aRequiresOneOfIsNotFollowed() {
    var rules =
        List.of(
            new Rule(
                Rule.Origin.CATALOG,
                "one-of",
                RuleKind.REQUIRES_ONE_OF,
                TOW,
                List.of(HITCH, COOLING),
                true,
                Set.of(),
                true,
                Set.of(),
                null),
            own("tow-cooling", RuleKind.EXCLUDES, null, null, TOW, COOLING),
            own("cooling-tow", RuleKind.EXCLUDES, null, null, COOLING, TOW),
            own("hitch-wiring", RuleKind.REQUIRES, null, null, HITCH, WIRING));
    var cells = onSportInNorthAmerica(Map.of(TOW, "A", HITCH, "A", COOLING, "A"));

    assertThat(chained(found(List.of(), rules, Set.of(), cells))).isEmpty();
  }
}
