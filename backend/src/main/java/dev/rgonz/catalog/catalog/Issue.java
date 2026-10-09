package dev.rgonz.catalog.catalog;

import java.util.List;

/**
 * One validation finding about a catalog. It names what it is about, each part only where it
 * applies: a trim, a region, and a feature together name a cell, a trim and a region an offering,
 * and none of them the catalog as a whole.
 *
 * @param relatedFeatureIds the other features involved, besides the one the issue is about
 * @param rule the rule the issue comes from, or null when it comes from no rule
 * @param message the finding in words, naming things by their names
 */
record Issue(
    Code code,
    Severity severity,
    Long trimId,
    String regionCode,
    Long featureId,
    List<Long> relatedFeatureIds,
    RuleReference rule,
    String message) {

  /** An Error blocks submit and approve; a Warning never blocks. */
  enum Severity {
    ERROR,
    WARNING
  }

  /** What an issue finds. */
  enum Code {
    NO_TRIMS,
    NO_REGIONS,
    TRIM_NOT_SOLD,
    REGION_WITHOUT_TRIMS,
    NO_FEATURES,
    FEATURE_NEVER_OFFERED,
    OFFERING_EMPTY,
    FEATURE_RETIRED,
    TRIM_INACTIVE,
    REGION_INACTIVE,
    REQUIRED_NOT_STANDARD,
    REQUIRED_NOT_OFFERED,
    EXCLUDED_BOTH_STANDARD,
    EXCLUDED_BY_STANDARD,
    INCLUDED_NOT_STANDARD,
    INCLUDED_NOT_OFFERED,
    INCLUDED_ALREADY_STANDARD,
    ONE_OF_NONE_OFFERED
  }

  /** A rule, by its origin and by what identifies it there. */
  record RuleReference(String origin, String key) {}
}
