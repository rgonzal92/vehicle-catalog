package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What holds of a rule whatever its origin: what it may name, and the sentence it is put in. A
 * global rule and a catalog rule differ in where they may name from, and only a catalog rule has a
 * trim scope.
 */
public final class Rules {
  private Rules() {}

  /**
   * Refuses content that is not what a rule may name, by the first thing wrong with it: a kind, a
   * source, or a target left out, too many targets or ones its kind does not take, a feature the
   * origin does not have or that is retired, an inclusion by a feature that is no package, and a
   * scope that lists nothing or what the origin does not have active.
   */
  public static void checkWhatARuleNames(Content given, Nameable nameable) {
    var kind = given.kind();
    var source = given.sourceFeatureId();
    var targets = given.targetFeatureIds();
    if (kind == null) {
      throw ApiException.invalid("Choose a kind.");
    }
    if (source == null) {
      throw ApiException.invalid("Choose a source.");
    }
    if (targets == null || targets.stream().anyMatch(Objects::isNull)) {
      throw ApiException.invalid("Choose at least one target.");
    }
    if (targets.size() > RuleKind.MOST_TARGETS) {
      throw ApiException.limitExceeded(
          "A rule has at most %d targets.".formatted(RuleKind.MOST_TARGETS));
    }
    var refusal = kind.refusalOf(source, targets);
    if (refusal != null) {
      throw ApiException.invalid(refusal);
    }

    var named = new ArrayList<Long>();
    named.add(source);
    named.addAll(targets);
    var features =
        nameable.features().apply(named).stream()
            .collect(Collectors.toMap(NamedFeature::id, Function.identity()));
    if (!features.keySet().containsAll(named)) {
      throw ApiException.invalid(nameable.notAFeature());
    }
    for (var id : named) {
      if (features.get(id).status().equals("RETIRED")) {
        throw ApiException.invalid(
            "%s is retired, and a rule cannot name it.".formatted(features.get(id).name()));
      }
    }
    if (kind == RuleKind.INCLUDES && !features.get(source).kind().equals("PACKAGE")) {
      throw ApiException.invalid("Only a package includes other features.");
    }

    checkScope(
        given.trimIds(),
        "Choose the trims the rule covers, or every trim.",
        nameable.activeTrims(),
        nameable.notATrim());
    checkScope(
        given.regionCodes(),
        "Choose the regions the rule applies in, or every region.",
        nameable.activeRegions(),
        nameable.notARegion());
  }

  /** Refuses a scope that lists nothing, or what the origin does not have active. */
  private static <K> void checkScope(
      List<K> listed, String nothing, Function<List<K>, Long> active, String notActive) {
    if (listed == null) {
      return;
    }
    if (listed.isEmpty()) {
      throw ApiException.invalid(nothing);
    }
    var each = new HashSet<>(listed);
    if (each.contains(null) || active.apply(List.copyOf(each)) < each.size()) {
      throw ApiException.invalid(notActive);
    }
  }

  /**
   * What a rule names, as its origin was given it. Anything of it may be missing, which the check
   * refuses.
   *
   * @param trimIds the trims the rule covers, or null when it covers every trim
   * @param regionCodes the regions the rule applies in, or null when it applies in every region
   */
  public record Content(
      RuleKind kind,
      Long sourceFeatureId,
      List<Long> targetFeatureIds,
      List<Long> trimIds,
      List<String> regionCodes) {}

  /**
   * What the rules of one origin may name, each looked up for what a rule lists, and what a rule is
   * told that names something else.
   *
   * @param features of the features listed, those a rule may name, retired or not
   * @param notAFeature what a rule is told that names a feature it may not
   * @param activeTrims how many of the trims listed are active ones a rule may name; null for the
   *     library, whose rules have no trim scope
   * @param notATrim what a rule is told that names a trim it may not; null for the library
   * @param activeRegions how many of the regions listed are active ones a rule may name
   * @param notARegion what a rule is told that names a region it may not, or one that is null
   */
  public record Nameable(
      Function<List<Long>, List<NamedFeature>> features,
      String notAFeature,
      Function<List<Long>, Long> activeTrims,
      String notATrim,
      Function<List<String>, Long> activeRegions,
      String notARegion) {}

  /** A feature a rule may name, with what it is called, its kind, and its status. */
  public record NamedFeature(long id, String name, String kind, String status) {}

  /**
   * A rule by the names of what it names, in the order it names them.
   *
   * @param trims null when the rule covers every trim
   * @param regions null when the rule applies in every region
   */
  public record Named(
      String source,
      RuleKind kind,
      List<String> targets,
      List<String> trims,
      List<String> regions) {

    /**
     * The rule as a sentence without its full stop: "Tow Package requires Heavy-Duty Cooling (on
     * Sport; in Europe)". A scope that covers everything is not said.
     */
    public String inWords() {
      var said = "%s %s %s".formatted(source, kind.words(), String.join(", ", targets));
      var scopes = new ArrayList<String>();
      if (trims != null) {
        scopes.add("on " + String.join(", ", trims));
      }
      if (regions != null) {
        scopes.add("in " + String.join(", ", regions));
      }
      return scopes.isEmpty() ? said : "%s (%s)".formatted(said, String.join("; ", scopes));
    }
  }
}
