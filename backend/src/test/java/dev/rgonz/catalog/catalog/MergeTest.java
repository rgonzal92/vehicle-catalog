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
import dev.rgonz.catalog.catalog.Merge.Conflict;
import dev.rgonz.catalog.catalog.Merge.Side;
import dev.rgonz.catalog.library.RuleKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks what a merge of three catalogs comes to, for every row of its table and every kind of
 * thing it merges. Each test starts from a base that sells Base and Sport in North America and Base
 * in Europe, with a tow package, a hitch, cooling, and a roof; a rule by which the tow package
 * requires the hitch; and an exclusion between the roof and cooling. Mine and theirs are copies of
 * it that each side changed.
 */
class MergeTest {
  private static final Trim BASE = new Trim(1, "Base", 1);
  private static final Trim SPORT = new Trim(2, "Sport", 2);
  private static final Trim TOURING = new Trim(3, "Touring", 3);
  private static final Region NORTH_AMERICA = new Region("NA", "North America");
  private static final Region EUROPE = new Region("EU", "Europe");
  private static final Region ASIA = new Region("ASIA", "Asia");
  private static final FeatureRow TOW =
      new FeatureRow(8, "PACKAGE_TOW", Kind.PACKAGE, "Tow Package", "PACKAGES");
  private static final FeatureRow HITCH =
      new FeatureRow(9, "HITCH", Kind.FEATURE, "Trailer Hitch Receiver", "CHASSIS");
  private static final FeatureRow COOLING =
      new FeatureRow(10, "COOLING", Kind.FEATURE, "Heavy-Duty Cooling", "THERMAL");
  private static final FeatureRow ROOF =
      new FeatureRow(11, "ROOF", Kind.FEATURE, "Panoramic Roof", "EXTERIOR");
  private static final FeatureRow RACK =
      new FeatureRow(12, "RACK", Kind.FEATURE, "Roof Rack", "EXTERIOR");

  /**
   * A catalog's contents, to be changed before they become a snapshot. What is removed takes what
   * is beneath it along, as the editor does.
   */
  private static final class Contents {
    final List<Trim> trims = new ArrayList<>(List.of(BASE, SPORT));
    final List<Region> regions = new ArrayList<>(List.of(NORTH_AMERICA, EUROPE));
    final List<Offering> offerings =
        new ArrayList<>(
            List.of(new Offering(1, "NA"), new Offering(2, "NA"), new Offering(1, "EU")));
    final List<FeatureRow> featureRows = new ArrayList<>(List.of(TOW, HITCH, COOLING, ROOF));
    final List<Cell> cells =
        new ArrayList<>(
            List.of(
                new Cell(8, 2, "NA", Availability.A),
                new Cell(9, 2, "NA", Availability.S),
                new Cell(10, 1, "EU", Availability.S)));
    final List<Rule> rules = new ArrayList<>();

    Contents() {
      rules.add(rule("needs-hitch", RuleKind.REQUIRES, TOW, List.of(HITCH), null, null, null));
      rules.addAll(exclusion("a-pair", ROOF, COOLING, null, null));
    }

    void sell(Trim trim, Region region) {
      offerings.add(new Offering(trim.id(), region.code()));
    }

    void set(FeatureRow feature, Trim trim, Region region, Availability availability) {
      cells.removeIf(
          cell ->
              cell.featureId() == feature.id()
                  && cell.trimId() == trim.id()
                  && cell.regionCode().equals(region.code()));
      if (availability != Availability.N) {
        cells.add(new Cell(feature.id(), trim.id(), region.code(), availability));
      }
    }

    void removeTrim(Trim trim) {
      trims.remove(trim);
      offerings.removeIf(offering -> offering.trimId() == trim.id());
      cells.removeIf(cell -> cell.trimId() == trim.id());
      rules.replaceAll(
          rule -> scoped(rule, without(rule.trimIds(), trim.id()), rule.regionCodes()));
      rules.removeIf(rule -> !rule.allTrims() && rule.trimIds().isEmpty());
    }

    void removeRegion(Region region) {
      regions.remove(region);
      offerings.removeIf(offering -> offering.regionCode().equals(region.code()));
      cells.removeIf(cell -> cell.regionCode().equals(region.code()));
      rules.replaceAll(
          rule -> scoped(rule, rule.trimIds(), without(rule.regionCodes(), region.code())));
      rules.removeIf(rule -> !rule.allRegions() && rule.regionCodes().isEmpty());
    }

    void stopSelling(Trim trim, Region region) {
      offerings.remove(new Offering(trim.id(), region.code()));
      cells.removeIf(cell -> cell.trimId() == trim.id() && cell.regionCode().equals(region.code()));
    }

    void removeFeature(FeatureRow feature) {
      featureRows.remove(feature);
      cells.removeIf(cell -> cell.featureId() == feature.id());
      rules.removeIf(
          rule ->
              rule.sourceFeatureId() == feature.id()
                  || rule.targetFeatureIds().contains(feature.id()));
    }

    /** Replaces the rule with the same key. */
    void change(Rule changed) {
      rules.replaceAll(rule -> rule.key().equals(changed.key()) ? changed : rule);
    }

    void removeRule(String key) {
      rules.removeIf(rule -> rule.key().equals(key));
    }

    void removeExclusion(String pairKey) {
      rules.removeIf(rule -> pairKey.equals(rule.pairKey()));
    }

    CatalogSnapshot snapshot() {
      return new CatalogSnapshot(
          41, 3, Status.DRAFT, 0, trims, regions, offerings, featureRows, cells, rules);
    }
  }

  private static <T> Set<T> without(Set<T> all, T one) {
    var rest = new LinkedHashSet<>(all);
    rest.remove(one);
    return rest;
  }

  private static Rule scoped(Rule rule, Set<Long> trims, Set<String> regions) {
    return new Rule(
        rule.origin(),
        rule.key(),
        rule.kind(),
        rule.sourceFeatureId(),
        rule.targetFeatureIds(),
        rule.allTrims(),
        trims,
        rule.allRegions(),
        regions,
        rule.pairKey());
  }

  private static CatalogSnapshot catalog(Consumer<Contents> changed) {
    var contents = new Contents();
    changed.accept(contents);
    return contents.snapshot();
  }

  private static CatalogSnapshot catalog() {
    return catalog(contents -> {});
  }

  /** A rule of the catalog. A scope that is null covers everything. */
  private static Rule rule(
      String key,
      RuleKind kind,
      FeatureRow source,
      List<FeatureRow> targets,
      Set<Trim> trims,
      Set<Region> regions,
      String pairKey) {
    return new Rule(
        Rule.Origin.CATALOG,
        key,
        kind,
        source.id(),
        targets.stream().map(FeatureRow::id).toList(),
        trims == null,
        trims == null ? Set.of() : trims.stream().map(Trim::id).collect(Collectors.toSet()),
        regions == null,
        regions == null ? Set.of() : regions.stream().map(Region::code).collect(Collectors.toSet()),
        pairKey);
  }

  private static Rule requires(
      String key, FeatureRow source, FeatureRow target, Set<Trim> trims, Set<Region> regions) {
    return rule(key, RuleKind.REQUIRES, source, List.of(target), trims, regions, null);
  }

  private static List<Rule> exclusion(
      String pairKey, FeatureRow one, FeatureRow other, Set<Trim> trims, Set<Region> regions) {
    return List.of(
        rule(pairKey + "-forward", RuleKind.EXCLUDES, one, List.of(other), trims, regions, pairKey),
        rule(
            pairKey + "-mirrored",
            RuleKind.EXCLUDES,
            other,
            List.of(one),
            trims,
            regions,
            pairKey));
  }

  /** What a catalog has, whatever the order and the labels. */
  private record Had(
      Set<Long> trims,
      Set<String> regions,
      Set<Offering> offerings,
      Set<Long> featureRows,
      Set<Cell> cells,
      Set<Rule> rules) {}

  /** What a catalog has, once it is sure that it has nothing twice and no half of an exclusion. */
  private static Had had(CatalogSnapshot catalog) {
    var had =
        new Had(
            catalog.trims().stream().map(Trim::id).collect(Collectors.toSet()),
            catalog.regions().stream().map(Region::code).collect(Collectors.toSet()),
            Set.copyOf(catalog.offerings()),
            catalog.featureRows().stream().map(FeatureRow::id).collect(Collectors.toSet()),
            Set.copyOf(catalog.cells()),
            Set.copyOf(catalog.rules()));
    assertThat(had.trims()).as("each trim once").hasSameSizeAs(catalog.trims());
    assertThat(had.regions()).as("each region once").hasSameSizeAs(catalog.regions());
    assertThat(had.featureRows()).as("each feature row once").hasSameSizeAs(catalog.featureRows());
    assertThat(
            catalog.rules().stream()
                .filter(rule -> rule.pairKey() != null)
                .collect(Collectors.groupingBy(Rule::pairKey, Collectors.counting()))
                .values())
        .as("the rules of each exclusion")
        .allMatch(rules -> rules == 2);
    return had;
  }

  private static List<String> ids(Merge.Result merge) {
    return merge.conflicts().stream().map(Conflict::id).toList();
  }

  private static Named<Consumer<Contents>> change(String name, Consumer<Contents> change) {
    return Named.of(name, change);
  }

  /** One change of every kind of thing a merge merges. */
  static Stream<Named<Consumer<Contents>>> changes() {
    return Stream.of(
        change("a trim added", contents -> contents.trims.add(TOURING)),
        change("a trim removed", contents -> contents.removeTrim(SPORT)),
        change("a region added", contents -> contents.regions.add(ASIA)),
        change("a region removed", contents -> contents.removeRegion(EUROPE)),
        change("an offering added", contents -> contents.sell(SPORT, EUROPE)),
        change("an offering removed", contents -> contents.stopSelling(BASE, EUROPE)),
        change("a feature row added", contents -> contents.featureRows.add(RACK)),
        change("a feature row removed", contents -> contents.removeFeature(HITCH)),
        change("a cell filled", contents -> contents.set(TOW, BASE, NORTH_AMERICA, Availability.S)),
        change(
            "a cell changed", contents -> contents.set(TOW, SPORT, NORTH_AMERICA, Availability.S)),
        change(
            "a cell cleared",
            contents -> contents.set(HITCH, SPORT, NORTH_AMERICA, Availability.N)),
        change(
            "a rule added",
            contents -> contents.rules.add(requires("needs-cooling", TOW, COOLING, null, null))),
        change("a rule removed", contents -> contents.removeRule("needs-hitch")),
        change(
            "a rule changed",
            contents -> contents.change(requires("needs-hitch", TOW, HITCH, Set.of(SPORT), null))),
        change(
            "an exclusion added",
            contents -> contents.rules.addAll(exclusion("another", TOW, ROOF, null, null))),
        change("an exclusion removed", contents -> contents.removeExclusion("a-pair")),
        change(
            "an exclusion changed",
            contents -> {
              contents.removeExclusion("a-pair");
              contents.rules.addAll(exclusion("a-pair", ROOF, COOLING, null, Set.of(EUROPE)));
            }));
  }

  @Test
  void whatNeitherSideChangedStaysAsItIs() {
    var merge = Merge.of(catalog(), catalog(), catalog(), Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(had(merge.merged())).isEqualTo(had(catalog()));
  }

  @ParameterizedTest
  @MethodSource("changes")
  void whatOnlyTheirsChangedIsTaken(Consumer<Contents> change) {
    var theirs = catalog(change);

    var merge = Merge.of(catalog(), catalog(), theirs, Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(had(merge.merged())).isEqualTo(had(theirs));
  }

  @ParameterizedTest
  @MethodSource("changes")
  void whatOnlyMineChangedIsKept(Consumer<Contents> change) {
    var mine = catalog(change);

    var merge = Merge.of(catalog(), mine, catalog(), Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(had(merge.merged())).isEqualTo(had(mine));
  }

  @ParameterizedTest
  @MethodSource("changes")
  void whatBothChangedAlikeIsKeptOnceWithoutAConflict(Consumer<Contents> change) {
    var merge = Merge.of(catalog(), catalog(change), catalog(change), Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(had(merge.merged())).isEqualTo(had(catalog(change)));
  }

  @Test
  void aCellThatBothChangedDifferentlyIsAConflictThatIsLeftAsMineUntilItIsSettled() {
    var mine = catalog(contents -> contents.set(TOW, SPORT, NORTH_AMERICA, Availability.S));
    var theirs = catalog(contents -> contents.set(TOW, SPORT, NORTH_AMERICA, Availability.N));

    var open = Merge.of(catalog(), mine, theirs, Map.of());
    var forMine = Merge.of(catalog(), mine, theirs, Map.of("cell:8:2:NA", Side.MINE));
    var forTheirs = Merge.of(catalog(), mine, theirs, Map.of("cell:8:2:NA", Side.THEIRS));

    assertThat(open.conflicts())
        .containsExactly(
            new Conflict(
                "cell:8:2:NA",
                Merge.Kind.CELL,
                "Tow Package, Sport in North America",
                "Available",
                "Standard",
                "Not offered",
                null));
    assertThat(open.unsettled()).hasSize(1);
    assertThat(had(open.merged())).isEqualTo(had(mine));
    assertThat(forMine.conflicts()).extracting(Conflict::resolution).containsExactly(Side.MINE);
    assertThat(forMine.unsettled()).isEmpty();
    assertThat(had(forMine.merged())).isEqualTo(had(mine));
    assertThat(had(forTheirs.merged())).isEqualTo(had(theirs));
  }

  @Test
  void aRuleThatBothChangedDifferentlyIsAConflictWithWhatEachSideSays() {
    var mine =
        catalog(
            contents ->
                contents.change(
                    rule(
                        "needs-hitch",
                        RuleKind.REQUIRES,
                        TOW,
                        List.of(HITCH, COOLING),
                        null,
                        null,
                        null)));
    var theirs =
        catalog(
            contents -> contents.change(requires("needs-hitch", TOW, HITCH, Set.of(SPORT), null)));
    var removed = catalog(contents -> contents.removeRule("needs-hitch"));

    var open = Merge.of(catalog(), mine, theirs, Map.of());
    var forTheirs = Merge.of(catalog(), mine, theirs, Map.of("rule:needs-hitch", Side.THEIRS));
    var againstARemoval = Merge.of(catalog(), mine, removed, Map.of());

    assertThat(open.conflicts())
        .containsExactly(
            new Conflict(
                "rule:needs-hitch",
                Merge.Kind.RULE,
                "Tow Package",
                "Tow Package requires Trailer Hitch Receiver",
                "Tow Package requires Trailer Hitch Receiver, Heavy-Duty Cooling",
                "Tow Package requires Trailer Hitch Receiver (on Sport)",
                null));
    assertThat(had(open.merged())).isEqualTo(had(mine));
    assertThat(had(forTheirs.merged())).isEqualTo(had(theirs));
    assertThat(againstARemoval.conflicts())
        .extracting(Conflict::theirs)
        .containsExactly("No such rule");
    assertThat(
            had(
                Merge.of(catalog(), mine, removed, Map.of("rule:needs-hitch", Side.THEIRS))
                    .merged()))
        .isEqualTo(had(removed));
  }

  @Test
  void anExclusionThatBothChangedDifferentlyIsOneConflictAndIsSettledAsAWholePair() {
    var mine =
        catalog(
            contents -> {
              contents.removeExclusion("a-pair");
              contents.rules.addAll(exclusion("a-pair", ROOF, COOLING, Set.of(SPORT), null));
            });
    var theirs =
        catalog(
            contents -> {
              contents.removeExclusion("a-pair");
              contents.rules.addAll(exclusion("a-pair", ROOF, COOLING, null, Set.of(EUROPE)));
            });

    var open = Merge.of(catalog(), mine, theirs, Map.of());
    var forTheirs = Merge.of(catalog(), mine, theirs, Map.of("rule:pair a-pair", Side.THEIRS));

    assertThat(open.conflicts())
        .containsExactly(
            new Conflict(
                "rule:pair a-pair",
                Merge.Kind.RULE,
                "Panoramic Roof",
                "Panoramic Roof excludes Heavy-Duty Cooling",
                "Panoramic Roof excludes Heavy-Duty Cooling (on Sport)",
                "Panoramic Roof excludes Heavy-Duty Cooling (in Europe)",
                null));
    assertThat(had(open.merged())).isEqualTo(had(mine));
    assertThat(had(forTheirs.merged())).isEqualTo(had(theirs));
  }

  @Test
  void aTrimThatOneSideRemovedWhileTheOtherChangedACellOfItIsOneConflictOnTheTrim() {
    var mine = catalog(contents -> contents.set(HITCH, SPORT, NORTH_AMERICA, Availability.A));
    var theirs = catalog(contents -> contents.removeTrim(SPORT));

    var open = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(open.conflicts())
        .as("and none on the cells beneath it")
        .containsExactly(
            new Conflict(
                "trim:2",
                Merge.Kind.TRIM,
                "Sport",
                "In the catalog",
                "Kept, with 1 cell changed beneath it",
                "Removed",
                null));
    assertThat(had(open.merged())).isEqualTo(had(mine));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("trim:2", Side.MINE)).merged()))
        .isEqualTo(had(mine));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("trim:2", Side.THEIRS)).merged()))
        .isEqualTo(had(theirs));
  }

  @Test
  void aRegionAddedBySideAndACellChangedInAnotherRegionByTheOtherMergeWithoutAConflict() {
    var mine =
        catalog(
            contents -> {
              contents.regions.add(ASIA);
              contents.sell(BASE, ASIA);
              contents.set(TOW, BASE, ASIA, Availability.S);
            });
    var theirs = catalog(contents -> contents.set(COOLING, BASE, EUROPE, Availability.A));

    var merge = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(had(merge.merged()))
        .isEqualTo(
            had(
                catalog(
                    contents -> {
                      contents.regions.add(ASIA);
                      contents.sell(BASE, ASIA);
                      contents.set(TOW, BASE, ASIA, Availability.S);
                      contents.set(COOLING, BASE, EUROPE, Availability.A);
                    })));
  }

  @Test
  void aRegionThatOneSideRemovedWhileTheOtherChangedACellInItIsAConflictOnTheRegion() {
    var mine = catalog(contents -> contents.removeRegion(EUROPE));
    var theirs = catalog(contents -> contents.set(COOLING, BASE, EUROPE, Availability.A));

    var open = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(open.conflicts())
        .containsExactly(
            new Conflict(
                "region:EU",
                Merge.Kind.REGION,
                "Europe",
                "In the catalog",
                "Removed",
                "Kept, with 1 cell changed beneath it",
                null));
    assertThat(had(open.merged())).as("left as mine").isEqualTo(had(mine));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("region:EU", Side.THEIRS)).merged()))
        .isEqualTo(had(theirs));
  }

  @Test
  void aSettledRemovalTakesTheCellsAndRulesBeneathItAlongAndAKeptOneKeepsThem() {
    var mine =
        catalog(
            contents -> {
              contents.set(COOLING, BASE, EUROPE, Availability.A);
              contents.rules.add(requires("in-europe", TOW, COOLING, null, Set.of(EUROPE)));
            });
    var theirs = catalog(contents -> contents.removeRegion(EUROPE));

    var open = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(open.conflicts())
        .extracting(Conflict::id, Conflict::mine)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "region:EU", "Kept, with 1 cell, 1 rule changed beneath it"));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("region:EU", Side.THEIRS)).merged()))
        .as("removed")
        .isEqualTo(had(theirs));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("region:EU", Side.MINE)).merged()))
        .as("kept")
        .isEqualTo(had(mine));
  }

  @Test
  void aRuleThatTheRemovingSideNarrowedCoversTheRegionAgainWhenTheRegionIsKept() {
    Consumer<Contents> everywhere =
        contents ->
            contents.rules.add(
                requires("in-both", TOW, COOLING, null, Set.of(EUROPE, NORTH_AMERICA)));
    var base = catalog(everywhere);
    var mine = catalog(everywhere.andThen(contents -> contents.removeRegion(EUROPE)));
    var theirs =
        catalog(
            everywhere.andThen(contents -> contents.set(COOLING, BASE, EUROPE, Availability.A)));

    assertThat(had(Merge.of(base, mine, theirs, Map.of("region:EU", Side.THEIRS)).merged()))
        .as("kept")
        .isEqualTo(had(theirs));
    assertThat(had(Merge.of(base, mine, theirs, Map.of("region:EU", Side.MINE)).merged()))
        .as("removed")
        .isEqualTo(had(mine));
  }

  @Test
  void anOfferingThatOneSideRemovedWhileTheOtherChangedACellOfItIsAConflictOnTheOffering() {
    var mine = catalog(contents -> contents.set(TOW, BASE, EUROPE, Availability.S));
    var theirs = catalog(contents -> contents.stopSelling(BASE, EUROPE));

    var open = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(open.conflicts())
        .containsExactly(
            new Conflict(
                "offering:1:EU",
                Merge.Kind.OFFERING,
                "Base in Europe",
                "In the catalog",
                "Kept, with 1 cell changed beneath it",
                "Removed",
                null));
    assertThat(had(open.merged())).isEqualTo(had(mine));
    assertThat(
            had(Merge.of(catalog(), mine, theirs, Map.of("offering:1:EU", Side.THEIRS)).merged()))
        .isEqualTo(had(theirs));
  }

  @Test
  void aFeatureRowThatOneSideRemovedWhileTheOtherChangedACellOfItIsAConflictOnTheRow() {
    var mine = catalog(contents -> contents.set(HITCH, BASE, NORTH_AMERICA, Availability.A));
    var theirs = catalog(contents -> contents.removeFeature(HITCH));

    var open = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(open.conflicts())
        .as("and none on the rule that names it")
        .containsExactly(
            new Conflict(
                "feature:9",
                Merge.Kind.FEATURE_ROW,
                "Trailer Hitch Receiver",
                "In the catalog",
                "Kept, with 1 cell changed beneath it",
                "Removed",
                null));
    assertThat(had(open.merged())).isEqualTo(had(mine));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("feature:9", Side.MINE)).merged()))
        .isEqualTo(had(mine));
    assertThat(had(Merge.of(catalog(), mine, theirs, Map.of("feature:9", Side.THEIRS)).merged()))
        .isEqualTo(had(theirs));
  }

  @Test
  void aTrimThatBothSidesAddedIsKeptOnceAndItsCellsMergeOneByOne() {
    var mine =
        catalog(
            contents -> {
              contents.trims.add(TOURING);
              contents.sell(TOURING, NORTH_AMERICA);
              contents.set(TOW, TOURING, NORTH_AMERICA, Availability.S);
              contents.set(HITCH, TOURING, NORTH_AMERICA, Availability.A);
            });
    var theirs =
        catalog(
            contents -> {
              contents.trims.add(TOURING);
              contents.sell(TOURING, NORTH_AMERICA);
              contents.set(TOW, TOURING, NORTH_AMERICA, Availability.A);
              contents.set(COOLING, TOURING, NORTH_AMERICA, Availability.S);
            });

    var merge = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(merge.conflicts())
        .containsExactly(
            new Conflict(
                "cell:8:3:NA",
                Merge.Kind.CELL,
                "Tow Package, Touring in North America",
                "Not offered",
                "Standard",
                "Available",
                null));
    assertThat(merge.merged().trims()).containsExactly(BASE, SPORT, TOURING);
    assertThat(had(merge.merged()).cells())
        .contains(
            new Cell(8, 3, "NA", Availability.S),
            new Cell(9, 3, "NA", Availability.A),
            new Cell(10, 3, "NA", Availability.S));
  }

  @Test
  void aRuleThatBothSidesAddedSayingTheSameIsKeptOnceWithTheKeyItHasInTheirs() {
    var mine =
        catalog(
            contents -> {
              contents.rules.add(requires("my-key", TOW, COOLING, null, null));
              contents.rules.addAll(exclusion("my-pair", TOW, ROOF, null, null));
            });
    var theirs =
        catalog(
            contents -> {
              contents.rules.add(requires("their-key", TOW, COOLING, null, null));
              // The same exclusion, said from its other feature first.
              contents.rules.addAll(exclusion("their-pair", ROOF, TOW, null, null));
            });

    var merge = Merge.of(catalog(), mine, theirs, Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(had(merge.merged())).isEqualTo(had(theirs));
  }

  @Test
  void aRuleThatNamesATrimARegionOrAFeatureTheOtherSideRemovedIsAConflictOnWhatWasRemoved() {
    var onSport =
        catalog(
            contents ->
                contents.rules.add(requires("on-sport", TOW, COOLING, Set.of(SPORT), null)));
    var inEurope =
        catalog(
            contents ->
                contents.rules.add(requires("in-europe", TOW, COOLING, null, Set.of(EUROPE))));
    var ofCooling =
        catalog(contents -> contents.rules.add(requires("of-cooling", TOW, COOLING, null, null)));
    var withoutSport = catalog(contents -> contents.removeTrim(SPORT));
    var withoutEurope = catalog(contents -> contents.removeRegion(EUROPE));
    var withoutCooling = catalog(contents -> contents.removeFeature(COOLING));

    assertThat(Merge.of(catalog(), onSport, withoutSport, Map.of()).conflicts())
        .extracting(Conflict::id, Conflict::mine)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("trim:2", "Kept, with 1 rule changed beneath it"));
    assertThat(ids(Merge.of(catalog(), inEurope, withoutEurope, Map.of())))
        .containsExactly("region:EU");
    assertThat(ids(Merge.of(catalog(), ofCooling, withoutCooling, Map.of())))
        .containsExactly("feature:10");
    // The other way round, the rule is theirs and the removal is mine.
    assertThat(ids(Merge.of(catalog(), withoutCooling, ofCooling, Map.of())))
        .containsExactly("feature:10");

    assertThat(
            had(Merge.of(catalog(), onSport, withoutSport, Map.of("trim:2", Side.THEIRS)).merged()))
        .as("the trim removed, and the rule with it")
        .isEqualTo(had(withoutSport));
    assertThat(
            had(
                Merge.of(catalog(), ofCooling, withoutCooling, Map.of("feature:10", Side.THEIRS))
                    .merged()))
        .as("the feature row removed, and every rule that names it")
        .isEqualTo(had(withoutCooling));
    assertThat(
            had(
                Merge.of(catalog(), ofCooling, withoutCooling, Map.of("feature:10", Side.MINE))
                    .merged()))
        .as("the feature row kept, with the rules that name it")
        .isEqualTo(had(ofCooling));
  }

  @Test
  void overAnEmptyBaseWhatEitherSideHasIsKeptAndOnlyWhatTheyHaveDifferentlyIsAConflict() {
    var mine = catalog();
    // A catalog that never had Sport or the hitch, and has cooling otherwise.
    var theirs =
        catalog(
            contents -> {
              contents.removeTrim(SPORT);
              contents.removeFeature(HITCH);
              contents.set(COOLING, BASE, EUROPE, Availability.A);
              contents.featureRows.add(RACK);
              contents.set(RACK, BASE, NORTH_AMERICA, Availability.S);
            });

    var merge = Merge.of(CatalogSnapshot.empty(), mine, theirs, Map.of());

    assertThat(ids(merge)).containsExactly("cell:10:1:EU");
    assertThat(had(merge.merged()))
        .isEqualTo(
            had(
                catalog(
                    contents -> {
                      contents.featureRows.add(RACK);
                      contents.set(RACK, BASE, NORTH_AMERICA, Availability.S);
                    })));
  }

  @Test
  void aLabelThatTheLibraryChangedIsNoChangeAndTheMergedCatalogShowsMine() {
    var theirs = catalog(contents -> contents.trims.set(1, new Trim(2, "Sport Plus", 5)));

    var merge = Merge.of(catalog(), catalog(), theirs, Map.of());

    assertThat(merge.conflicts()).isEmpty();
    assertThat(merge.merged().trims()).containsExactly(BASE, SPORT);
  }
}
