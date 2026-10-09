package dev.rgonz.catalog.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks what becomes of a job whose work fails: it is tried again, it has failed for good once the
 * queue gives its message up, and an admin sees it and can have it retried. A job fails here
 * because nothing does a job of its type, and is put right by giving it a type something does. The
 * queue of the tests delivers a message three times, a second apart.
 */
@ExtendWith(OutputCaptureExtension.class)
class FailedJobsIT extends ApplicationIT {
  @Autowired ApplicationContext application;

  @BeforeEach
  void noJobs() {
    Worker.forgets(application);
  }

  /** Writes a job that nothing does, with its message, and answers with its id. */
  private long aJobThatNothingDoes() {
    return jdbc.sql(
            """
            WITH queued AS (
                INSERT INTO job (type, dedupe_key, subject)
                VALUES ('WORK_NOTHING_DOES', 'a-job-' || gen_random_uuid(), '{"catalogId": -1}')
                RETURNING id
            ), written AS (
                INSERT INTO outbox (job_id, payload)
                SELECT id, jsonb_build_object('jobId', id) FROM queued
            )
            SELECT id FROM queued
            """)
        .query(Long.class)
        .single();
  }

  /** Gives the job a type that something does: what follows an approval of no catalog at all. */
  private void putRight(long job) {
    jdbc.sql("UPDATE job SET type = 'AFTER_APPROVAL' WHERE id = :id").param("id", job).update();
  }

  private String stands(long job) {
    return jdbc.sql("SELECT status || ' after ' || attempts FROM job WHERE id = :id")
        .param("id", job)
        .query(String.class)
        .single();
  }

  /**
   * Has the worker's steps done until the job stands as it should, each delivery a second apart.
   */
  private void workUntil(long job, String standing) {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              Worker.runs(application);
              assertThat(stands(job)).isEqualTo(standing);
            });
  }

  /** Lets the queue give up the message of a job that has failed for good. */
  private void untilItsMessageIsGivenUp() {
    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              Worker.runs(application);
              assertThat(Worker.inTheDeadLetterQueue(application)).hasSize(1);
            });
  }

  @Test
  void aJobThatFailsOnceAndThenSucceedsIsDoneWithTwoAttempts() {
    var job = aJobThatNothingDoes();

    Worker.runs(application);

    assertThat(stands(job)).isEqualTo("QUEUED after 1");
    assertThat(jdbc.sql("SELECT error FROM job").query(String.class).single())
        .contains("Nothing here does a job of type WORK_NOTHING_DOES");

    putRight(job);
    workUntil(job, "SUCCEEDED after 2");

    assertThat(jdbc.sql("SELECT error FROM job").query(String.class).optional())
        .as("what the last try said, which did not fail")
        .isEmpty();
    assertThat(Worker.inTheDeadLetterQueue(application)).isEmpty();
  }

  @Test
  void aJobThatFailsEveryTimeHasFailedAfterThreeDeliveriesAndItsMessageIsGivenUp() {
    var job = aJobThatNothingDoes();

    workUntil(job, "FAILED after 3");
    untilItsMessageIsGivenUp();

    assertThat(Worker.inTheDeadLetterQueue(application))
        .containsExactly("{\"jobId\": %d}".formatted(job));
    assertThat(stands(job)).as("which is not delivered again").isEqualTo("FAILED after 3");
    assertThat(Worker.sweeps(application)).as("a job that is still failed stays there").isZero();
    assertThat(Worker.inTheDeadLetterQueue(application)).hasSize(1);
  }

  @Test
  void aFailedJobIsRetriedOnceItsCauseIsPutRightAndItsMessageLeavesTheDeadLetterQueue() {
    var job = aJobThatNothingDoes();
    workUntil(job, "FAILED after 3");
    untilItsMessageIsGivenUp();
    putRight(job);

    var retried = retry(Role.ADMIN, job);

    assertThat(retried).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("QUEUED");
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE sent_at IS NULL")
                .query(Long.class)
                .single())
        .as("a new message, written with the retry")
        .isOne();
    workUntil(job, "SUCCEEDED after 4");
    assertThat(Worker.sweeps(application)).isOne();
    assertThat(Worker.inTheDeadLetterQueue(application)).isEmpty();

    assertThat(retry(Role.ADMIN, job))
        .as("a job that has not failed")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NOT_FAILED");
    assertThat(retry(Role.ADMIN, job + 1000)).as("no job at all").hasStatus(404);
    assertThat(
            jdbc.sql("SELECT count(*) FROM outbox WHERE sent_at IS NULL")
                .query(Long.class)
                .single())
        .as("and nothing is queued by either")
        .isZero();
  }

  @Test
  void aRetriedJobIsPartOfTheTraceOfItsRetry(CapturedOutput output) {
    var job = aJobThatNothingDoes();
    workUntil(job, "FAILED after 3");
    putRight(job);

    retry(Role.ADMIN, job);
    workUntil(job, "SUCCEEDED after 4");

    var request = traceOf(output, "POST /api/jobs/%d/retry 200 in \\d+ ms".formatted(job));
    assertThat(request).as("the trace of the retry").isNotNull();
    assertThat(traceOf(output, "AFTER_APPROVAL job %d done in \\d+ ms".formatted(job)))
        .as("the trace of the job")
        .isEqualTo(request);
  }

  @Test
  void theMessageOfAJobThatIsGoneLeavesTheDeadLetterQueue() {
    var job = aJobThatNothingDoes();
    workUntil(job, "FAILED after 3");
    untilItsMessageIsGivenUp();

    jdbc.sql("DELETE FROM job").update();

    assertThat(Worker.sweeps(application)).isOne();
    assertThat(Worker.inTheDeadLetterQueue(application)).isEmpty();
  }

  @Test
  void anAdminListsTheJobsNewestFirstAPageAtATimeAndByStatus() {
    var failed = aJobThatNothingDoes();
    workUntil(failed, "FAILED after 3");
    var done = aJobThatNothingDoes();
    putRight(done);
    workUntil(done, "SUCCEEDED after 1");
    var waiting = aJobThatNothingDoes();

    var all = list(Role.ADMIN, "");

    assertThat(all).hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(3);
    assertThat(ApplicationIT.<List<Integer>>read(all, "$.items[*].id"))
        .containsExactly((int) waiting, (int) done, (int) failed);
    assertThat(ApplicationIT.<Map<String, Object>>read(all, "$.items[2]"))
        .containsEntry("type", "WORK_NOTHING_DOES")
        .containsEntry("status", "FAILED")
        .containsEntry("attempts", 3)
        .containsEntry("subject", Map.of("catalogId", -1))
        .containsKeys("createdAt", "updatedAt");
    assertThat(ApplicationIT.<String>read(all, "$.items[2].error"))
        .contains("Nothing here does a job of type WORK_NOTHING_DOES");
    assertThat(
            ApplicationIT.<List<Integer>>read(list(Role.ADMIN, "?status=FAILED"), "$.items[*].id"))
        .containsExactly((int) failed);
    assertThat(list(Role.ADMIN, "?status=FAILED"))
        .bodyJson()
        .extractingPath("$.total")
        .isEqualTo(1);
    assertThat(
            ApplicationIT.<List<Integer>>read(list(Role.ADMIN, "?page=1&size=2"), "$.items[*].id"))
        .containsExactly((int) failed);
    assertThat(list(Role.ADMIN, "?status=LOST")).hasStatus(400);
  }

  @Test
  void anAuthorAndAManagerNeitherListTheJobsNorRetryOne() {
    var job = aJobThatNothingDoes();

    for (var role : List.of(Role.AUTHOR, Role.MANAGER)) {
      assertThat(list(role, "")).as("the list, for a %s", role).hasStatus(403);
      assertThat(retry(role, job)).as("a retry, for a %s", role).hasStatus(403);
    }
    assertThat(mvc.get().uri("/api/jobs")).as("without a session").hasStatus(401);
  }

  private MvcTestResult list(Role as, String query) {
    return mvc.get().uri("/api/jobs" + query).with(signedInAs(as)).exchange();
  }

  private MvcTestResult retry(Role as, long job) {
    return mvc.post()
        .uri("/api/jobs/{id}/retry", job)
        .with(signedInAs(as))
        .with(csrfToken())
        .exchange();
  }

  /** The id of the trace in which the last line that reads like this was written, if any was. */
  private static String traceOf(CapturedOutput output, String line) {
    var written =
        Pattern.compile("\\[([0-9a-f]{32})-[0-9a-f]{16}\\].* : " + line + "$", Pattern.MULTILINE)
            .matcher(output.getAll());
    String trace = null;
    while (written.find()) {
      trace = written.group(1);
    }
    return trace;
  }
}
