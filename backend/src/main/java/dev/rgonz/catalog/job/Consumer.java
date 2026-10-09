package dev.rgonz.catalog.job;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the jobs whose messages arrive. A message is deleted only once its job's work is saved, so a
 * stop in the middle leaves the message to be delivered again. A message can arrive twice for that
 * reason and others; a job that is done already is not done again. A job whose work fails is tried
 * again when its message is delivered again, and has failed for good once the queue gives the
 * message up, whether a try at it said why or not.
 */
@Component
@TalksToTheQueue
class Consumer {
  private static final Logger log = LoggerFactory.getLogger(Consumer.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final JobQueue queue;
  private final JsonMapper json;
  private final ObservationRegistry observations;
  private final Map<String, JobHandler> handlers;

  Consumer(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      JobQueue queue,
      JsonMapper json,
      ObservationRegistry observations,
      List<JobHandler> handlers) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.queue = queue;
    this.json = json;
    this.observations = observations;
    this.handlers =
        handlers.stream()
            .collect(Collectors.toMap(handler -> handler.type().name(), Function.identity()));
  }

  /**
   * Handles the messages that wait on the queue, or that arrive while this waits for them, and
   * answers with how many there were. A message whose job fails stays on the queue, which delivers
   * it again.
   */
  int receive(Duration wait) {
    var messages = queue.receive(wait);
    for (var message : messages) {
      try {
        run(message.body(), JobQueue.attributesOf(message));
        queue.delete(message);
      } catch (RuntimeException | Error failure) {
        // An error that is no exception, such as running out of memory, is a failed try too.
        log.warn("A job was not done", failure);
        keepThatItFailed(message, failure);
      }
    }
    return messages.size();
  }

  /**
   * Keeps that a try at a job failed, and why. The job's own work went with its transaction, so
   * this is saved by itself. The queue delivers the message again unless it has delivered it as
   * often as it does: then the job has failed for good, and stays so until an admin has it retried.
   */
  private void keepThatItFailed(Message message, Throwable failure) {
    try {
      jdbc.sql(
              """
              UPDATE job
              SET attempts = attempts + 1, error = :error,
                  status = CASE WHEN :last THEN 'FAILED' ELSE status END, updated_at = now()
              WHERE id = :id AND status <> 'SUCCEEDED'
              """)
          .param("error", failure.toString().lines().findFirst().orElse("").strip())
          .param("last", JobQueue.deliveriesOf(message) >= queue.mostDeliveries())
          .param("id", jobOf(message.body()))
          .update();
    } catch (RuntimeException unkept) {
      log.warn("That a job failed could not be kept", unkept);
    }
  }

  /**
   * Looks through the dead-letter queue, so that it holds the jobs that are failed now and whoever
   * watches its depth is told of nothing else.
   *
   * <p>A job that still waits though the queue has given its message up is made failed: no try at
   * it was done, and none was kept as failed, as when the worker was stopped in the middle of each.
   * A message is deleted when its job is no longer failed: one that was retried and has been done
   * since, one that was retried and waits for its new message, and one that no longer exists.
   *
   * @return how many messages it deleted
   */
  int sweepTheDeadLetterQueue() {
    int deleted = 0;
    for (var message : queue.receiveFailed()) {
      long job = jobOf(message.body());
      var status =
          jdbc.sql("SELECT status FROM job WHERE id = :id")
              .param("id", job)
              .query(String.class)
              .optional()
              .orElse("GONE");
      if (status.equals("QUEUED") && isTheLatestOf(job, message)) {
        giveUp(job);
      } else if (!status.equals("FAILED")) {
        queue.deleteFailed(message);
        deleted++;
      }
    }
    return deleted;
  }

  /**
   * Whether this is the message the job was last queued with. A job that was retried has a new
   * message, while the one of the tries before its retry may still be in the dead-letter queue.
   */
  private boolean isTheLatestOf(long job, Message message) {
    var sent = JobQueue.attributesOf(message).get(JobQueue.MESSAGE);

    return sent != null
        && jdbc.sql("SELECT coalesce(max(id) = :message, false) FROM outbox WHERE job_id = :job")
            .param("message", Long.parseLong(sent))
            .param("job", job)
            .query(Boolean.class)
            .single();
  }

  /**
   * Makes a job failed that the queue has given up. A job that is being worked on just now is left
   * as it is, for the next look through the dead-letter queue: its try may yet be done.
   */
  private void giveUp(long job) {
    jdbc.sql(
            """
            UPDATE job
            SET status = 'FAILED', updated_at = now(),
                error = coalesce(
                    error, 'The queue gave up its message, and no try at the job said why.')
            WHERE id = (
                SELECT id FROM job WHERE id = :id AND status = 'QUEUED' FOR UPDATE SKIP LOCKED
            )
            """)
        .param("id", job)
        .update();
  }

  /** The job a message names. */
  private long jobOf(String body) {
    return json.readTree(body).required("jobId").asLong();
  }

  /**
   * Runs the job a message names and writes a line for it: which job, how it ended, and how long
   * that took. The job is traced while it runs, as part of the trace its message carries, which is
   * the trace of the request that wrote the job. So one trace shows the request and its job, and
   * the job's line carries that trace's id. A message that carries no trace starts one. How long
   * the job took is also what the worker reports of itself, by the job's type and how it ended.
   *
   * @param said what the message says beside its body: its job's type, and its trace if it has one
   */
  private void run(String body, Map<String, String> said) {
    long id = jobOf(body);
    var started = System.nanoTime();
    var received = new ReceiverContext<Map<String, String>>(Map::get, Kind.CONSUMER);
    received.setCarrier(said);
    var type = said.getOrDefault("type", "unknown");
    var observation =
        Observation.createNotStarted("job.run", () -> received, observations)
            .contextualName(type + " job")
            .lowCardinalityKeyValue("type", type)
            .start();
    try (var traced = observation.openScope()) {
      var ended = runOnce(id);
      observation.lowCardinalityKeyValue("outcome", "SUCCESS");
      log.info("{} in {} ms", ended.formatted(id), (System.nanoTime() - started) / 1_000_000);
    } catch (RuntimeException | Error failure) {
      observation.lowCardinalityKeyValue("outcome", "FAILURE");
      observation.error(failure);
      throw failure;
    } finally {
      observation.stop();
    }
  }

  /**
   * Does the job's work with the job's row held from the first look at it until the work is saved.
   * Nothing is done for a job that is done already, or that no longer exists.
   *
   * @return how it ended, as a line that has a place for the job's id
   */
  private String runOnce(long id) {
    return transactions.execute(
        transaction -> {
          var job =
              jdbc.sql(
                      """
                      SELECT type, status, subject::text AS subject
                      FROM job
                      WHERE id = :id
                      FOR UPDATE
                      """)
                  .param("id", id)
                  .query(Waiting.class)
                  .optional();
          if (job.isEmpty()) {
            return "Job %d is gone, and so is its message";
          }
          if (job.get().status().equals("SUCCEEDED")) {
            return job.get().type() + " job %d was done already";
          }
          var handler = handlers.get(job.get().type());
          if (handler == null) {
            throw new IllegalStateException("Nothing here does a job of type " + job.get().type());
          }
          handler.handle(json.readTree(job.get().subject()));
          jdbc.sql(
                  """
                  UPDATE job
                  SET status = 'SUCCEEDED', attempts = attempts + 1, error = NULL,
                      updated_at = now()
                  WHERE id = :id
                  """)
              .param("id", id)
              .update();
          return job.get().type() + " job %d done";
        });
  }

  private record Waiting(String type, String status, String subject) {}
}
