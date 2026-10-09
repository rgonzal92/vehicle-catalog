package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.library.RuleKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A rule as validation applies it: a relationship between a source feature and its targets, in the
 * offerings its scopes cover. A global rule covers every trim.
 *
 * @param key what identifies the rule within its origin
 * @param targetFeatureIds one target for an Excludes
 * @param trimIds the trims the rule covers; they count for nothing when it covers every trim
 * @param regionCodes the regions the rule covers; they count for nothing when it covers every one
 * @param pairKey what an Excludes shares with the rule that says the same the other way round
 */
record Rule(
    Origin origin,
    String key,
    RuleKind kind,
    long sourceFeatureId,
    List<Long> targetFeatureIds,
    boolean allTrims,
    Set<Long> trimIds,
    boolean allRegions,
    Set<String> regionCodes,
    String pairKey) {

  /** Whether a rule is defined in the library for every catalog, or belongs to one catalog. */
  enum Origin {
    GLOBAL,
    CATALOG
  }

  /**
   * The rule as a sentence without its full stop, by the names given for what it names: "Tow
   * Package requires Heavy-Duty Cooling (on Sport; in Europe)". A scope that covers everything is
   * not said.
   */
  String inWords(
      Function<Long, String> feature,
      Function<Long, String> trim,
      Function<String, String> region) {
    var said =
        "%s %s %s"
            .formatted(
                feature.apply(sourceFeatureId),
                kind.words(),
                targetFeatureIds.stream().map(feature).collect(Collectors.joining(", ")));
    var scopes = new ArrayList<String>();
    if (!allTrims) {
      scopes.add("on " + trimIds.stream().map(trim).collect(Collectors.joining(", ")));
    }
    if (!allRegions) {
      scopes.add("in " + regionCodes.stream().map(region).collect(Collectors.joining(", ")));
    }
    return scopes.isEmpty() ? said : "%s (%s)".formatted(said, String.join("; ", scopes));
  }

  /**
   * What the rule says, in a form that two rules saying the same share: its kind, the features it
   * names, and its scopes. An exclusion says the same whichever rule of its pair says it, so its
   * two features are named without a direction.
   */
  Object said() {
    var targets = Set.copyOf(targetFeatureIds);
    Object named;
    if (pairKey == null) {
      named = List.of(sourceFeatureId, targets);
    } else {
      var both = new HashSet<>(targets);
      both.add(sourceFeatureId);
      named = both;
    }
    return List.of(
        kind,
        named,
        allTrims ? "every trim" : Set.copyOf(trimIds),
        allRegions ? "every region" : Set.copyOf(regionCodes));
  }

  /** Whether the rule is in effect for the offering. */
  boolean covers(Offering offering) {
    return (allTrims || trimIds.contains(offering.trimId()))
        && (allRegions || regionCodes.contains(offering.regionCode()));
  }
}
