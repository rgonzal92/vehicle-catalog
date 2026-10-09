package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.library.RuleKind;
import java.util.List;
import java.util.Set;

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

  /** Whether the rule is in effect for the offering. */
  boolean covers(Offering offering) {
    return (allTrims || trimIds.contains(offering.trimId()))
        && (allRegions || regionCodes.contains(offering.regionCode()));
  }
}
