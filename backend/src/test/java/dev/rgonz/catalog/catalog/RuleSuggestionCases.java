package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.LongFunction;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sentences a rule is suggested from to measure the model, each with the rule it should give
 * for a working copy of the seeded Compact SUV 2026, or with none where the sentence does not say
 * one rule. A case passes when the suggested rule equals the expected one, and nothing is judged by
 * a model.
 */
final class RuleSuggestionCases {
  static final String PASSES_WHEN =
      "A case passes when the suggested rule equals the expected one in its kind, its source, its"
          + " targets, its trims, and its regions, or when no rule is suggested where none is"
          + " expected. An exclusion says the same whichever of its two features is its source."
          + " Nothing is judged by a model.";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private RuleSuggestionCases() {}

  /**
   * A rule by the codes of its features, the names of its trims, and the codes of its regions.
   *
   * @param trims null when it covers every trim
   * @param regions null when it covers every region
   */
  record Rule(
      String kind, String source, List<String> targets, List<String> trims, List<String> regions) {

    /** Whether the other rule says the same as this one. */
    boolean saysTheSameAs(Rule other) {
      return other != null && said().equals(other.said());
    }

    private List<Object> said() {
      var features = new TreeSet<>(targets);
      // An exclusion holds both ways, so its source is one of the two features it names.
      Object source = this.source;
      if (kind.equals("EXCLUDES")) {
        features.add(this.source);
        source = "";
      }
      return List.of(kind, source, features, scope(trims), scope(regions));
    }

    private static Set<String> scope(List<String> named) {
      return named == null ? Set.of() : new TreeSet<>(named);
    }

    @Override
    public String toString() {
      return "%s %s -> %s; trims: %s; regions: %s"
          .formatted(
              kind,
              source,
              String.join(", ", targets),
              trims == null || trims.isEmpty() ? "every one" : String.join(", ", trims),
              regions == null || regions.isEmpty() ? "every one" : String.join(", ", regions));
    }
  }

  /**
   * One sentence and what it should give.
   *
   * @param heldOut whether it is one of those that are not looked at while the prompt is changed
   * @param expected the rule, or null when the sentence does not say one rule
   */
  record Case(String id, boolean heldOut, String sentence, Rule expected) {}

  /** Every case, as the repository keeps them. */
  static List<Case> all() {
    try (var cases =
        RuleSuggestionCases.class.getResourceAsStream("/evaluation/rule-suggestions.json")) {
      return JSON.readValue(cases, new TypeReference<List<Case>>() {});
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Has a rule suggested from the case's sentence and holds what came against what is expected.
   *
   * @param suggest what the application answers a sentence with: a suggestion, or why there is none
   * @param featureCode the code of the feature of an id
   * @param trimName the name of the trim of an id
   */
  static Result run(
      Case one,
      Function<String, JsonNode> suggest,
      LongFunction<String> featureCode,
      LongFunction<String> trimName) {
    var answered = suggest.apply(one.sentence());
    var suggestion = answered.path("suggestion");
    Rule came = null;
    if (suggestion.isObject()) {
      var targets = new ArrayList<String>();
      suggestion
          .path("targetFeatureIds")
          .forEach(id -> targets.add(featureCode.apply(id.asLong())));
      var trims = new ArrayList<String>();
      suggestion.path("trimIds").forEach(id -> trims.add(trimName.apply(id.asLong())));
      var regions = new ArrayList<String>();
      suggestion.path("regionCodes").forEach(code -> regions.add(code.asString()));
      came =
          new Rule(
              suggestion.path("kind").asString(),
              featureCode.apply(suggestion.path("sourceFeatureId").asLong()),
              targets,
              suggestion.path("allTrims").asBoolean() ? null : trims,
              suggestion.path("allRegions").asBoolean() ? null : regions);
    }

    return new Result(
        one.id(),
        one.heldOut(),
        one.expected() == null ? came == null : one.expected().saysTheSameAs(came),
        "\"%s\"".formatted(one.sentence()),
        one.expected() == null ? "no rule" : one.expected().toString(),
        came == null
            ? "no rule: %s".formatted(answered.path("refusal").asString("nothing was said of why"))
            : came.toString());
  }
}
