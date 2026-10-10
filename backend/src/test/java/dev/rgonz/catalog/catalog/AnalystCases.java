package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The questions the analyst is asked to measure the model, about the seeded Approved catalogs, each
 * with the tools that have to be called for it and the facts its answer has to hold. Some ask for
 * what a working copy holds, and pass when the answer holds none of it. Nothing is judged by a
 * model: an answer is searched for words.
 */
final class AnalystCases {
  static final String PASSES_WHEN =
      "A case passes when the tools it names were called, when the answer holds every fact it"
          + " requires, and when the answer holds nothing it names as a working copy's. The working"
          + " copy of such a case has a trim, Performance, that no Approved catalog has. Words are"
          + " looked for whatever their case, and nothing is judged by a model.";

  /** The trim that the working copy of a case has and that no seeded Approved catalog has. */
  static final String TRIM_OF_THE_WORKING_COPY = "Performance";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AnalystCases() {}

  /**
   * One question and what its answer has to hold.
   *
   * @param heldOut whether it is one of those that are not looked at while the prompt is changed
   * @param question the question, in which {@code {copy}} stands for the working copy's id
   * @param ofAWorkingCopy whether it asks for what a working copy of the asker's holds
   * @param tools the tools that have to be called, whatever else is
   * @param facts what the answer has to hold: of each list, at least one
   * @param absent what the answer must not hold
   */
  record Case(
      String id,
      boolean heldOut,
      String question,
      boolean ofAWorkingCopy,
      List<String> tools,
      List<List<String>> facts,
      List<String> absent) {

    /** The question as it is asked, of this working copy. */
    String asked(long copy) {
      return question.replace("{copy}", String.valueOf(copy));
    }
  }

  /** Every case, as the repository keeps them. */
  static List<Case> all() {
    try (var cases = AnalystCases.class.getResourceAsStream("/evaluation/analyst.json")) {
      return JSON.readValue(cases, new TypeReference<List<Case>>() {});
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Holds the analyst's answer to a case's question against what the case requires.
   *
   * @param copy the working copy the question was asked of, if it names one
   * @param answered what the application answered with: the answer and its tool calls, or why there
   *     is none
   */
  static Result judge(Case one, long copy, JsonNode answered) {
    var answer = answered.path("answer").asString("");
    var lower = answer.toLowerCase(Locale.ROOT);
    var called = new ArrayList<String>();
    answered.path("toolCalls").forEach(call -> called.add(call.path("tool").asString()));

    var facts =
        one.facts().stream()
            .allMatch(
                any ->
                    any.stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT))));
    var leaked =
        one.absent().stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT)));

    return new Result(
        one.id(),
        one.heldOut(),
        answered.path("answer").isString() && called.containsAll(one.tools()) && facts && !leaked,
        "\"%s\"".formatted(one.asked(copy)),
        "calls %s; holds %s; does not hold %s"
            .formatted(
                one.tools().isEmpty() ? "whatever it calls" : String.join(", ", one.tools()),
                one.facts().isEmpty()
                    ? "whatever it says"
                    : String.join(
                        ", ", one.facts().stream().map(any -> String.join(" or ", any)).toList()),
                one.absent().isEmpty()
                    ? "anything in particular"
                    : String.join(", ", one.absent())),
        answered.path("answer").isString()
            ? "called %s; answered: %s"
                .formatted(
                    called.isEmpty() ? "nothing" : String.join(", ", called),
                    answer.replaceAll("\\s+", " ").strip())
            : "no answer: %s"
                .formatted(answered.path("detail").asString("nothing was said of why")));
  }
}
