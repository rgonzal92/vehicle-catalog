package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.time.Duration;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks what follows an approval: a job that the worker runs once, whatever stops in between, as
 * part of the trace of the request that approved, which tells the catalog's owner. Each test starts
 * from the seeded catalogs, a working copy of Compact SUV 2026 that Ana has submitted, and no job
 * or message from before.
 */
@ExtendWith(OutputCaptureExtension.class)
class AfterApprovalIT extends WorkingCopyTests {
  @Autowired ApplicationContext application;

  /** Ana's submitted working copy, at revision 1. */
  private long copy;

  @BeforeEach
  void anasSubmission() throws Exception {
    seedLibraryAndCatalogs();
    jdbc.sql("DELETE FROM notification").update();
    Worker.forgets(application);
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(decide(ana(), "submit", "\"0\"")).hasStatusOk();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void anApprovalWritesOneJobWithItsUnsentMessageAndARefusedOneWritesNeither() {
    assertThat(decide(mia(), "approve", "\"7\"")).as("from another revision").hasStatus(412);
    assertThat(decide(ana(), "approve", "\"1\"")).as("by an author").hasStatus(403);
    assertThat(count("job")).isZero();
    assertThat(count("outbox")).isZero();

    assertThat(decide(mia(), "approve", "\"1\"")).hasStatusOk();

    assertThat(
            jdbc.sql("SELECT type || ' ' || status || ' ' || (subject ->> 'catalogId') FROM job")
                .query(String.class)
                .list())
        .containsExactly("AFTER_APPROVAL QUEUED " + copy);
    assertThat(count("outbox WHERE sent_at IS NULL")).isOne();
    assertThat(notifications()).as("until the worker has run").isZero();
  }

  @Test
  void theWorkerTellsTheOwnerWhoApprovedTheirCatalogAsWhichVersion() {
    decide(mia(), "approve", "\"1\"");

    Worker.runs(application);

    assertThat(
            jdbc.sql(
                    """
                    SELECT kind, payload ->> 'catalog' AS catalog,
                           payload ->> 'vehicleLine' AS vehicle_line,
                           payload ->> 'modelYear' AS model_year,
                           payload ->> 'versionNumber' AS version_number,
                           payload ->> 'reviewer' AS reviewer,
                           (payload ->> 'catalogId')::bigint AS catalog_id,
                           (payload ->> 'lineageId')::bigint = c.lineage_id AS of_its_lineage,
                           n.read_at IS NULL AS unread
                    FROM notification n
                    JOIN catalog c ON c.id = (n.payload ->> 'catalogId')::bigint
                    WHERE n.user_id = :ana
                    """)
                .param("ana", person("ana"))
                .query()
                .singleRow())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "kind", "CATALOG_APPROVED",
                "catalog", "COMPACT_SUV 2026",
                "vehicle_line", "Compact SUV",
                "model_year", "2026",
                "version_number", "3",
                "reviewer", "mia",
                "catalog_id", copy,
                "of_its_lineage", true,
                "unread", true));
    assertThat(count("job WHERE status = 'SUCCEEDED' AND attempts = 1")).isOne();
    assertThat(count("outbox WHERE sent_at IS NULL")).isZero();
    assertThat(count("notification WHERE user_id = %d", person("mia")))
        .as("the reviewer is told nothing")
        .isZero();
  }

  @Test
  void aMessageThatIsSentTwiceTellsTheOwnerOnce() {
    decide(mia(), "approve", "\"1\"");
    Worker.runs(application);

    // A publisher that stops after the queue has taken a message, and before marking it, sends it
    // again when it is started.
    jdbc.sql("UPDATE outbox SET sent_at = NULL").update();
    Worker.runs(application);

    assertThat(notifications()).isOne();
    assertThat(count("job WHERE status = 'SUCCEEDED' AND attempts = 1")).isOne();
    assertThat(Worker.takesAndStops(application)).as("the second message, once handled").isEmpty();
  }

  @Test
  void aWorkerThatIsStoppedInTheMiddleOfAJobLeavesItToBeDoneOnce() {
    decide(mia(), "approve", "\"1\"");
    Worker.sends(application);

    assertThat(Worker.takesAndStops(application)).hasSize(1);
    assertThat(notifications()).isZero();
    assertThat(count("job WHERE status = 'QUEUED'")).isOne();

    // The queue delivers the message again once it has been hidden for its time.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Worker.runs(application);
              assertThat(notifications()).isOne();
            });
    assertThat(count("job WHERE status = 'SUCCEEDED'")).isOne();
  }

  @Test
  void anApiThatIsStoppedBeforeTheMessageIsSentLosesNoJob() {
    decide(mia(), "approve", "\"1\"");

    // The approval is saved and nothing has been sent, which is all a stopped API leaves behind.
    assertThat(Worker.takesAndStops(application)).isEmpty();
    assertThat(count("outbox WHERE sent_at IS NULL")).isOne();

    Worker.runs(application);

    assertThat(notifications()).isOne();
  }

  @Test
  void aMessageWhoseJobIsGoneIsDeletedAndDoesNothing() {
    decide(mia(), "approve", "\"1\"");
    Worker.sends(application);
    jdbc.sql("DELETE FROM job").update();

    Worker.runs(application);

    assertThat(notifications()).isZero();
    await()
        .pollDelay(Duration.ofMillis(1500))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(Worker.takesAndStops(application))
                    .as("the message, once it would have been delivered again")
                    .isEmpty());
  }

  @Test
  void theRequestThatApprovesAndTheJobItCausesAreOneTrace(CapturedOutput output) {
    decide(mia(), "approve", "\"1\"");

    Worker.runs(application);

    var request = traceOf(output, "POST /api/catalogs/%d/approve 200 in \\d+ ms".formatted(copy));
    assertThat(request).as("the trace of the request").isNotNull();
    assertThat(jdbc.sql("SELECT traceparent FROM outbox").query(String.class).single())
        .as("what the message carried")
        .matches("00-" + request + "-[0-9a-f]{16}-[0-9a-f]{2}");
    assertThat(traceOf(output, "AFTER_APPROVAL job \\d+ done in \\d+ ms"))
        .as("the trace of the job")
        .isEqualTo(request);
  }

  @Test
  void aMessageThatCarriesNoTraceIsHandledAllTheSame() {
    decide(mia(), "approve", "\"1\"");
    jdbc.sql("UPDATE outbox SET traceparent = NULL").update();

    Worker.runs(application);

    assertThat(notifications()).isOne();
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

  /** How many notifications Ana has. */
  private long notifications() {
    return count("notification WHERE user_id = %d", person("ana"));
  }

  /** Submits, approves, or rejects Ana's copy as the person, from the revision. */
  private MvcTestResult decide(RequestPostProcessor who, String decision, String revision) {
    return edit(
        who, mvc.post().uri("/api/catalogs/{id}/{decision}", copy, decision), revision, "{}");
  }
}
