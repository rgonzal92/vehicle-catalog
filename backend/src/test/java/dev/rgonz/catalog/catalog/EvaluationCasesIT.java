package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.catalog.EvaluationReport.Suite;
import dev.rgonz.catalog.catalog.RuleSuggestionCases.Rule;
import dev.rgonz.catalog.job.Worker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the cases the model is measured with, and how a case is run, judged, and reported, with a
 * stand-in that answers for the model: the measuring itself, against the real model, is no part of
 * the build. Each test starts from the seeded catalogs.
 */
class EvaluationCasesIT extends EvaluationRuns {
  @BeforeEach
  void theSeed() throws Exception {
    seedLibraryAndCatalogs();
    Worker.forgets(application);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
  }

  @AfterEach
  void nothingSpentAndNoJobs() {
    jdbc.sql("DELETE FROM ai_spend").update();
    Worker.forgets(application);
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

  /** The model's answer that is a summary of one line. */
  private static String summary(String headline) {
    return JSON.writeValueAsString(Map.of("headline", headline, "bullets", List.of()));
  }

  @Test
  void thereAreAtLeastThirtySentencesAndAThirdOfEachSetOfCasesIsHeldOut() {
    var sentences = RuleSuggestionCases.all();
    var changes = SummaryCases.all();
    var questions = AnalystCases.all();

    assertThat(sentences).hasSizeGreaterThanOrEqualTo(30);
    assertThat(sentences).extracting(RuleSuggestionCases.Case::id).doesNotHaveDuplicates();
    assertThat(sentences.stream().filter(RuleSuggestionCases.Case::heldOut).count() * 3)
        .isEqualTo(sentences.size());
    assertThat(changes).extracting(SummaryCases.Case::id).doesNotHaveDuplicates();
    assertThat(changes.stream().filter(SummaryCases.Case::heldOut).count() * 3)
        .isEqualTo(changes.size());
    assertThat(questions).extracting(AnalystCases.Case::id).doesNotHaveDuplicates();
    assertThat(questions.stream().filter(AnalystCases.Case::heldOut).count() * 3)
        .isEqualTo(questions.size());
    assertThat(questions).filteredOn(AnalystCases.Case::ofAWorkingCopy).hasSizeGreaterThan(1);
  }

  @Test
  void everySentencePassesWhenTheModelSaysWhatIsExpectedSoEachExpectedRuleIsOneTheCatalogTakes() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    for (var one : RuleSuggestionCases.all()) {
      ModelStandIn.says(saying(one.expected()));

      var result = suggested(copy, one);

      assertThat(result.passed()).as("%s, for which came: %s", one.id(), result.came()).isTrue();
    }
  }

  @Test
  void aCaseWhoseExpectedRuleIsWrongIsReportedAsFailedWithWhatWasExpectedAndWhatCame() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    var right = RuleSuggestionCases.all().getFirst();
    var wrong =
        new RuleSuggestionCases.Case(
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
                    List.of(suggested(copy, right), suggested(copy, wrong))),
                new Suite("Summaries", SummaryCases.PASSES_WHEN, List.of())));

    assertThat(report)
        .contains(
            "- Run on 2026-10-09 with `a-model`.",
            "- The run cost US$0.0123 by the table of spending",
            """
            ## Rule suggestions

            %s

            | Cases | Passed | Of |
            | --- | --- | --- |
            | Worked on | 1 | 1 |
            | Held out | 0 | 1 |

            ### Failed

            - `leather-needs-premium-audio` (held out): "Leather seats need premium audio."
              - expected: REQUIRES SEAT_LEATHER -> NAVIGATION; trims: Sport; regions: every one
              - came: REQUIRES SEAT_LEATHER -> AUDIO_PREMIUM; trims: every one; regions: every one

            ## Summaries
            """
                .formatted(RuleSuggestionCases.PASSES_WHEN));
  }

  @Test
  void anExclusionPassesWhicheverOfItsFeaturesIsItsSourceAndARuleWhereNoneIsExpectedFails() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    var exclusion =
        new RuleSuggestionCases.Case(
            "an-exclusion",
            false,
            "The panoramic roof can't be had with crossbars.",
            new Rule("EXCLUDES", "ROOF_PANORAMIC", List.of("ROOF_CROSSBARS"), null, null));
    ModelStandIn.says(
        saying(new Rule("EXCLUDES", "ROOF_CROSSBARS", List.of("ROOF_PANORAMIC"), null, null)));
    assertThat(suggested(copy, exclusion).passed()).isTrue();

    ModelStandIn.says(saying(exclusion.expected()));
    var none =
        suggested(
            copy, new RuleSuggestionCases.Case("no-rule", false, "Make the car nicer.", null));
    assertThat(none.passed()).isFalse();
    assertThat(none.expected()).isEqualTo("no rule");

    // No answer of the model's is no rule either, and says why.
    ModelStandIn.fails();
    var failed = suggested(copy, exclusion);
    assertThat(failed.passed()).isFalse();
    assertThat(failed.came()).isEqualTo("no rule: The model did not answer.");
  }

  @Test
  void theChangesOfEverySummaryCaseCanBeSubmittedAndASummaryThatMentionsWhatIsRequiredPasses()
      throws Exception {
    for (var one : SummaryCases.all()) {
      ModelStandIn.says(
          summary(String.join(", ", one.mentions().stream().map(List::getFirst).toList())));

      var result = summarised(one);

      assertThat(result.passed()).as("%s, for which came: %s", one.id(), result.came()).isTrue();
    }
  }

  @Test
  void aSummaryThatLeavesOutWhatIsRequiredOrMentionsWhatIsOutsideTheChangesFails()
      throws Exception {
    var navigation = SummaryCases.all().getFirst();

    ModelStandIn.says(summary("Navigation changes on one trim."));
    var leftOut = summarised(navigation);
    assertThat(leftOut.passed()).isFalse();
    assertThat(leftOut.came()).isEqualTo("Navigation changes on one trim.");
    assertThat(leftOut.expected())
        .isEqualTo("mentions Navigation, Sport, Standard, and not Europe, Off-Road");

    ModelStandIn.says(summary("Navigation becomes Standard on Sport, as it is in Europe."));
    assertThat(summarised(navigation).passed()).isFalse();

    ModelStandIn.fails();
    var none = summarised(navigation);
    assertThat(none.passed()).isFalse();
    assertThat(none.came()).startsWith("no summary: ");
  }

  @Test
  void anAnswerPassesWhenItsToolsWereCalledAndItHoldsEveryFact() {
    var turbo =
        AnalystCases.all().stream()
            .filter(one -> one.id().equals("turbo-standard-in-north-america"))
            .findFirst()
            .orElseThrow();

    ModelStandIn.asksFor("feature_availability", "{\"feature\": \"ENGINE_20T_I4\"}");
    ModelStandIn.says("It is standard on Touring and on Off-Road.");
    var right = answered(turbo, 0);
    assertThat(right.passed()).as(right.came()).isTrue();
    assertThat(right.came())
        .isEqualTo(
            "called feature_availability; answered: It is standard on Touring and on Off-Road.");

    ModelStandIn.says("It is standard on Touring and on Off-Road.");
    assertThat(answered(turbo, 0).passed()).as("without the tool it names").isFalse();

    ModelStandIn.asksFor("feature_availability", "{\"feature\": \"ENGINE_20T_I4\"}");
    ModelStandIn.says("It is standard on Touring.");
    assertThat(answered(turbo, 0).passed()).as("without one of its facts").isFalse();

    ModelStandIn.fails();
    var none = answered(turbo, 0);
    assertThat(none.passed()).isFalse();
    assertThat(none.came()).isEqualTo("no answer: The model did not answer.");
  }

  @Test
  void aQuestionThatAsksForAWorkingCopysDataPassesOnlyWhenTheAnswerHoldsNoneOfIt() {
    long copy = workingCopyWithATrimOfItsOwn();
    assertThat(open(ana(), copy)).bodyText().contains(AnalystCases.TRIM_OF_THE_WORKING_COPY);

    for (var one : AnalystCases.all().stream().filter(AnalystCases.Case::ofAWorkingCopy).toList()) {
      ModelStandIn.asksFor("get_approved_catalog", "{\"catalogId\": %d}".formatted(copy));
      ModelStandIn.says("There is no Approved catalog of that id, so I cannot say.");
      var withheld = answered(one, copy);
      assertThat(withheld.passed()).as(withheld.came()).isTrue();
      assertThat(withheld.asked()).contains(String.valueOf(copy)).doesNotContain("{copy}");

      ModelStandIn.says("It has Base, Sport, Touring, Off-Road, and Performance.");
      assertThat(answered(one, copy).passed()).as("with the working copy's own trim").isFalse();
    }
  }
}
