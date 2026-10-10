package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ai.TheRealModel;
import dev.rgonz.catalog.catalog.EvaluationReport.Suite;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures the language model against the cases with known answers, and writes {@code
 * docs/ai-evaluation.md}. It asks the real model, through the application and its allowance, so it
 * costs money and needs a key: it runs with {@code ./mvnw verify -Pevaluation} and {@code
 * OPENAI_API_KEY} in the environment, and never as part of the build, which runs no class of this
 * name.
 */
@Import(TheRealModel.class)
class AiEvaluation extends WorkingCopyTests {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Where the report goes, from the directory the build runs in. */
  private static final Path REPORT = Path.of("..", "docs", "ai-evaluation.md");

  @Value("${app.ai.model}")
  String model;

  @Test
  void measuresTheModelAndWritesTheReport() throws Exception {
    assertThat(System.getenv("OPENAI_API_KEY"))
        .as("OPENAI_API_KEY in the environment, which asking the real model needs")
        .isNotBlank();
    seedLibraryAndCatalogs();
    jdbc.sql("DELETE FROM ai_spend").update();
    var suites = new ArrayList<Suite>();

    suites.add(ruleSuggestions());

    var cost =
        jdbc.sql("SELECT coalesce(sum(coalesce(spent, reserved)), 0) FROM ai_spend")
            .query(BigDecimal.class)
            .single();
    Files.writeString(
        REPORT,
        EvaluationReport.of(
            LocalDate.now(ZoneOffset.UTC), model, cost.stripTrailingZeros(), suites));
  }

  /** A rule suggested from each sentence, for a working copy of the seeded Compact SUV 2026. */
  private Suite ruleSuggestions() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    var results =
        RuleSuggestionCases.all().stream()
            .map(
                one ->
                    RuleSuggestionCases.run(
                        one,
                        sentence -> suggestion(copy, sentence),
                        id ->
                            jdbc.sql("SELECT code FROM feature WHERE id = ?")
                                .param(id)
                                .query(String.class)
                                .single(),
                        id ->
                            jdbc.sql("SELECT name FROM trim WHERE id = ?")
                                .param(id)
                                .query(String.class)
                                .single()))
            .toList();

    return new Suite("Rule suggestions", RuleSuggestionCases.PASSES_WHEN, results);
  }

  private JsonNode suggestion(long copy, String sentence) {
    try {
      return JSON.readTree(
          edit(
                  ana(),
                  mvc.post().uri("/api/catalogs/{id}/rule-suggestions", copy),
                  null,
                  JSON.writeValueAsString(Map.of("sentence", sentence)))
              .getResponse()
              .getContentAsString());
    } catch (java.io.UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
