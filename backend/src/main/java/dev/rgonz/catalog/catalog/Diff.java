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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out what changed from one catalog to another: the trims, regions, offerings, feature rows,
 * and rules that were added and removed, the cells whose availability changed, and the rules that
 * say something else. It knows nothing of the database or of the web.
 *
 * <p>Contents are matched by what identifies them: a trim and a feature by the library entry, a
 * region by its code, an offering by trim and region, a cell by feature, trim, and region, and a
 * rule by its rule key. An exclusion is two paired rules that say the same, so it is one unit,
 * matched by its pair key and listed once. Labels are no contents: a trim that the library has
 * renamed has not changed.
 */
final class Diff {
  private Diff() {}

  /**
   * What changed from the catalog before to the catalog after. What was added and what changed is
   * named by the labels of the catalog after, and what was removed by those of the catalog before.
   */
  static Changes between(CatalogSnapshot before, CatalogSnapshot after) {
    var was = new Side(before);
    var is = new Side(after);
    var offeringsBefore = Set.copyOf(before.offerings());
    var offeringsAfter = Set.copyOf(after.offerings());

    return new Changes(
        only(after.trims(), Trim::id, was.trims.keySet()),
        only(before.trims(), Trim::id, is.trims.keySet()),
        only(after.regions(), Region::code, was.regions.keySet()),
        only(before.regions(), Region::code, is.regions.keySet()),
        is.offerings(offering -> !offeringsBefore.contains(offering)),
        was.offerings(offering -> !offeringsAfter.contains(offering)),
        only(after.featureRows(), FeatureRow::id, was.features.keySet()),
        only(before.featureRows(), FeatureRow::id, is.features.keySet()),
        cellsChanged(was, is, offeringsBefore),
        is.rules.entrySet().stream()
            .filter(rule -> !was.rules.containsKey(rule.getKey()))
            .map(rule -> new RuleSaid(rule.getValue().key(), is.inWords(rule.getValue())))
            .toList(),
        was.rules.entrySet().stream()
            .filter(rule -> !is.rules.containsKey(rule.getKey()))
            .map(rule -> new RuleSaid(rule.getValue().key(), was.inWords(rule.getValue())))
            .toList(),
        is.rules.entrySet().stream()
            .filter(rule -> was.rules.containsKey(rule.getKey()))
            .filter(rule -> !saysTheSame(was.rules.get(rule.getKey()), rule.getValue()))
            .map(
                rule ->
                    new RuleChanged(
                        rule.getValue().key(),
                        was.inWords(was.rules.get(rule.getKey())),
                        is.inWords(rule.getValue())))
            .toList());
  }

  /** The entries whose key the other catalog does not have, in the order their catalog has them. */
  private static <T, K> List<T> only(List<T> entries, Function<T, K> key, Set<K> others) {
    return entries.stream().filter(entry -> !others.contains(key.apply(entry))).toList();
  }

  /**
   * The cells with another availability than before, of the feature rows and offerings that both
   * catalogs have. The cells of a row or an offering that was added or removed are not listed one
   * by one: the row or the offering is.
   */
  private static List<CellChanged> cellsChanged(Side was, Side is, Set<Offering> offeringsBefore) {
    var changed = new ArrayList<CellChanged>();
    for (FeatureRow feature : is.snapshot.featureRows()) {
      if (!was.features.containsKey(feature.id())) {
        continue;
      }
      for (Offering offering : is.offeringsInOrder()) {
        if (!offeringsBefore.contains(offering)) {
          continue;
        }
        var earlier = was.value(feature.id(), offering);
        var now = is.value(feature.id(), offering);
        if (earlier != now) {
          changed.add(
              new CellChanged(
                  feature.id(),
                  feature.code(),
                  feature.name(),
                  offering.trimId(),
                  is.trims.get(offering.trimId()).name(),
                  offering.regionCode(),
                  is.regions.get(offering.regionCode()).name(),
                  earlier,
                  now));
        }
      }
    }
    return changed;
  }

  /** Whether two rules with the same key, or two pairs with the same pair key, say the same. */
  private static boolean saysTheSame(Rule one, Rule other) {
    return one.said().equals(other.said());
  }

  /**
   * What changed from one catalog to another.
   *
   * @param cellsChanged in the order of the catalog after: by feature, then by region and trim
   * @param rulesAdded each rule in words, by the labels of the catalog after; an exclusion once
   * @param rulesRemoved each rule in words, by the labels of the catalog before
   */
  record Changes(
      List<Trim> trimsAdded,
      List<Trim> trimsRemoved,
      List<Region> regionsAdded,
      List<Region> regionsRemoved,
      List<OfferingNamed> offeringsAdded,
      List<OfferingNamed> offeringsRemoved,
      List<FeatureRow> featureRowsAdded,
      List<FeatureRow> featureRowsRemoved,
      List<CellChanged> cellsChanged,
      List<RuleSaid> rulesAdded,
      List<RuleSaid> rulesRemoved,
      List<RuleChanged> rulesChanged) {}

  /** An offering with the names of its trim and its region. */
  record OfferingNamed(long trimId, String trim, String regionCode, String region) {}

  /** A cell whose availability changed, with the names of what it is a cell of. */
  record CellChanged(
      long featureId,
      String featureCode,
      String feature,
      long trimId,
      String trim,
      String regionCode,
      String region,
      Availability before,
      Availability after) {}

  /** A rule that was added or removed, by its key and in words. */
  record RuleSaid(String key, String rule) {}

  /** A rule that says something else than before: what it said, and what it says. */
  record RuleChanged(String key, String before, String after) {}

  /** One of the two catalogs, with its contents by what identifies them. */
  private static final class Side {
    final CatalogSnapshot snapshot;
    final Map<Long, Trim> trims;
    final Map<String, Region> regions;
    final Map<Long, FeatureRow> features;
    final Map<List<Object>, Availability> cells = new HashMap<>();

    /** The rules by what matches them: an exclusion once, by its pair key. */
    final Map<String, Rule> rules = new LinkedHashMap<>();

    Side(CatalogSnapshot snapshot) {
      this.snapshot = snapshot;
      trims = snapshot.trims().stream().collect(Collectors.toMap(Trim::id, Function.identity()));
      regions =
          snapshot.regions().stream().collect(Collectors.toMap(Region::code, Function.identity()));
      features =
          snapshot.featureRows().stream()
              .collect(Collectors.toMap(FeatureRow::id, Function.identity()));
      for (Cell cell : snapshot.cells()) {
        cells.put(List.of(cell.featureId(), cell.trimId(), cell.regionCode()), cell.availability());
      }
      // Of a pair, the rule that comes first says it for both.
      for (Rule rule : snapshot.rules()) {
        rules.putIfAbsent(rule.pairKey() == null ? rule.key() : "pair " + rule.pairKey(), rule);
      }
    }

    Availability value(long featureId, Offering offering) {
      return cells.getOrDefault(
          List.of(featureId, offering.trimId(), offering.regionCode()), Availability.N);
    }

    /** The catalog's offerings as its matrix has them: region by region, and trims in order. */
    List<Offering> offeringsInOrder() {
      var regionPlaces = new HashMap<String, Integer>();
      snapshot.regions().forEach(region -> regionPlaces.put(region.code(), regionPlaces.size()));
      return snapshot.offerings().stream()
          .sorted(
              Comparator.comparing((Offering offering) -> regionPlaces.get(offering.regionCode()))
                  .thenComparing(offering -> trims.get(offering.trimId()).sortOrder())
                  .thenComparing(Offering::trimId))
          .toList();
    }

    List<OfferingNamed> offerings(java.util.function.Predicate<Offering> wanted) {
      return offeringsInOrder().stream()
          .filter(wanted)
          .map(
              offering ->
                  new OfferingNamed(
                      offering.trimId(),
                      trims.get(offering.trimId()).name(),
                      offering.regionCode(),
                      regions.get(offering.regionCode()).name()))
          .toList();
    }

    /** A rule as a sentence without its full stop, by this catalog's labels. */
    String inWords(Rule rule) {
      return rule.inWords(
          this::feature,
          id -> trims.containsKey(id) ? trims.get(id).name() : "a trim it has not",
          code -> regions.containsKey(code) ? regions.get(code).name() : code);
    }

    private String feature(long id) {
      return features.containsKey(id) ? features.get(id).name() : "a feature it has no row for";
    }
  }
}
