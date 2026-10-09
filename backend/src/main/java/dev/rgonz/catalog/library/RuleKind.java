package dev.rgonz.catalog.library;

import java.util.HashSet;
import java.util.List;

/**
 * What a rule says of its source feature and its targets. A global rule and a catalog rule are of
 * the same four kinds.
 */
public enum RuleKind {
  /** The source needs every target. */
  REQUIRES("requires", 1),
  /** The source needs at least one target. */
  REQUIRES_ONE_OF("requires one of", 2),
  /** The package brings every target with it. */
  INCLUDES("includes", 1),
  /** The source and the target cannot be on the same vehicle. */
  EXCLUDES("excludes", 1);

  /** The most targets a rule has. */
  public static final int MOST_TARGETS = 20;

  private final String words;
  private final int fewestTargets;

  RuleKind(String words, int fewestTargets) {
    this.words = words;
    this.fewestTargets = fewestTargets;
  }

  /** The kind as a rule says it between its source and its targets: "requires one of". */
  public String words() {
    return words;
  }

  /**
   * Why the features are not what a rule of this kind may name, or null when they are: too few
   * targets for the kind, a target named twice, or the source among the targets. Having too many
   * targets is not judged here.
   */
  public String refusalOf(long sourceFeatureId, List<Long> targetFeatureIds) {
    if (targetFeatureIds.size() < fewestTargets) {
      return this == REQUIRES_ONE_OF
          ? "A Requires one of rule has at least 2 targets."
          : "Choose at least one target.";
    }
    if (new HashSet<>(targetFeatureIds).size() < targetFeatureIds.size()) {
      return "Name each target once.";
    }
    if (targetFeatureIds.contains(sourceFeatureId)) {
      return "A rule's source cannot be one of its targets.";
    }
    return null;
  }
}
