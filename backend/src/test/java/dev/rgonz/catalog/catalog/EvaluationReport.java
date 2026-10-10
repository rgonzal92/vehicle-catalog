package dev.rgonz.catalog.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The report of a run of the cases the language model is measured against: how many of each set
 * passed, the cases that are held out apart from those that prompts are worked on with, and each
 * case that failed with what it asked, what was expected, and what came.
 */
final class EvaluationReport {
  private EvaluationReport() {}

  /**
   * What became of one case.
   *
   * @param heldOut whether it is one of those that are not looked at while a prompt is changed
   * @param asked what the model was asked, as a person would put it
   */
  record Result(
      String id, boolean heldOut, boolean passed, String asked, String expected, String came) {}

  /**
   * The cases of one thing the model is asked for.
   *
   * @param passesWhen what makes a case one that passed, in a sentence or two
   */
  record Suite(String name, String passesWhen, List<Result> results) {}

  /** The report, as the Markdown of {@code docs/ai-evaluation.md}. */
  static String of(LocalDate date, String model, BigDecimal cost, List<Suite> suites) {
    var report = new StringBuilder();
    report
        .append("# How well the language model does what it is asked\n\n")
        .append(
            "Cases with known answers, run against the real model. They are kept in"
                + " `backend/src/test/resources/evaluation/`. A third of each set is held out:"
                + " those cases are not looked at while a prompt is changed, and are reported"
                + " apart, so that they say how the prompt does on sentences it was not written"
                + " for.\n\n")
        .append("- Run on %s with `%s`.\n".formatted(date, model))
        .append(
            "- The run cost US$%s by the table of spending, of the dollar a day may cost.\n"
                .formatted(cost.toPlainString()))
        .append(
            "- To run it again, with `OPENAI_API_KEY` in the environment:"
                + " `./mvnw verify -Pevaluation` in `backend/`. It writes this file, and is no"
                + " part of the build or of the pipeline.\n");
    for (var suite : suites) {
      report
          .append("\n## %s\n\n".formatted(suite.name()))
          .append(suite.passesWhen())
          .append("\n\n| Cases | Passed | Of |\n| --- | --- | --- |\n")
          .append(row("Worked on", suite.results().stream().filter(one -> !one.heldOut()).toList()))
          .append(row("Held out", suite.results().stream().filter(Result::heldOut).toList()));
      var failed = suite.results().stream().filter(one -> !one.passed()).toList();
      report.append(failed.isEmpty() ? "\nNone failed.\n" : "\n### Failed\n\n");
      for (var one : failed) {
        report
            .append(
                "- `%s` (%s): %s\n"
                    .formatted(one.id(), one.heldOut() ? "held out" : "worked on", one.asked()))
            .append("  - expected: %s\n".formatted(one.expected()))
            .append("  - came: %s\n".formatted(one.came()));
      }
    }
    return report.toString();
  }

  private static String row(String set, List<Result> results) {
    return "| %s | %d | %d |\n"
        .formatted(set, results.stream().filter(Result::passed).count(), results.size());
  }
}
