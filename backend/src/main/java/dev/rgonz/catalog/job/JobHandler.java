package dev.rgonz.catalog.job;

import tools.jackson.databind.JsonNode;

/**
 * Does the work of the jobs of one type. A handler runs in a transaction that holds its job's row,
 * and what it writes is saved with the job's becoming done, or not at all. The same message can
 * arrive twice, so a handler writes nothing that a second run would write again.
 */
public interface JobHandler {
  JobType type();

  /**
   * Does the job's work.
   *
   * @param subject what the job is about, as it was written with the job
   */
  void handle(JsonNode subject);
}
