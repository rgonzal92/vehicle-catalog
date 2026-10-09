package dev.rgonz.catalog.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.ApplicationIT;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Checks how a job is written: with the change that causes it, and once for the same work. */
class JobsIT extends ApplicationIT {
  @Autowired Jobs jobs;
  @Autowired TransactionTemplate transactions;
  @Autowired ApplicationContext application;

  @BeforeEach
  void noJobs() {
    Worker.forgets(application);
  }

  @Test
  void twoChangesThatCallForTheSameWorkWriteOneJobAndOneMessage() {
    var first =
        transactions.execute(
            change -> jobs.queue(JobType.AFTER_APPROVAL, "the-same-work", Map.of("catalogId", 7)));
    var second =
        transactions.execute(
            change -> jobs.queue(JobType.AFTER_APPROVAL, "the-same-work", Map.of("catalogId", 7)));

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(count("job")).isOne();
    assertThat(count("outbox WHERE sent_at IS NULL")).isOne();
    assertThat(
            jdbc.sql("SELECT status || ' ' || (subject ->> 'catalogId') FROM job")
                .query(String.class)
                .single())
        .isEqualTo("QUEUED 7");
  }

  @Test
  void aJobIsWrittenOnlyInTheTransactionOfTheChangeThatCausesIt() {
    assertThatThrownBy(
            () -> jobs.queue(JobType.AFTER_APPROVAL, "outside-a-change", Map.of("catalogId", 7)))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("job")).isZero();
  }

  @Test
  void aChangeThatIsNotSavedLeavesNoJob() {
    transactions.executeWithoutResult(
        change -> {
          jobs.queue(JobType.AFTER_APPROVAL, "of-a-change-undone", Map.of("catalogId", 7));
          change.setRollbackOnly();
        });

    assertThat(count("job")).isZero();
    assertThat(count("outbox")).isZero();
  }

  private long count(String from) {
    return jdbc.sql("SELECT count(*) FROM " + from).query(Long.class).single();
  }
}
