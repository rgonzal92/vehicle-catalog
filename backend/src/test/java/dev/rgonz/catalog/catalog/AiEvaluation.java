package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ai.TheRealModel;
import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import dev.rgonz.catalog.catalog.EvaluationReport.Suite;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
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
  String model;

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

    var cost =
        jdbc.sql("SELECT coalesce(sum(coalesce(spent, reserved)), 0) FROM ai_spend")
            .query(BigDecimal.class)
            .single();
    Files.writeString(
        REPORT,
        EvaluationReport.of(
            LocalDate.now(ZoneOffset.UTC), model, cost.stripTrailingZeros(), suites));
  }
}
