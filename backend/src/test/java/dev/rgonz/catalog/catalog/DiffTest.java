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
import dev.rgonz.catalog.catalog.Diff.CellChanged;
import dev.rgonz.catalog.catalog.Diff.Changes;
import dev.rgonz.catalog.catalog.Diff.OfferingNamed;
import dev.rgonz.catalog.catalog.Diff.RuleChanged;
import dev.rgonz.catalog.catalog.Diff.RuleSaid;
import dev.rgonz.catalog.library.RuleKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Checks what a diff lists for every kind of change from one catalog to another. Each test starts
 * from a catalog that sells Base and Sport in North America and Base in Europe, with a tow package,
 * a hitch, and cooling, and changes a copy of it.
 */
class DiffTest {
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

  /** A catalog's contents, to be changed before they become a snapshot. */
  private static final class Contents {
    final List<Trim> trims = new ArrayList<>(List.of(BASE, SPORT));
    final List<Region> regions = new ArrayList<>(List.of(NORTH_AMERICA, EUROPE));
    final List<Offering> offerings =
        new ArrayList<>(
            List.of(new Offering(1, "NA"), new Offering(2, "NA"), new Offering(1, "EU")));
    final List<FeatureRow> featureRows = new ArrayList<>(List.of(TOW, HITCH, COOLING));
    final List<Cell> cells =
        new ArrayList<>(
            List.of(
                new Cell(8, 2, "NA", Availability.A),
                new Cell(9, 2, "NA", Availability.S),
                new Cell(10, 1, "EU", Availability.S)));
    final List<Rule> rules = new ArrayList<>();

    CatalogSnapshot snapshot() {
      return new CatalogSnapshot(
          41, 3, Status.APPROVED, 0, trims, regions, offerings, featureRows, cells, rules);
    }
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
      Set<Long> trims,
      Set<String> regions,
      String pairKey) {
    return new Rule(
        Rule.Origin.CATALOG,
        key,
        kind,
        source.id(),
        targets.stream().map(FeatureRow::id).toList(),
        trims == null,
        trims == null ? Set.of() : new LinkedHashSet<>(trims),
        regions == null,
        regions == null ? Set.of() : new LinkedHashSet<>(regions),
        pairKey);
  }

  private static Rule requires(FeatureRow source, FeatureRow... targets) {
    return rule("a-rule", RuleKind.REQUIRES, source, List.of(targets), null, null, null);
  }

  private static List<Rule> exclusion(FeatureRow one, FeatureRow other, Set<Long> trims) {
    return List.of(
        rule("forward", RuleKind.EXCLUDES, one, List.of(other), trims, null, "a-pair"),
        rule("mirrored", RuleKind.EXCLUDES, other, List.of(one), trims, null, "a-pair"));
  }

  private static boolean nothing(Changes changes) {
    return changes.trimsAdded().isEmpty()
        && changes.trimsRemoved().isEmpty()
        && changes.regionsAdded().isEmpty()
        && changes.regionsRemoved().isEmpty()
        && changes.offeringsAdded().isEmpty()
        && changes.offeringsRemoved().isEmpty()
        && changes.featureRowsAdded().isEmpty()
        && changes.featureRowsRemoved().isEmpty()
        && changes.cellsChanged().isEmpty()
        && changes.rulesAdded().isEmpty()
        && changes.rulesRemoved().isEmpty()
        && changes.rulesChanged().isEmpty();
  }

  @Test
  void twoCatalogsWithTheSameContentsDoNotDiffer() {
    var withARule = catalog(contents -> contents.rules.add(requires(TOW, HITCH)));

    assertThat(nothing(Diff.between(catalog(), catalog()))).isTrue();
    assertThat(nothing(Diff.between(withARule, withARule))).isTrue();
  }

  @Test
  void aLabelThatTheLibraryChangedIsNoChange() {
    var renamed =
        catalog(
            contents -> {
              contents.trims.set(1, new Trim(2, "Sport Plus", 5));
              contents.regions.set(1, new Region("EU", "Europe and Africa"));
              contents.featureRows.set(
                  0, new FeatureRow(8, "PACKAGE_TOW", Kind.PACKAGE, "Towing Package", "CHASSIS"));
            });

    assertThat(nothing(Diff.between(catalog(), renamed))).isTrue();
  }

  @Test
  void aTrimARegionAndAnOfferingAreAddedAndRemoved() {
    var changed =
        catalog(
            contents -> {
              contents.trims.remove(SPORT);
              contents.trims.add(TOURING);
              contents.regions.remove(EUROPE);
              contents.regions.add(ASIA);
              contents.offerings.clear();
              contents.offerings.add(new Offering(1, "NA"));
              contents.offerings.add(new Offering(3, "ASIA"));
              contents.cells.clear();
            });

    var changes = Diff.between(catalog(), changed);

    assertThat(changes.trimsAdded()).containsExactly(TOURING);
    assertThat(changes.trimsRemoved()).containsExactly(SPORT);
    assertThat(changes.regionsAdded()).containsExactly(ASIA);
    assertThat(changes.regionsRemoved()).containsExactly(EUROPE);
    assertThat(changes.offeringsAdded())
        .containsExactly(new OfferingNamed(3, "Touring", "ASIA", "Asia"));
    assertThat(changes.offeringsRemoved())
        .as("named by the catalog before, region by region")
        .containsExactly(
            new OfferingNamed(2, "Sport", "NA", "North America"),
            new OfferingNamed(1, "Base", "EU", "Europe"));
    assertThat(changes.cellsChanged())
        .as("the cells of the offerings that went are not listed one by one")
        .isEmpty();
  }

  @Test
  void aFeatureRowIsAddedAndRemovedAndItsCellsAreNotListedOneByOne() {
    var changed =
        catalog(
            contents -> {
              contents.featureRows.remove(COOLING);
              contents.cells.removeIf(cell -> cell.featureId() == 10);
              contents.featureRows.add(ROOF);
              contents.cells.add(new Cell(11, 1, "NA", Availability.S));
            });

    var changes = Diff.between(catalog(), changed);

    assertThat(changes.featureRowsAdded()).containsExactly(ROOF);
    assertThat(changes.featureRowsRemoved()).containsExactly(COOLING);
    assertThat(changes.cellsChanged()).isEmpty();
  }

  @Test
  void aCellIsListedWithItsAvailabilityBeforeAndAfter() {
    var changed =
        catalog(
            contents -> {
              contents.cells.clear();
              // The tow package becomes Standard on Sport, the hitch is no longer offered there,
              // and cooling is now Available on Base in North America.
              contents.cells.add(new Cell(8, 2, "NA", Availability.S));
              contents.cells.add(new Cell(10, 1, "EU", Availability.S));
              contents.cells.add(new Cell(10, 1, "NA", Availability.A));
            });

    var changes = Diff.between(catalog(), changed);

    assertThat(changes.cellsChanged())
        .as("by feature, then by region and trim")
        .containsExactly(
            new CellChanged(
                8,
                "PACKAGE_TOW",
                "Tow Package",
                2,
                "Sport",
                "NA",
                "North America",
                Availability.A,
                Availability.S),
            new CellChanged(
                9,
                "HITCH",
                "Trailer Hitch Receiver",
                2,
                "Sport",
                "NA",
                "North America",
                Availability.S,
                Availability.N),
            new CellChanged(
                10,
                "COOLING",
                "Heavy-Duty Cooling",
                1,
                "Base",
                "NA",
                "North America",
                Availability.N,
                Availability.A));
  }

  @Test
  void aRuleIsAddedAndRemovedInWords() {
    var scoped =
        rule(
            "scoped",
            RuleKind.INCLUDES,
            TOW,
            List.of(HITCH, COOLING),
            Set.of(2L),
            new LinkedHashSet<>(List.of("NA", "EU")),
            null);
    var with = catalog(contents -> contents.rules.add(scoped));

    assertThat(Diff.between(catalog(), with).rulesAdded())
        .containsExactly(
            new RuleSaid(
                "scoped",
                "Tow Package includes Trailer Hitch Receiver, Heavy-Duty Cooling (on Sport; in"
                    + " North America, Europe)"));
    assertThat(Diff.between(with, catalog()).rulesRemoved())
        .containsExactly(
            new RuleSaid(
                "scoped",
                "Tow Package includes Trailer Hitch Receiver, Heavy-Duty Cooling (on Sport; in"
                    + " North America, Europe)"));
    assertThat(Diff.between(with, catalog()).rulesAdded()).isEmpty();
  }

  @Test
  void aRuleHasChangedWhenItsSourceItsTargetsOrEitherScopeDiffers() {
    var before = catalog(contents -> contents.rules.add(requires(TOW, HITCH)));
    var changes =
        List.of(
            requires(COOLING, HITCH),
            requires(TOW, HITCH, COOLING),
            rule("a-rule", RuleKind.REQUIRES, TOW, List.of(HITCH), Set.of(2L), null, null),
            rule("a-rule", RuleKind.REQUIRES, TOW, List.of(HITCH), null, Set.of("EU"), null));

    assertThat(changes)
        .extracting(
            now ->
                Diff.between(before, catalog(contents -> contents.rules.add(now))).rulesChanged())
        .containsExactly(
            List.of(
                new RuleChanged(
                    "a-rule",
                    "Tow Package requires Trailer Hitch Receiver",
                    "Heavy-Duty Cooling requires Trailer Hitch Receiver")),
            List.of(
                new RuleChanged(
                    "a-rule",
                    "Tow Package requires Trailer Hitch Receiver",
                    "Tow Package requires Trailer Hitch Receiver, Heavy-Duty Cooling")),
            List.of(
                new RuleChanged(
                    "a-rule",
                    "Tow Package requires Trailer Hitch Receiver",
                    "Tow Package requires Trailer Hitch Receiver (on Sport)")),
            List.of(
                new RuleChanged(
                    "a-rule",
                    "Tow Package requires Trailer Hitch Receiver",
                    "Tow Package requires Trailer Hitch Receiver (in Europe)")));
    var reordered = catalog(contents -> contents.rules.add(requires(TOW, COOLING, HITCH)));
    assertThat(
            Diff.between(
                    catalog(contents -> contents.rules.add(requires(TOW, HITCH, COOLING))),
                    reordered)
                .rulesChanged())
        .as("the same targets in another order")
        .isEmpty();
  }

  @Test
  void anExclusionIsOneUnitAndIsListedOnceForItsPair() {
    var with = catalog(contents -> contents.rules.addAll(exclusion(TOW, COOLING, null)));
    var turned =
        catalog(
            contents -> {
              contents.rules.addAll(exclusion(TOW, COOLING, null).reversed());
            });
    var narrowed = catalog(contents -> contents.rules.addAll(exclusion(TOW, COOLING, Set.of(1L))));
    var another = catalog(contents -> contents.rules.addAll(exclusion(TOW, HITCH, null)));

    assertThat(Diff.between(catalog(), with).rulesAdded())
        .containsExactly(new RuleSaid("forward", "Tow Package excludes Heavy-Duty Cooling"));
    assertThat(Diff.between(with, catalog()).rulesRemoved())
        .containsExactly(new RuleSaid("forward", "Tow Package excludes Heavy-Duty Cooling"));
    assertThat(nothing(Diff.between(with, turned)))
        .as("the pair says the same whichever of its rules comes first")
        .isTrue();
    assertThat(Diff.between(with, narrowed).rulesChanged())
        .containsExactly(
            new RuleChanged(
                "forward",
                "Tow Package excludes Heavy-Duty Cooling",
                "Tow Package excludes Heavy-Duty Cooling (on Base)"));
    assertThat(Diff.between(with, another).rulesChanged())
        .containsExactly(
            new RuleChanged(
                "forward",
                "Tow Package excludes Heavy-Duty Cooling",
                "Tow Package excludes Trailer Hitch Receiver"));
  }

  @Test
  void whatWasRemovedIsNamedAsTheCatalogBeforeNamedIt() {
    var before =
        catalog(
            contents -> {
              contents.trims.set(1, new Trim(2, "Sport as it was", 2));
              contents.featureRows.set(
                  2, new FeatureRow(10, "COOLING", Kind.FEATURE, "Cooling as it was", "THERMAL"));
              contents.rules.add(requires(TOW, COOLING));
            });
    var after =
        catalog(
            contents -> {
              contents.trims.remove(SPORT);
              contents.offerings.remove(new Offering(2, "NA"));
              contents.featureRows.remove(COOLING);
              contents.cells.clear();
            });

    var changes = Diff.between(before, after);

    assertThat(changes.trimsRemoved()).extracting(Trim::name).containsExactly("Sport as it was");
    assertThat(changes.offeringsRemoved())
        .extracting(OfferingNamed::trim)
        .containsExactly("Sport as it was");
    assertThat(changes.featureRowsRemoved())
        .extracting(FeatureRow::name)
        .containsExactly("Cooling as it was");
    assertThat(changes.rulesRemoved())
        .extracting(RuleSaid::rule)
        .containsExactly("Tow Package requires Cooling as it was");
  }
}
