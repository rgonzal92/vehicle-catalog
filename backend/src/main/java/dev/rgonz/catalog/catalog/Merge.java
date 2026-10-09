package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Works out the update of a stale working copy from its lineage's current Approved: a merge of
 * three catalogs. <b>Base</b> is the nearest catalog both sides descend from, <b>mine</b> is the
 * working copy, and <b>theirs</b> is the current Approved. It knows nothing of the database or of
 * the web.
 *
 * <p>What is merged are trims, regions, offerings, and feature rows, each of which a catalog has or
 * has not; cells, by their availability; and rules, by what they say, an exclusion being one unit
 * for its pair. For each of them: what only theirs changed is taken, what only mine changed is
 * kept, what both changed alike is kept, and what both changed differently is a conflict for the
 * owner to settle. Labels are not merged: a trim the library has renamed has not changed.
 *
 * <p>A trim, a region, an offering, or a feature row that one side removed while the other changed
 * something beneath it is a conflict too, on what was removed. Settled as removed, it takes the
 * cells and rules beneath it along. Settled as kept, what is beneath it is as the side that kept it
 * has it.
 */
final class Merge {
  private Merge() {}

  /** Whose version of something a conflict is settled for. */
  enum Side {
    MINE,
    THEIRS
  }

  /** What a conflict is about. */
  enum Kind {
    TRIM,
    REGION,
    OFFERING,
    FEATURE_ROW,
    CELL,
    RULE
  }

  /**
   * The merge of mine with theirs over their base, with each conflict settled as the resolutions
   * say. A conflict without a resolution is left as mine has it, and is listed as unsettled.
   *
   * @param resolutions for each conflict that is settled, by its id, whose version is taken
   */
  static Result of(
      CatalogSnapshot base,
      CatalogSnapshot mine,
      CatalogSnapshot theirs,
      Map<String, Side> resolutions) {
    return new Merging(new Contents(base), new Contents(mine), new Contents(theirs), resolutions)
        .run();
  }

  /**
   * What a merge comes to.
   *
   * @param merged the working copy's contents after the update, with the labels the working copy
   *     shows where it has them
   * @param conflicts every conflict, settled or not, in the order a catalog is read: trims,
   *     regions, offerings, feature rows, cells, rules
   */
  record Result(CatalogSnapshot merged, List<Conflict> conflicts) {
    /** The conflicts the owner has still to settle. */
    List<Conflict> unsettled() {
      return conflicts.stream().filter(conflict -> conflict.resolution() == null).toList();
    }
  }

  /**
   * Something both sides changed differently, or that one side removed while the other changed what
   * is beneath it.
   *
   * @param id what identifies the conflict among those of one merge, and from one working out of
   *     the same merge to the next
   * @param what which one of its kind it is about, by name: a cell by its feature and its offering,
   *     and a rule by its source feature
   * @param base how it was in the catalog both sides come from, in words
   * @param mine how the working copy has it
   * @param theirs how the current Approved has it
   * @param resolution whose version the owner took, or null while they have not said
   */
  record Conflict(
      String id,
      Kind kind,
      String what,
      String base,
      String mine,
      String theirs,
      Side resolution) {}

  /** A cell by what identifies it. */
  private record CellKey(long featureId, long trimId, String regionCode) {
    Offering offering() {
      return new Offering(trimId, regionCode);
    }
  }

  /**
   * A rule as the merge matches it: one rule, or the two rules of an exclusion's pair.
   *
   * @param rules the rule, or both rules of the pair
   */
  private record RuleUnit(String key, List<Rule> rules) {
    Rule first() {
      return rules.getFirst();
    }

    /** What the unit says. Of a pair, either rule says it for both. */
    Object said() {
      return first().said();
    }

    Set<Long> features() {
      var rule = first();
      var named = new HashSet<>(rule.targetFeatureIds());
      named.add(rule.sourceFeatureId());
      return named;
    }

    Set<Long> trims() {
      return first().allTrims() ? Set.of() : first().trimIds();
    }

    Set<String> regions() {
      return first().allRegions() ? Set.of() : first().regionCodes();
    }
  }

  /** One of the three catalogs, with its contents by what identifies them. */
  private static final class Contents {
    final CatalogSnapshot snapshot;
    final Map<Long, Trim> trims = new LinkedHashMap<>();
    final Map<String, Region> regions = new LinkedHashMap<>();
    final Set<Offering> offerings;
    final Map<Long, FeatureRow> features = new LinkedHashMap<>();
    final Map<CellKey, Availability> cells = new HashMap<>();
    final Map<String, RuleUnit> rules = new LinkedHashMap<>();

    Contents(CatalogSnapshot snapshot) {
      this.snapshot = snapshot;
      snapshot.trims().forEach(trim -> trims.put(trim.id(), trim));
      snapshot.regions().forEach(region -> regions.put(region.code(), region));
      offerings = new LinkedHashSet<>(snapshot.offerings());
      snapshot.featureRows().forEach(feature -> features.put(feature.id(), feature));
      for (Cell cell : snapshot.cells()) {
        cells.put(
            new CellKey(cell.featureId(), cell.trimId(), cell.regionCode()), cell.availability());
      }
      Map<String, List<Rule>> units = new LinkedHashMap<>();
      for (Rule rule : snapshot.rules()) {
        units
            .computeIfAbsent(
                rule.pairKey() == null ? rule.key() : "pair " + rule.pairKey(),
                _ -> new ArrayList<>())
            .add(rule);
      }
      units.forEach((key, both) -> rules.put(key, new RuleUnit(key, List.copyOf(both))));
    }

    Availability value(CellKey cell) {
      return cells.getOrDefault(cell, Availability.N);
    }

    Set<Long> trimIds() {
      return trims.keySet();
    }

    Set<String> regionCodes() {
      return regions.keySet();
    }

    Set<Long> featureIds() {
      return features.keySet();
    }
  }

  /** One merge as it is worked out. */
  private static final class Merging {
    private final Contents base;
    private final Contents mine;
    private final Contents theirs;
    private final Map<String, Side> resolutions;
    private final List<Conflict> conflicts = new ArrayList<>();

    /**
     * The trims, regions, offerings, and feature rows whose removal by one side is a conflict, each
     * by its conflict's id with how it is settled, or with nothing while it is not. What is beneath
     * one of them raises no conflict of its own: it follows how that one is settled.
     */
    private final Map<String, Side> contested = new HashMap<>();

    private final Set<Long> trims = new LinkedHashSet<>();
    private final Set<String> regions = new LinkedHashSet<>();
    private final Set<Offering> offerings = new LinkedHashSet<>();
    private final Set<Long> features = new LinkedHashSet<>();

    Merging(Contents base, Contents mine, Contents theirs, Map<String, Side> resolutions) {
      this.base = base;
      this.mine = mine;
      this.theirs = theirs;
      this.resolutions = resolutions;
    }

    Result run() {
      for (long trim : union(Contents::trimIds)) {
        if (has(
            Kind.TRIM,
            "trim:" + trim,
            trimName(trim),
            side -> side.trims.containsKey(trim),
            side ->
                beneath(
                    side,
                    cell -> cell.trimId() == trim,
                    rule -> rule.trims().contains(trim),
                    offering -> offering.trimId() == trim))) {
          trims.add(trim);
        }
      }
      for (String region : union(Contents::regionCodes)) {
        if (has(
            Kind.REGION,
            "region:" + region,
            regionName(region),
            side -> side.regions.containsKey(region),
            side ->
                beneath(
                    side,
                    cell -> cell.regionCode().equals(region),
                    rule -> rule.regions().contains(region),
                    offering -> offering.regionCode().equals(region)))) {
          regions.add(region);
        }
      }
      for (Offering offering : union(side -> side.offerings)) {
        // An offering goes with its trim and with its region.
        if (!trims.contains(offering.trimId()) || !regions.contains(offering.regionCode())) {
          continue;
        }
        var above = settledAbove("trim:" + offering.trimId(), "region:" + offering.regionCode());
        boolean kept =
            above.present()
                ? above.isOpen()
                    ? mine.offerings.contains(offering)
                    : side(above.side()).offerings.contains(offering)
                : has(
                    Kind.OFFERING,
                    "offering:" + offering.trimId() + ":" + offering.regionCode(),
                    offeringName(offering),
                    side -> side.offerings.contains(offering),
                    side ->
                        beneath(
                            side,
                            cell -> cell.offering().equals(offering),
                            rule -> false,
                            other -> false));
        if (kept) {
          offerings.add(offering);
        }
      }
      for (long feature : union(Contents::featureIds)) {
        if (has(
            Kind.FEATURE_ROW,
            "feature:" + feature,
            featureName(feature),
            side -> side.features.containsKey(feature),
            side ->
                beneath(
                    side,
                    cell -> cell.featureId() == feature,
                    rule -> rule.features().contains(feature),
                    offering -> false))) {
          features.add(feature);
        }
      }

      var cells = cells();
      var rules = rules();
      // Listed as a catalog is read, whatever order they were found in.
      conflicts.sort(Comparator.comparing(Conflict::kind));

      return new Result(
          new CatalogSnapshot(
              mine.snapshot.catalogId(),
              mine.snapshot.lineageId(),
              mine.snapshot.status(),
              mine.snapshot.revision(),
              trims.stream()
                  .map(id -> labelled(side -> side.trims.get(id)))
                  .sorted(Comparator.comparing(Trim::sortOrder).thenComparing(Trim::id))
                  .toList(),
              regions.stream().map(code -> labelled(side -> side.regions.get(code))).toList(),
              List.copyOf(offerings),
              features.stream()
                  .map(id -> labelled(side -> side.features.get(id)))
                  .sorted(Comparator.comparing(FeatureRow::code))
                  .toList(),
              cells,
              rules),
          List.copyOf(conflicts));
    }

    /**
     * Whether the merged catalog has a trim, a region, an offering, or a feature row. What both
     * sides have alike stays so. What one side added or removed is taken, unless it was removed
     * while the other side changed something beneath it: that is a conflict, which the owner
     * settles for the side that removed it or for the side that kept it.
     *
     * @param in whether a side has it
     * @param changedBeneath what a side changed beneath it since the base, in words, or nothing
     */
    private boolean has(
        Kind kind,
        String id,
        String what,
        Predicate<Contents> in,
        Function<Contents, String> changedBeneath) {
      boolean inBase = in.test(base);
      boolean inMine = in.test(mine);
      boolean inTheirs = in.test(theirs);
      if (inMine == inTheirs) {
        return inMine;
      }
      // One side has left it as the base has it, and the other added or removed it.
      var changer = inMine == inBase ? theirs : mine;
      var other = changer == theirs ? mine : theirs;
      boolean removed = !in.test(changer);
      var lost = removed ? changedBeneath.apply(other) : "";
      if (lost.isEmpty()) {
        return !removed;
      }
      var resolution = resolutions.get(id);
      contested.put(id, resolution);
      conflicts.add(
          new Conflict(
              id,
              kind,
              what,
              "In the catalog",
              changer == mine ? "Removed" : "Kept, with " + lost + " changed beneath it",
              changer == theirs ? "Removed" : "Kept, with " + lost + " changed beneath it",
              resolution));
      // Unsettled, it is left as mine has it.
      return resolution == null ? inMine : in.test(side(resolution));
    }

    /**
     * What a side changed beneath a trim, a region, an offering, or a feature row since the base,
     * and would lose with it: the cells it gave another availability than Not offered, the rules it
     * added or changed, and the offerings it added. In words, or nothing when there is none.
     */
    private String beneath(
        Contents side,
        Predicate<CellKey> cellBeneath,
        Predicate<RuleUnit> ruleBeneath,
        Predicate<Offering> offeringBeneath) {
      long cells =
          side.cells.entrySet().stream()
              .filter(cell -> cellBeneath.test(cell.getKey()))
              .filter(cell -> cell.getValue() != base.value(cell.getKey()))
              .count();
      long rules =
          side.rules.values().stream()
              .filter(ruleBeneath)
              .filter(rule -> !saysTheSame(rule, base.rules.get(rule.key())))
              .count();
      long offerings =
          side.offerings.stream()
              .filter(offeringBeneath)
              .filter(offering -> !base.offerings.contains(offering))
              .count();

      return Stream.of(
              counted(cells, "cell"), counted(rules, "rule"), counted(offerings, "offering"))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(", "));
    }

    private static String counted(long count, String thing) {
      return count == 0 ? null : count + " " + thing + (count == 1 ? "" : "s");
    }

    /**
     * The merged cells. A cell goes with its feature row and with its offering. Beneath something
     * whose removal was a conflict it is as the side that conflict was settled for has it, and
     * otherwise it is merged by its availability.
     */
    private List<Cell> cells() {
      var keys = new LinkedHashSet<CellKey>();
      for (var side : List.of(mine, theirs, base)) {
        keys.addAll(side.cells.keySet());
      }
      var merged = new ArrayList<Cell>();
      for (CellKey key : keys) {
        if (!features.contains(key.featureId()) || !offerings.contains(key.offering())) {
          continue;
        }
        var above =
            settledAbove(
                "feature:" + key.featureId(),
                "offering:" + key.trimId() + ":" + key.regionCode(),
                "trim:" + key.trimId(),
                "region:" + key.regionCode());
        Availability value;
        if (above.present()) {
          value = above.isOpen() ? mine.value(key) : side(above.side()).value(key);
        } else {
          value = cell(key);
        }
        if (value != Availability.N) {
          merged.add(new Cell(key.featureId(), key.trimId(), key.regionCode(), value));
        }
      }
      return merged;
    }

    private Availability cell(CellKey key) {
      var was = base.value(key);
      var my = mine.value(key);
      var their = theirs.value(key);
      if (my == their || their == was) {
        return my;
      }
      if (my == was) {
        return their;
      }
      var id = "cell:" + key.featureId() + ":" + key.trimId() + ":" + key.regionCode();
      var resolution = resolutions.get(id);
      conflicts.add(
          new Conflict(
              id,
              Kind.CELL,
              featureName(key.featureId()) + ", " + offeringName(key.offering()),
              name(was),
              name(my),
              name(their),
              resolution));
      return resolution == Side.THEIRS ? their : my;
    }

    private static String name(Availability availability) {
      return switch (availability) {
        case S -> "Standard";
        case A -> "Available";
        case N -> "Not offered";
      };
    }

    /**
     * The merged rules. A rule is merged by what it says, and a pair as one. Two rules that the
     * sides added separately and that say the same are one rule, with the key it has in theirs. A
     * rule that names a feature the merged catalog has no row for goes with that feature row, and a
     * trim or a region the merged catalog no longer has leaves the scope of every rule.
     */
    private List<Rule> rules() {
      var keys = new LinkedHashSet<String>();
      for (var side : List.of(mine, theirs, base)) {
        keys.addAll(side.rules.keySet());
      }
      var saidByTheirs =
          theirs.rules.values().stream()
              .filter(rule -> !base.rules.containsKey(rule.key()))
              .map(RuleUnit::said)
              .collect(Collectors.toSet());
      var merged = new ArrayList<RuleUnit>();
      for (String key : keys) {
        var was = base.rules.get(key);
        var my = mine.rules.get(key);
        var their = theirs.rules.get(key);
        if (was == null && their == null && saidByTheirs.contains(my.said())) {
          // Both sides added a rule that says this, and theirs is the one that is kept.
          continue;
        }
        var kept = rule(key, was, my, their);
        if (kept != null) {
          merged.add(kept);
        }
      }

      return merged.stream().flatMap(unit -> withinTheCatalog(unit).stream()).toList();
    }

    private RuleUnit rule(String key, RuleUnit was, RuleUnit my, RuleUnit their) {
      if (saysTheSame(my, their)) {
        return my;
      }
      // Beneath something whose removal is a conflict, the rule follows how that is settled.
      var above = settledAbove(above(my, their));
      if (above.present()) {
        return above.isOpen() || above.side() == Side.MINE ? my : their;
      }
      if (saysTheSame(their, was)) {
        return my;
      }
      if (saysTheSame(my, was)) {
        return their;
      }
      var id = "rule:" + key;
      var resolution = resolutions.get(id);
      // A rule conflict is one of two versions at least, and is named by the feature it is a rule
      // of.
      var source = (my != null ? my : their).first().sourceFeatureId();
      conflicts.add(
          new Conflict(
              id,
              Kind.RULE,
              featureName(source),
              inWords(was),
              inWords(my),
              inWords(their),
              resolution));
      return resolution == Side.THEIRS ? their : my;
    }

    /** The ids of the conflicts there can be about what either version of a rule names. */
    private String[] above(RuleUnit my, RuleUnit their) {
      var ids = new ArrayList<String>();
      for (var unit : Stream.of(my, their).filter(Objects::nonNull).toList()) {
        unit.features().forEach(feature -> ids.add("feature:" + feature));
        unit.trims().forEach(trim -> ids.add("trim:" + trim));
        unit.regions().forEach(region -> ids.add("region:" + region));
      }
      return ids.toArray(String[]::new);
    }

    /**
     * A rule as the merged catalog can have it: none when it names a feature the catalog has no row
     * for, or when a scope of it lists nothing the catalog still has; and otherwise without the
     * trims and regions the catalog no longer has. Both rules of a pair fare alike.
     */
    private List<Rule> withinTheCatalog(RuleUnit unit) {
      if (!features.containsAll(unit.features())) {
        return List.of();
      }
      var kept = new ArrayList<Rule>();
      for (Rule rule : unit.rules()) {
        var trimScope = new LinkedHashSet<>(rule.trimIds());
        trimScope.retainAll(trims);
        var regionScope = new LinkedHashSet<>(rule.regionCodes());
        regionScope.retainAll(regions);
        if ((!rule.allTrims() && trimScope.isEmpty())
            || (!rule.allRegions() && regionScope.isEmpty())) {
          return List.of();
        }
        kept.add(
            new Rule(
                rule.origin(),
                rule.key(),
                rule.kind(),
                rule.sourceFeatureId(),
                rule.targetFeatureIds(),
                rule.allTrims(),
                trimScope,
                rule.allRegions(),
                regionScope,
                rule.pairKey()));
      }
      return kept;
    }

    private static boolean saysTheSame(RuleUnit one, RuleUnit other) {
      return one == null || other == null ? one == other : one.said().equals(other.said());
    }

    private String inWords(RuleUnit unit) {
      return unit == null
          ? "No such rule"
          : unit.first().inWords(this::featureName, this::trimName, this::regionName);
    }

    /**
     * How the conflicts above something are settled: for the side they all agree on, not yet when
     * one of them is open, or not at all when there is none or they disagree. What they disagree
     * about is merged for itself.
     */
    private Above settledAbove(String... ids) {
      var sides = new HashSet<Side>();
      boolean any = false;
      for (String id : ids) {
        if (contested.containsKey(id)) {
          any = true;
          var side = contested.get(id);
          if (side == null) {
            return new Above(true, null);
          }
          sides.add(side);
        }
      }
      return any && sides.size() == 1
          ? new Above(true, sides.iterator().next())
          : new Above(false, null);
    }

    /**
     * Whether there is a conflict above something, and the side it is settled for, or null while it
     * is open.
     */
    private record Above(boolean present, Side side) {
      boolean isOpen() {
        return present && side == null;
      }
    }

    private Contents side(Side side) {
      return side == Side.MINE ? mine : theirs;
    }

    /** What all three catalogs have of one kind, mine first, then what only the others have. */
    private <K> Set<K> union(Function<Contents, Set<K>> of) {
      var all = new LinkedHashSet<K>();
      for (var side : List.of(mine, theirs, base)) {
        all.addAll(of.apply(side));
      }
      return all;
    }

    /** An entry with its label as the working copy shows it, or else as the others do. */
    private <T> T labelled(Function<Contents, T> of) {
      return Stream.of(mine, theirs, base)
          .map(of)
          .filter(Objects::nonNull)
          .findFirst()
          .orElseThrow();
    }

    private String trimName(long id) {
      return labelled(side -> side.trims.get(id)).name();
    }

    private String regionName(String code) {
      return labelled(side -> side.regions.get(code)).name();
    }

    private String featureName(long id) {
      return labelled(side -> side.features.get(id)).name();
    }

    private String offeringName(Offering offering) {
      return trimName(offering.trimId()) + " in " + regionName(offering.regionCode());
    }
  }
}
