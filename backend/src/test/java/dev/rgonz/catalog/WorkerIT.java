package dev.rgonz.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.job.Jobs;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the worker: the same build as the API, run with the profile {@code worker}. It is stopped
 * when these tests are done, so that it runs no job of a test that follows.
 */
@ActiveProfiles("worker")
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerIT extends ApplicationIT {
  static {
    // The API migrates the database before a worker starts. Here nothing else may have.
    Flyway.configure()
        .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
        .load()
        .migrate();
  }

  @Autowired ApplicationContext application;
  @Autowired Jobs jobs;
  @Autowired TransactionTemplate transactions;

  @Test
  void itAnswersItsHealthCheckAndHasNothingAtAnyOtherAddress() {
    assertThat(mvc.get().uri("/api/health/readiness")).hasStatusOk();

    assertThat(mvc.get().uri("/api/me")).hasStatus(404);
    assertThat(mvc.get().uri("/api/lineages").with(signedInAs(Role.ADMIN))).hasStatus(404);
    assertThat(mvc.post().uri("/api/catalogs").with(signedInAs(Role.ADMIN)).with(csrfToken()))
        .hasStatus(404);
    assertThat(mvc.get().uri("/api/oauth2/authorization/cognito")).hasStatus(404);
    assertThat(mvc.get().uri("/")).hasStatus(404);
  }

  @Test
  void itNeitherMigratesTheDatabaseNorResetsTheDemo() {
    assertThat(application.getBeanProvider(Flyway.class).getIfAvailable()).isNull();
    assertThat(application.containsBean("demoReset")).isFalse();
  }

  @Test
  void itSendsAndRunsAJobWithoutBeingAskedAndWritesALineForItWithATraceId(CapturedOutput output) {
    // The catalog this job is about does not exist, so there is no one to tell and nothing else
    // to do: the job is done once the worker has got round to it.
    transactions.executeWithoutResult(
        change ->
            jobs.queue(JobType.AFTER_APPROVAL, "for-the-worker-to-find", Map.of("catalogId", -1)));

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.sql(
                                """
                                SELECT status FROM job WHERE dedupe_key = 'for-the-worker-to-find'
                                """)
                            .query(String.class)
                            .single())
                    .isEqualTo("SUCCEEDED"));
    await()
        .untilAsserted(
            () ->
                assertThat(output)
                    .containsPattern(
                        Pattern.compile(
                            "\\[[0-9a-f]{32}-[0-9a-f]{16}\\].* : AFTER_APPROVAL job \\d+ done in"
                                + " \\d+ ms$",
                            Pattern.MULTILINE)));
  }
}
