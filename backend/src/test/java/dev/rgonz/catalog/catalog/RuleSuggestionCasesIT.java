package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import dev.rgonz.catalog.catalog.EvaluationReport.Suite;
import dev.rgonz.catalog.catalog.RuleSuggestionCases.Case;
import dev.rgonz.catalog.catalog.RuleSuggestionCases.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the cases that rule suggestions are measured with, and how a case is judged and reported,
 * with a stand-in that answers for the model: the measuring itself, against the real model, is no
 * part of the build. Each test starts from a working copy of the seeded Compact SUV 2026.
 */
class RuleSuggestionCasesIT extends WorkingCopyTests {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  @AfterEach
  void nothingSpent() {
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  /** What the application answers the sentence with, for Ana's working copy. */
  private JsonNode suggest(String sentence) {
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

  private Result run(Case one) {
    return RuleSuggestionCases.run(
        one,
        this::suggest,
        id ->
            jdbc.sql("SELECT code FROM feature WHERE id = ?")
                .param(id)
                .query(String.class)
                .single(),
        id ->
            jdbc.sql("SELECT name FROM trim WHERE id = ?").param(id).query(String.class).single());
  }

  /** The model's answer that says this rule, or that the sentence says none. */
  private static String saying(Rule rule) {
    var said = new LinkedHashMap<String, Object>();
    said.put("saysOneRule", rule != null);
    said.put("kind", rule == null ? null : rule.kind());
    said.put("source", rule == null ? null : rule.source());
    said.put("targets", rule == null ? List.of() : rule.targets());
    said.put("trims", rule == null ? null : rule.trims());
    said.put("regions", rule == null ? null : rule.regions());
    return JSON.writeValueAsString(said);
  }

  @Test
  void thereAreAtLeastThirtyCasesAThirdOfThemHeldOut() {
    var cases = RuleSuggestionCases.all();

    assertThat(cases).hasSizeGreaterThanOrEqualTo(30);
    assertThat(cases).extracting(Case::id).doesNotHaveDuplicates();
    assertThat(cases.stream().filter(Case::heldOut).count() * 3).isEqualTo(cases.size());
  }

  @Test
  void everyCasePassesWhenTheModelSaysWhatIsExpectedSoEachExpectedRuleIsOneTheCatalogTakes() {
    for (var one : RuleSuggestionCases.all()) {
      ModelStandIn.says(saying(one.expected()));

      var result = run(one);

      assertThat(result.passed()).as("%s, for which came: %s", one.id(), result.came()).isTrue();
    }
  }

  @Test
  void aCaseWhoseExpectedRuleIsWrongIsReportedAsFailedWithWhatWasExpectedAndWhatCame() {
    var right = RuleSuggestionCases.all().getFirst();
    var wrong =
        new Case(
            right.id(),
            true,
            right.sentence(),
            new Rule("REQUIRES", "SEAT_LEATHER", List.of("NAVIGATION"), List.of("Sport"), null));
    ModelStandIn.says(saying(right.expected()));
    ModelStandIn.says(saying(right.expected()));

    var report =
        EvaluationReport.of(
            LocalDate.of(2026, 10, 9),
            "a-model",
            new BigDecimal("0.0123"),
            List.of(
                new Suite(
                    "Rule suggestions",
                    RuleSuggestionCases.PASSES_WHEN,
                    List.of(run(right), run(wrong)))));

    assertThat(report)
        .contains(
            "- Run on 2026-10-09 with `a-model`.",
            "- The run cost US$0.0123 by the table of spending",
            """
            | Cases | Passed | Of |
            | --- | --- | --- |
            | Worked on | 1 | 1 |
            | Held out | 0 | 1 |

            ### Failed

            - `leather-needs-premium-audio` (held out): "Leather seats need premium audio."
              - expected: REQUIRES SEAT_LEATHER -> NAVIGATION; trims: Sport; regions: every one
              - came: REQUIRES SEAT_LEATHER -> AUDIO_PREMIUM; trims: every one; regions: every one
            """);
  }

  @Test
  void anExclusionPassesWhicheverOfItsFeaturesIsItsSourceAndARuleWhereNoneIsExpectedFails() {
    var exclusion =
        new Case(
            "an-exclusion",
            false,
            "The panoramic roof can't be had with crossbars.",
            new Rule("EXCLUDES", "ROOF_PANORAMIC", List.of("ROOF_CROSSBARS"), null, null));
    ModelStandIn.says(
        saying(new Rule("EXCLUDES", "ROOF_CROSSBARS", List.of("ROOF_PANORAMIC"), null, null)));
    assertThat(run(exclusion).passed()).isTrue();

    ModelStandIn.says(saying(exclusion.expected()));
    var none = run(new Case("no-rule", false, "Make the car nicer.", null));
    assertThat(none.passed()).isFalse();
    assertThat(none.expected()).isEqualTo("no rule");

    // No answer of the model's is no rule either, and says why.
    ModelStandIn.fails();
    var failed = run(exclusion);
    assertThat(failed.passed()).isFalse();
    assertThat(failed.came()).isEqualTo("no rule: nothing was said of why");
  }
}
