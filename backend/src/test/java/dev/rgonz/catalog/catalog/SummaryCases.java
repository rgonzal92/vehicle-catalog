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
 * The sets of changes a summary is written of to measure the model, each made to a working copy of
 * the seeded Compact SUV 2026 and submitted, with what a summary of them has to mention and what it
 * must not. Nothing is judged by a model: a summary is searched for words.
 */
final class SummaryCases {
  static final String PASSES_WHEN =
      "A case passes when there is a summary, when it mentions everything the case requires, and"
          + " when it mentions nothing the case names as outside the changes. The application"
          + " itself gives no summary that names a feature or a trim the changes do not have, so"
          + " such a summary fails as one that is not there. Words are looked for whatever their"
          + " case, and nothing is judged by a model.";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SummaryCases() {}

  /**
   * A cell to set, by the code of its feature, the name of its trim, and the code of its region.
   */
  record Cell(String feature, String trim, String region, String availability) {}

  /**
   * One set of changes and what a summary of it has to hold.
   *
   * @param heldOut whether it is one of those that are not looked at while the prompt is changed
   * @param cells the cells to set
   * @param rules the rules to add
   * @param mentions what the summary has to mention: of each list, at least one
   * @param mustNot what is outside the changes, and so must not be mentioned
   */
  record Case(
      String id,
      boolean heldOut,
      List<Cell> cells,
      List<RuleSuggestionCases.Rule> rules,
      List<List<String>> mentions,
      List<String> mustNot) {

    /** The changes in a line, for the report. */
    String changes() {
      var made = new ArrayList<String>();
      cells.forEach(
          cell ->
              made.add(
                  "%s on %s in %s becomes %s"
                      .formatted(cell.feature(), cell.trim(), cell.region(), cell.availability())));
      rules.forEach(rule -> made.add("a rule is added: " + rule));
      return String.join(". ", made);
    }
  }

  /** Every case, as the repository keeps them. */
  static List<Case> all() {
    try (var cases = SummaryCases.class.getResourceAsStream("/evaluation/summaries.json")) {
      return JSON.readValue(cases, new TypeReference<List<Case>>() {});
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Holds the summary the application has of a case's changes against what the case requires.
   *
   * @param summary the summary as the application answers with it: its status, and its headline and
   *     bullets or why there is none
   */
  static Result judge(Case one, JsonNode summary) {
    var ready = summary.path("status").asString("").equals("READY");
    var lines = new ArrayList<String>();
    lines.add(summary.path("headline").asString(""));
    summary.path("bullets").forEach(bullet -> lines.add(bullet.asString()));
    var said = String.join(" ", lines);
    var lower = said.toLowerCase(Locale.ROOT);

    var mentioned =
        one.mentions().stream()
            .allMatch(
                any ->
                    any.stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT))));
    var outside =
        one.mustNot().stream().anyMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT)));

    return new Result(
        one.id(),
        one.heldOut(),
        ready && mentioned && !outside,
        one.changes(),
        "mentions %s, and not %s"
            .formatted(
                String.join(
                    ", ", one.mentions().stream().map(any -> String.join(" or ", any)).toList()),
                String.join(", ", one.mustNot())),
        ready
            ? said
            : "no summary: %s"
                .formatted(summary.path("reason").asString("nothing was said of why")));
  }
}
