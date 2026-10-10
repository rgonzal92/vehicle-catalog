package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ai.Model;
import dev.rgonz.catalog.ai.TheRealModel;
import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import dev.rgonz.catalog.catalog.EvaluationReport.Suite;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;

/**
 * Measures the language model against the cases with known answers, and writes {@code
 * docs/ai-evaluation.md}. It asks the real model, through the application and its allowance, so it
 * costs money and needs a key: it runs with {@code ./mvnw verify -Pevaluation} and {@code
 * OPENAI_API_KEY} in the environment, and never as part of the build, which runs no class of this
 * name.
 */
@Import(TheRealModel.class)
class AiEvaluation extends EvaluationRuns {
  /** Where the report goes, from the directory the build runs in. */
  private static final Path REPORT = Path.of("..", "docs", "ai-evaluation.md");

  @Value("${app.ai.model}")
  String modelName;

  @Value("${app.documents.least-likeness}")
  double leastLikeness;

  @Autowired Model model;

  @Test
  void measuresTheModelAndWritesTheReport() throws Exception {
    assertThat(System.getenv("OPENAI_API_KEY"))
        .as("OPENAI_API_KEY in the environment, which asking the real model needs")
        .isNotBlank();
    jdbc.sql("DELETE FROM ai_spend").update();
    var suites = new ArrayList<Suite>();

    seedLibraryAndCatalogs();
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    suites.add(
        new Suite(
            "Rule suggestions",
            RuleSuggestionCases.PASSES_WHEN,
            RuleSuggestionCases.all().stream().map(one -> suggested(copy, one)).toList()));

    var summaries = new ArrayList<Result>();
    for (var one : SummaryCases.all()) {
      summaries.add(summarised(one));
    }
    suites.add(new Suite("Summaries", SummaryCases.PASSES_WHEN, summaries));

    seedLibraryAndCatalogs();
    long withATrimOfItsOwn = workingCopyWithATrimOfItsOwn();
    suites.add(
        new Suite(
            "The analyst",
            AnalystCases.PASSES_WHEN,
            AnalystCases.all().stream().map(one -> answered(one, withATrimOfItsOwn)).toList()));

    seedLibraryAndCatalogs();
    theNotesAreReady();
    var aboutDocuments = DocumentCases.all().cases();
    suites.add(
        new Suite(
            "Answers from documents",
            DocumentCases.PASSES_WHEN,
            aboutDocuments.stream().map(this::answeredFromDocuments).toList(),
            howAlikeTheClosestPassagesAre(aboutDocuments)));

    var cost =
        jdbc.sql("SELECT coalesce(sum(coalesce(spent, reserved)), 0) FROM ai_spend")
            .query(BigDecimal.class)
            .single();
    Files.writeString(
        REPORT,
        EvaluationReport.of(
            LocalDate.now(ZoneOffset.UTC), modelName, cost.stripTrailingZeros(), suites));
  }

  /**
   * How alike the closest passage of the chosen notes is to each question that is worked on, told
   * apart by whether the notes answer the question. It is what the least likeness a search returns
   * a passage for is set from, and the held-out questions have no part in it.
   */
  private String howAlikeTheClosestPassagesAre(List<DocumentCases.Case> cases) {
    var covered = new ArrayList<Double>();
    var notCovered = new ArrayList<Double>();
    for (var one : cases) {
      if (one.heldOut() || !one.tools().isEmpty()) {
        continue;
      }
      var meaning = model.meaningsOf(null, "EVALUATION", List.of(one.question())).getFirst();
      var numbers = new StringBuilder("[");
      for (int at = 0; at < meaning.length; at++) {
        numbers.append(at == 0 ? "" : ",").append(meaning[at]);
      }
      double closest =
          jdbc.sql(
                  """
                  SELECT max(1 - (p.meaning <=> :meaning::vector))
                  FROM document_passage p
                  JOIN document d ON d.id = p.document_id
                  WHERE d.vehicle_line_id = :line AND d.model_year = :year
                  """)
              .param("meaning", numbers.append(']').toString())
              .param("line", line(one.vehicleLine()))
              .param("year", one.modelYear())
              .query(Double.class)
              .single();
      (one.cites().isEmpty() ? notCovered : covered).add(closest);
    }
    return ("How alike the closest passage of the chosen notes is to a question, from 0 to 1, for"
            + " the cases that are worked on: %.2f to %.2f where the notes answer the question,"
            + " and %.2f to %.2f where they do not. A search returns no passage that is less"
            + " alike than %.2f.")
        .formatted(
            covered.stream().mapToDouble(Double::doubleValue).min().orElse(0),
            covered.stream().mapToDouble(Double::doubleValue).max().orElse(0),
            notCovered.stream().mapToDouble(Double::doubleValue).min().orElse(0),
            notCovered.stream().mapToDouble(Double::doubleValue).max().orElse(0),
            leastLikeness);
  }
}
