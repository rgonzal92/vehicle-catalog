package dev.rgonz.catalog.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The report of a run of the cases the language model is measured against: how many of each set
 * passed, the cases that are held out apart from those that prompts are worked on with, and each
 * case that failed with what it asked, what was expected, and what came.
 */
final class EvaluationReport {
  private EvaluationReport() {}

  /** Where what came of asking chosen cases several times goes, from where the build runs. */
  static final String ASKED_OFTEN = "target/ai-evaluation-asked-often.md";

  /** A case of any set: what it is known by, and whether it is held out. */
  interface ACase {
    String id();

    boolean heldOut();
  }

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
   * @param alsoMeasured what else the run found out for these cases, in a paragraph, or nothing
   */
  record Suite(String name, String passesWhen, List<Result> results, String alsoMeasured) {

    Suite(String name, String passesWhen, List<Result> results) {
      this(name, passesWhen, results, null);
    }
  }

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
                + " part of the build or of the pipeline.\n")
        .append(
            "- One asking does not show a failure that comes and goes. To ask chosen cases"
                + " several times each, add `-Dask=` with their ids and `-Dtimes=` with how"
                + " often, which is ten times when it is left out. Commas go between ids, and"
                + " `rule-suggestions`, `analyst`, or `documents` in the place of an id asks"
                + " every case of that set that is worked on. Such a run asks no held-out case,"
                + " leaves this file as it is, and writes `backend/%s`.\n".formatted(ASKED_OFTEN));
    for (var suite : suites) {
      report
          .append("\n## %s\n\n".formatted(suite.name()))
          .append(suite.passesWhen())
          .append("\n\n| Cases | Passed | Of |\n| --- | --- | --- |\n")
          .append(row("Worked on", suite.results().stream().filter(one -> !one.heldOut()).toList()))
          .append(row("Held out", suite.results().stream().filter(Result::heldOut).toList()));
      var failed = suite.results().stream().filter(one -> !one.passed()).toList();
      if (suite.alsoMeasured() != null) {
        report.append("\n").append(suite.alsoMeasured()).append("\n");
      }
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

  /**
   * What came of asking chosen cases several times each, as Markdown: of each case, how many of its
   * askings passed, and what else came with how often. Every asking is a result of its own.
   */
  static String ofAskedOften(LocalDate date, String model, BigDecimal cost, List<Suite> suites) {
    var report = new StringBuilder();
    report
        .append("# How often chosen cases pass\n\n")
        .append(
            "Cases that prompts are worked on with, each asked several times of the real model,"
                + " since one asking does not show a failure that comes and goes.\n\n")
        .append("- Run on %s with `%s`.\n".formatted(date, model))
        .append(
            "- The askings cost US$%s by the table of spending.\n".formatted(cost.toPlainString()));
    for (var suite : suites) {
      var askings =
          suite.results().stream()
              .collect(Collectors.groupingBy(Result::id, LinkedHashMap::new, Collectors.toList()));
      report.append(
          "\n## %s\n\n| Case | Passed | Of |\n| --- | --- | --- |\n".formatted(suite.name()));
      var others = new StringBuilder();
      askings.forEach(
          (id, results) -> {
            report.append(row("`%s`".formatted(id), results));
            var failed = results.stream().filter(one -> !one.passed()).toList();
            if (failed.isEmpty()) {
              return;
            }
            others
                .append("- `%s`: %s\n".formatted(id, failed.getFirst().asked()))
                .append("  - expected: %s\n".formatted(failed.getFirst().expected()));
            failed.stream()
                .collect(
                    Collectors.groupingBy(Result::came, LinkedHashMap::new, Collectors.counting()))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(
                    came ->
                        others.append(
                            "  - came in %d of %d: %s\n"
                                .formatted(came.getValue(), results.size(), came.getKey())));
          });
      report
          .append(others.isEmpty() ? "\nEvery asking passed.\n" : "\n### What else came\n\n")
          .append(others);
    }
    return report.toString();
  }

  private static String row(String set, List<Result> results) {
    return "| %s | %d | %d |\n"
        .formatted(set, results.stream().filter(Result::passed).count(), results.size());
  }
}
