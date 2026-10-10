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

  private static DocumentCases.Case documentCase(String id) {
    return DocumentCases.all().cases().stream()
        .filter(one -> one.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void thereAreAtLeastTwelveQuestionsAboutDocumentsAThirdHeldOutAndEachNamesNotesThatAreThere() {
    var kept = DocumentCases.all();
    var titles = kept.documents().stream().map(DocumentCases.Note::title).toList();

    assertThat(kept.cases()).hasSizeGreaterThanOrEqualTo(12);
    assertThat(kept.cases()).extracting(DocumentCases.Case::id).doesNotHaveDuplicates();
    assertThat(kept.cases().stream().filter(DocumentCases.Case::heldOut).count() * 3)
        .isEqualTo(kept.cases().size());
    assertThat(titles).doesNotHaveDuplicates();
    for (var one : kept.cases()) {
      assertThat(titles).as("the notes %s names", one.id()).containsAll(one.cites());
      assertThat(titles).as("the notes %s forbids", one.id()).containsAll(one.mustNotCite());
      // What an answer has to cite is a note of the vehicle line and model year it is asked of,
      // and what it must not cite is a note of another.
      for (var note : kept.documents()) {
        var ofTheSame =
            note.vehicleLine().equals(one.vehicleLine()) && note.modelYear() == one.modelYear();
        if (one.cites().contains(note.title())) {
          assertThat(ofTheSame)
              .as("%s is asked of what %s is about", one.id(), note.title())
              .isTrue();
        }
        if (one.mustNotCite().contains(note.title())) {
          assertThat(ofTheSame)
              .as("%s is asked of what %s is about", one.id(), note.title())
              .isFalse();
        }
      }
    }
    // Some ask what the chosen documents do not cover, and some what a catalog offers.
    assertThat(kept.cases())
        .filteredOn(one -> one.cites().isEmpty() && one.tools().isEmpty())
        .isNotEmpty();
    assertThat(kept.cases()).filteredOn(one -> !one.tools().isEmpty()).isNotEmpty();
  }

  @Test
  void aQuestionAboutDocumentsPassesOnlyWhenItsAnswerCitesWhatIsExpectedAndNothingForbidden() {
    theNotesAreReady();
    var hybrid = documentCase("why-the-hybrid-came-later");

    // The stand-in searches with words of the launch notes, and marks the first passage it is
    // given, which is theirs.
    ModelStandIn.asksFor(
        "search_documents",
        "{\"query\": \"The hybrid powertrain was not part of the launch content.\"}");
    ModelStandIn.says("The battery plant reached full output only in September [1].");
    var right = answeredFromDocuments(hybrid);
    assertThat(right.passed()).as(right.came()).isTrue();
    assertThat(right.came())
        .startsWith("cited Compact SUV 2026: launch notes; called search_documents;");

    // Without a mark there is no citation, so what is expected is not cited.
    ModelStandIn.says("The battery plant reached full output only in September.");
    assertThat(answeredFromDocuments(hybrid).passed()).as("without the citation").isFalse();

    // An answer cannot cite a note of another model year, since none is ever found for it. One
    // that did would fail.
    var citingAnotherYear =
        JSON.readTree(
            """
            {
              "answer": "Asia was added [1] [2].",
              "toolCalls": [{"tool": "search_documents", "arguments": "{}"}],
              "citations": [
                {"number": 1, "title": "Compact SUV 2026: launch notes"},
                {"number": 2, "title": "Compact SUV 2027: carryover notes"}
              ]
            }
            """);
    assertThat(DocumentCases.judge(hybrid, citingAnotherYear).passed()).isFalse();
  }

  @Test
  void aQuestionThatExpectsNothingCitedFailsWhenAnythingIsAndOneAboutACatalogNeedsItsTool() {
    theNotesAreReady();
    var notCovered = documentCase("not-covered-at-all");
    var offered = documentCase("what-the-catalog-offers");

    ModelStandIn.asksFor("search_documents", "{\"query\": \"towing capacity\"}");
    ModelStandIn.says("The documents do not cover that.");
    var admitted = answeredFromDocuments(notCovered);
    assertThat(admitted.passed()).as(admitted.came()).isTrue();

    ModelStandIn.asksFor(
        "search_documents", "{\"query\": \"The panoramic roof is kept to the Touring trim.\"}");
    ModelStandIn.says("It can tow a great deal [1].");
    assertThat(answeredFromDocuments(notCovered).passed()).as("with a citation").isFalse();

    ModelStandIn.asksFor("feature_availability", "{\"feature\": \"NAVIGATION\"}");
    ModelStandIn.says("Navigation is not offered on Sport in Europe.");
    var lookedUp = answeredFromDocuments(offered);
    assertThat(lookedUp.passed()).as(lookedUp.came()).isTrue();

    ModelStandIn.says("Navigation is not offered on Sport in Europe.");
    assertThat(answeredFromDocuments(offered).passed()).as("without the catalog's tool").isFalse();
  }
}
