package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Issue.Severity;
import dev.rgonz.catalog.job.JobHandler;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.job.Jobs;
import dev.rgonz.catalog.library.LibraryChanged;
import dev.rgonz.catalog.library.LibraryRevision;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Keeps what validation last found in each lineage's current Approved, so that the dashboard can
 * say which of them need revision without validating any. An Approved version never changes, but
 * the library does: when it changes in a way that can break a catalog, every current Approved is
 * validated again, by the worker.
 */
@Component
class ApprovedChecks implements JobHandler {
  /** Whoever reads a catalog as no one in particular, which an Approved version allows. */
  private static final long NO_ONE = 0;

  private final JdbcClient jdbc;
  private final Jobs jobs;
  private final Catalogs catalogs;
  private final LibraryRevision library;

  ApprovedChecks(JdbcClient jdbc, Jobs jobs, Catalogs catalogs, LibraryRevision library) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.catalogs = catalogs;
    this.library = library;
  }

  /**
   * Writes a job for every lineage that has a current Approved, in the transaction of the change of
   * the library that calls for it.
   */
  @EventListener
  void whenTheLibraryChanges(LibraryChanged changed) {
    queueForEveryCurrentApproved(changed.revision());
  }

  /** Has every lineage's current Approved checked against the library as it is now. */
  void queueForEveryCurrentApproved() {
    queueForEveryCurrentApproved(library.current());
  }

  private void queueForEveryCurrentApproved(long revision) {
    jdbc.sql("SELECT current_catalog_id FROM lineage WHERE current_catalog_id IS NOT NULL")
        .query(Long.class)
        .list()
        .forEach(
            catalog ->
                jobs.queue(
                    JobType.RECHECK_APPROVED,
                    "recheck:%d:%d".formatted(catalog, revision),
                    Map.of("catalogId", catalog, "libraryRevision", revision)));
  }

  @Override
  public JobType type() {
    return JobType.RECHECK_APPROVED;
  }

  /**
   * Validates the catalog and keeps the result. Nothing is done when the library has changed again
   * since the job was written, since a later job covers that, or when the catalog is no longer its
   * lineage's current Approved.
   */
  @Override
  public void handle(JsonNode subject) {
    long catalog = subject.required("catalogId").asLong();
    long revision = subject.required("libraryRevision").asLong();
    boolean current =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM lineage WHERE current_catalog_id = :catalog)")
            .param("catalog", catalog)
            .query(Boolean.class)
            .single();
    if (!current || revision < library.current()) {
      return;
    }
    catalogs
        .find(catalog, NO_ONE)
        .ifPresent(
            approved ->
                keep(
                    catalog,
                    revision,
                    approved.issues().stream()
                        .filter(issue -> issue.severity() == Severity.ERROR)
                        .count()));
  }

  /** Keeps that an approval has just found the catalog without an Error. */
  void passedAtItsApproval(long catalogId) {
    keep(catalogId, library.current(), 0);
  }

  /** Keeps a result, unless one from a later revision of the library is kept already. */
  private void keep(long catalogId, long revision, long errors) {
    jdbc.sql(
            """
            INSERT INTO approved_check (catalog_id, library_revision, status, error_count)
            VALUES (:catalog, :revision, :status, :errors)
            ON CONFLICT (catalog_id) DO UPDATE
            SET library_revision = EXCLUDED.library_revision, status = EXCLUDED.status,
                error_count = EXCLUDED.error_count, checked_at = now()
            WHERE approved_check.library_revision <= EXCLUDED.library_revision
            """)
        .param("catalog", catalogId)
        .param("revision", revision)
        .param("status", errors == 0 ? "OK" : "NEEDS_REVISION")
        .param("errors", errors)
        .update();
  }
}
