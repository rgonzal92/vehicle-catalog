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
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the jobs whose messages arrive. A message is deleted only once its job's work is saved, so a
 * stop in the middle leaves the message to be delivered again. A message can arrive twice for that
 * reason and others; a job that is done already is not done again.
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
  private final Map<JobType, JobHandler> handlers;

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
        handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
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
        log.warn("A job was not done, and its message will be delivered again", failure);
      }
    }
    return messages.size();
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
    long id = json.readTree(body).required("jobId").asLong();
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
          handlers
              .get(JobType.valueOf(job.get().type()))
              .handle(json.readTree(job.get().subject()));
          jdbc.sql(
                  """
                  UPDATE job
                  SET status = 'SUCCEEDED', attempts = attempts + 1, updated_at = now()
                  WHERE id = :id
                  """)
              .param("id", id)
              .update();
          return job.get().type() + " job %d done";
        });
  }

  private record Waiting(String type, String status, String subject) {}
}
