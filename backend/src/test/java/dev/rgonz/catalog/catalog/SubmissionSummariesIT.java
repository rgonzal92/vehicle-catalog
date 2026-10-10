package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the summary that the language model writes of a submitted catalog: that a submit expects
 * one, that the worker writes it once, that one which names what the changes do not hold is thrown
 * away, and who reads it. A stand-in answers for the model. Each test starts from the seeded
 * catalogs and a working copy of Compact SUV 2026 that Ana owns, in which the 2.0L turbo has become
 * Standard on Sport in North America.
 */
class SubmissionSummariesIT extends WorkingCopyTests {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired ApplicationContext application;
  @Autowired SubmissionSummaries summaries;

  /** Ana's working copy. */
  private long copy;

  /** The name the library has for the 2.0L turbo. */
  private String turbo;

  @BeforeEach
  void anasWorkingCopyWithAChange() throws Exception {
    seedLibraryAndCatalogs();
    Worker.forgets(application);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(setCells(ana(), copy, rev(), cell("ENGINE_20T_I4", "Sport", "NA", "S")))
        .hasStatusOk();
    turbo =
        jdbc.sql("SELECT name FROM feature WHERE code = 'ENGINE_20T_I4'")
            .query(String.class)
            .single();
  }

  @AfterEach
  void nothingSpentAndNoJobs() {
    jdbc.sql("DELETE FROM ai_spend").update();
    Worker.forgets(application);
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  private String rev() {
    return "\"" + revision(copy) + "\"";
  }

  private MvcTestResult submit() {
    return edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), rev(), "{}");
  }

  private MvcTestResult summary(RequestPostProcessor who) {
    return mvc.get().uri("/api/catalogs/{id}/summary", copy).with(who).exchange();
  }

  private Map<String, Object> summaryAsMiaReadsIt() {
    return ApplicationIT.<Map<String, Object>>read(summary(mia()), "$");
  }

  private static String saying(String headline, String... bullets) {
    return JSON.writeValueAsString(Map.of("headline", headline, "bullets", List.of(bullets)));
  }

  private String jobs() {
    return jdbc.sql(
            """
            SELECT coalesce(string_agg(dedupe_key || ' ' || status, ', ' ORDER BY id), 'none')
            FROM job WHERE type = 'SUMMARISE_SUBMISSION'
            """)
        .query(String.class)
        .single();
  }

  @Test
  void aSubmitExpectsASummaryAndLeavesOneJobForItAndARefusedSubmitLeavesNone() {
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), "\"99\"", "{}"))
        .as("a submit that names another revision")
        .hasStatus(412);
    assertThat(jobs()).isEqualTo("none");
    assertThat(summary(ana())).as("nothing is submitted").hasStatus(404);

    assertThat(submit()).hasStatusOk();

    assertThat(jobs()).isEqualTo("summary:%d:%d QUEUED".formatted(copy, revision(copy)));
    assertThat(summaryAsMiaReadsIt())
        .containsEntry("status", "PENDING")
        .containsEntry("headline", null)
        .containsEntry("bullets", List.of());
  }

  @Test
  void theWorkerHasTheModelSummariseTheChangesAndTheReviewerReadsIt() {
    ModelStandIn.says(
        saying(
            "One engine becomes standard", turbo + " becomes Standard on Sport in North America."),
        1500,
        40);
    submit();

    Worker.runs(application);

    assertThat(summaryAsMiaReadsIt())
        .containsEntry("status", "READY")
        .containsEntry("headline", "One engine becomes standard")
        .containsEntry("bullets", List.of(turbo + " becomes Standard on Sport in North America."))
        .containsEntry("reason", null);
    assertThat(jobs()).endsWith("SUCCEEDED");
    var asked = ModelStandIn.asked();
    assertThat(asked).hasSize(1);
    var request = asked.getFirst();
    assertThat(request.required("max_completion_tokens").asInt()).isEqualTo(400);
    assertThat(request.required("reasoning_effort").asString()).isEqualTo("none");
    var given = request.required("messages").get(1).required("content");
    var changes =
        JSON.readTree(
            given.isString() ? given.asString() : given.get(0).required("text").asString());
    assertThat(changes.required("cellsChanged").toString())
        .as("the changes, as the application computed them")
        .contains(
            "\"featureCode\":\"ENGINE_20T_I4\"", "\"trim\":\"Sport\"", "\"regionCode\":\"NA\"");
    assertThat(
            jdbc.sql("SELECT purpose || ' ' || (user_id IS NULL) || ' ' || spent FROM ai_spend")
                .query(String.class)
                .single())
        .as("what it cost, which no account pays for")
        .isEqualTo("SUBMISSION_SUMMARY true 0.00017000");
  }

  @Test
  void aSummaryThatNamesAFeatureOrATrimOutsideTheChangesIsThrownAway() {
    for (var said :
        List.of(
            saying("Leather comes to Sport", "Leather Seats become Standard on Sport."),
            saying("A change to an engine", turbo + " becomes Standard on Touring."),
            saying("A change", "SEAT_LEATHER is now offered."))) {
      Worker.forgets(application);
      ModelStandIn.forgets();
      jdbc.sql("DELETE FROM submission_summary").update();
      if (count("catalog WHERE id = %d AND status = 'SUBMITTED'", copy) == 1) {
        assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null))
            .hasStatusOk();
      }
      ModelStandIn.says(said);
      submit();

      Worker.runs(application);

      assertThat(summaryAsMiaReadsIt())
          .as(said)
          .containsEntry("status", "UNAVAILABLE")
          .containsEntry("headline", null)
          .containsEntry(
              "reason",
              "The summary named a feature or a trim that the changes do not hold, so it was thrown"
                  + " away.");
    }
  }

  @Test
  void whenTheModelCannotBeAskedOrDoesNotAnswerThereIsNoSummaryAndItSaysWhy() {
    ModelStandIn.fails();
    submit();

    Worker.runs(application);

    assertThat(summaryAsMiaReadsIt())
        .containsEntry("status", "UNAVAILABLE")
        .containsEntry("reason", "The model did not answer.");
    assertThat(jobs()).as("the job is done, and is not tried again").endsWith("SUCCEEDED");
    assertThat(ModelStandIn.asked()).hasSize(1);

    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null))
        .hasStatusOk();
    ModelStandIn.forgets();
    jdbc.sql(
            """
            INSERT INTO ai_spend (day, purpose, reserved)
            VALUES ((now() AT TIME ZONE 'UTC')::date, 'A_TEST', 1.00)
            """)
        .update();
    submit();

    Worker.runs(application);

    assertThat(summaryAsMiaReadsIt())
        .as("the summary of the catalog as it was submitted again")
        .containsEntry("status", "UNAVAILABLE")
        .containsEntry(
            "reason", "Today's allowance for the model is spent. It renews at 00:00 UTC.");
    assertThat(ModelStandIn.asked()).as("the model was not asked").isEmpty();
  }

  @Test
  void anAnswerThatIsNoSummaryLeavesNone() {
    ModelStandIn.says("not JSON at all");
    submit();

    Worker.runs(application);

    assertThat(summaryAsMiaReadsIt())
        .containsEntry("status", "UNAVAILABLE")
        .containsEntry("reason", "The model's answer was not a summary.");
  }

  @Test
  void aMessageThatIsDeliveredAgainAsksTheModelForNothing() {
    ModelStandIn.says(saying("One engine becomes standard"));
    submit();
    Worker.runs(application);

    summaries.handle(
        JSON.valueToTree(
            Map.of(
                SubmissionSummaries.CATALOG, copy, SubmissionSummaries.REVISION, revision(copy))));

    assertThat(ModelStandIn.asked()).hasSize(1);
    assertThat(summaryAsMiaReadsIt()).containsEntry("status", "READY");
  }

  @Test
  void aCatalogSubmittedAgainHasANewSummaryAndTheOldOneIsNotShown() {
    ModelStandIn.says(saying("The first summary"));
    submit();
    Worker.runs(application);
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null))
        .hasStatusOk();
    assertThat(summary(ana())).as("a Draft has no summary").hasStatus(404);

    ModelStandIn.says(saying("The second summary"));
    submit();

    assertThat(summaryAsMiaReadsIt()).containsEntry("status", "PENDING");
    Worker.runs(application);
    assertThat(summaryAsMiaReadsIt()).containsEntry("headline", "The second summary");
  }

  @Test
  void itsOwnerAndAReviewerReadItAndNoOneElse() {
    submit();

    assertThat(summary(ana())).as("its owner").hasStatusOk();
    assertThat(summary(mia())).as("a manager").hasStatusOk();
    assertThat(summary(signedInAs(Role.ADMIN, "an-admin"))).as("an admin").hasStatusOk();
    assertThat(summary(ben())).as("another author").hasStatus(404);
    assertThat(mvc.get().uri("/api/catalogs/{id}/summary", copy)).hasStatus(401);
  }
}
