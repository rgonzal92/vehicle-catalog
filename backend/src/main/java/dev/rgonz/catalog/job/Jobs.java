package dev.rgonz.catalog.job;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the jobs that changes cause. A job and the message that tells the worker of it are saved
 * with the change itself or not at all, so no change is left without its job and no job runs for a
 * change that was not made.
 */
@Component
public class Jobs {
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
