package dev.rgonz.catalog.job;

import dev.rgonz.catalog.core.ApiException;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the jobs that changes cause, lists them, and has a failed one tried again. A job and the
 * message that tells the worker of it are saved with the change itself or not at all, so no change
 * is left without its job and no job runs for a change that was not made.
 */
@Component
public class Jobs {
  /** The most jobs a page holds. */
  static final int LARGEST_PAGE = 100;

  /** The last page there can be, so that no page's offset overflows. */
  private static final int LAST_PAGE = Integer.MAX_VALUE / LARGEST_PAGE - 1;

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Tracer tracer;
  private final Propagator propagator;

  Jobs(JdbcClient jdbc, JsonMapper json, Tracer tracer, Propagator propagator) {
    this.jdbc = jdbc;
    this.json = json;
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * Writes a job and its unsent message in the caller's transaction, and refuses to be called
   * outside one. Nothing is written when a job with the dedupe key exists already. The message
   * keeps the trace it is written in, so that the job's handling becomes part of that trace.
   *
   * @param dedupeKey what two changes that call for the same work share
   * @param subject what the job is about, which its handler is given
   * @return whether a job was written
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean queue(JobType type, String dedupeKey, Map<String, ?> subject) {
    return jdbc.sql(
                """
                WITH queued AS (
                    INSERT INTO job (type, dedupe_key, subject)
                    VALUES (:type, :key, :subject::jsonb)
                    ON CONFLICT (dedupe_key) DO NOTHING
                    RETURNING id
                )
                INSERT INTO outbox (job_id, payload, traceparent)
                SELECT id, jsonb_build_object('jobId', id), :traceparent FROM queued
                """)
            .param("type", type.name())
            .param("key", dedupeKey)
            .param("subject", json.writeValueAsString(subject))
            .param("traceparent", traceparent())
            .update()
        == 1;
  }

  /**
   * Has a job that has failed tried once more: it waits again, and a new message is written for it,
   * in one transaction. Any other job is left as it is.
   */
  @Transactional
  Listed retry(long id) {
    var status =
        jdbc.sql("SELECT status FROM job WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(String.class)
            .optional()
            .orElseThrow(ApiException::notFound);
    if (!status.equals("FAILED")) {
      throw ApiException.conflict("NOT_FAILED", "Only a job that has failed can be retried.");
    }
    jdbc.sql("UPDATE job SET status = 'QUEUED', updated_at = now() WHERE id = :id")
        .param("id", id)
        .update();
    jdbc.sql(
            """
            INSERT INTO outbox (job_id, payload, traceparent)
            VALUES (:id, jsonb_build_object('jobId', :id), :traceparent)
            """)
        .param("id", id)
        .param("traceparent", traceparent())
        .update();

    return listed("WHERE id = :id", Map.of("id", id)).getFirst();
  }

  /**
   * One page of the jobs, newest first, and how many there are in all. A page or size out of range
   * is brought into it: a page holds between 1 and {@value #LARGEST_PAGE} jobs.
   *
   * @param status the status the jobs are to have, or null for every job
   */
  @Transactional(readOnly = true)
  JobPage page(String status, int page, int size) {
    var limit = Math.clamp(size, 1, LARGEST_PAGE);
    var where = status == null ? "" : "WHERE status = :status";
    Map<String, Object> wanted = status == null ? Map.of() : Map.of("status", status);
    var items =
        listed(
            where
                + " ORDER BY id DESC LIMIT %d OFFSET %d"
                    .formatted(limit, (long) Math.clamp(page, 0, LAST_PAGE) * limit),
            wanted);
    var total =
        jdbc.sql("SELECT count(*) FROM job " + where).params(wanted).query(Long.class).single();

    return new JobPage(items, total);
  }

  private List<Listed> listed(String which, Map<String, ?> parameters) {
    return jdbc.sql(
            """
            SELECT id, type, subject::text AS subject, status, attempts, error, created_at,
                   updated_at
            FROM job
            """
                + which)
        .params(parameters)
        .query(
            (row, _) ->
                new Listed(
                    row.getLong("id"),
                    row.getString("type"),
                    json.readTree(row.getString("subject")),
                    row.getString("status"),
                    row.getInt("attempts"),
                    row.getString("error"),
                    row.getTimestamp("created_at").toInstant(),
                    row.getTimestamp("updated_at").toInstant()))
        .list();
  }

  /** A page of jobs, newest first, and the number of jobs in all. */
  record JobPage(List<Listed> items, long total) {}

  /**
   * A job as the Jobs screen lists it.
   *
   * @param subject what it is about, such as the catalog
   * @param attempts how often it has been tried
   * @param error what the last try that failed said, or null when the last try did not fail
   */
  record Listed(
      long id,
      String type,
      JsonNode subject,
      String status,
      int attempts,
      String error,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * The trace this is called in, as the W3C writes one down, or nothing when it is called in none:
   * a job that no request caused starts a trace of its own.
   */
  private String traceparent() {
    var trace = tracer.currentTraceContext().context();
    if (trace == null) {
      return null;
    }
    var carrier = new HashMap<String, String>();
    propagator.inject(trace, carrier, Map::put);
    return carrier.get(JobQueue.TRACE);
  }
}
