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
 * message up.
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
      } catch (RuntimeException failure) {
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
  private void keepThatItFailed(Message message, RuntimeException failure) {
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
   * Looks through the dead-letter queue and deletes each message whose job is no longer failed: one
   * that was retried and has been done since, and one that no longer exists. So the dead-letter
   * queue holds the jobs that are failed now, and whoever watches its depth is told of nothing
   * else.
   *
   * @return how many messages it deleted
   */
  int sweepTheDeadLetterQueue() {
    int deleted = 0;
    for (var message : queue.receiveFailed()) {
      boolean failed =
          jdbc.sql("SELECT EXISTS (SELECT 1 FROM job WHERE id = :id AND status = 'FAILED')")
              .param("id", jobOf(message.body()))
              .query(Boolean.class)
              .single();
      if (!failed) {
        queue.deleteFailed(message);
        deleted++;
      }
    }
    return deleted;
  }

  /** The job a message names. */
  private long jobOf(String body) {
    return json.readTree(body).required("jobId").asLong();
  }

  /**
   * Runs the job a message names and writes a line for it: which job, how it ended, and how long
   * that took. The job is traced while it runs, as part of the trace its message carries, which is
   * the trace of the request that wrote the job. So one trace shows the request and its job, and
   * the job's line carries that trace's id. A message that carries no trace starts one.
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
      log.info("{} in {} ms", ended.formatted(id), (System.nanoTime() - started) / 1_000_000);
    } catch (RuntimeException failure) {
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
