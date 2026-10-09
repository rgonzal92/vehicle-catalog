package dev.rgonz.catalog.job;

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
  private final Map<JobType, JobHandler> handlers;

  Consumer(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      JobQueue queue,
      JsonMapper json,
      List<JobHandler> handlers) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.queue = queue;
    this.json = json;
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
        run(message.body());
        queue.delete(message);
      } catch (RuntimeException failure) {
        log.warn("A job was not done, and its message will be delivered again", failure);
      }
    }
    return messages.size();
  }

  /**
   * Runs the job a message names, with the job's row held from the first look at it until its work
   * is saved. Nothing is done for a job that is done already, or that no longer exists.
   */
  private void run(String body) {
    long id = json.readTree(body).required("jobId").asLong();
    transactions.executeWithoutResult(
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
          if (job.isEmpty() || job.get().status().equals("SUCCEEDED")) {
            return;
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
        });
  }

  private record Waiting(String type, String status, String subject) {}
}
