package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.job.JobHandler;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.job.Jobs;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Exports catalogs as spreadsheets. Whoever may open a catalog asks for one, the worker builds the
 * file and keeps it under the export's id, and whoever asked downloads it through a link that works
 * for a few minutes. An export stands as its job stands: waiting, ready once the file is kept, or
 * failed.
 */
@Service
class Exports implements JobHandler {
  /** The status of an export whose job is done or failed. Any other export is waiting. */
  private static final Map<String, String> STATUS_OF_JOB =
      Map.of("SUCCEEDED", "READY", "FAILED", "FAILED");

  private final JdbcClient jdbc;
  private final Catalogs catalogs;
  private final Jobs jobs;
  private final ExportFiles files;

  Exports(JdbcClient jdbc, Catalogs catalogs, Jobs jobs, ExportFiles files) {
    this.jdbc = jdbc;
    this.catalogs = catalogs;
    this.jobs = jobs;
    this.files = files;
  }

  /**
   * Has a catalog exported for a person who may open it, and answers with the export's id. The
   * export and the job that builds its file are written together.
   *
   * @param reviews whether the person reviews catalogs, as a manager and an admin do
   */
  @Transactional
  long request(long catalogId, long requesterId, boolean reviews) {
    var catalog =
        catalogs.find(catalogId, requesterId, reviews).orElseThrow(ApiException::notFound);
    var export =
        jdbc.sql(
                """
                INSERT INTO catalog_export (catalog_id, requested_by, file_name)
                VALUES (:catalog, :requester, :name)
                RETURNING id
                """)
            .param("catalog", catalogId)
            .param("requester", requesterId)
            .param("name", fileName(catalog))
            .query(Long.class)
            .single();
    jobs.queue(JobType.EXPORT, jobKey(export), Map.of("exportId", export));

    return export;
  }

  /** An export as the person who asked for it sees it. To anyone else there is no such export. */
  @Transactional(readOnly = true)
  Export find(long exportId, long requesterId) {
    return jdbc.sql(
            """
            SELECT e.id, e.catalog_id, e.file_name, j.status AS job_status
            FROM catalog_export e
            LEFT JOIN job j ON j.dedupe_key = 'export:' || e.id
            WHERE e.id = :id AND e.requested_by = :requester
            """)
        .param("id", exportId)
        .param("requester", requesterId)
        .query(
            (row, _) ->
                new Export(
                    row.getLong("id"),
                    row.getLong("catalog_id"),
                    STATUS_OF_JOB.getOrDefault(row.getString("job_status"), "QUEUED"),
                    row.getString("file_name")))
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  /**
   * A link to an export's file, for the person who asked for it, while they may still open the
   * catalog. There is none before the file is kept.
   */
  @Transactional(readOnly = true)
  URI linkTo(long exportId, long requesterId, boolean reviews) {
    var export = find(exportId, requesterId);
    if (!catalogs.opensFor(export.catalogId(), requesterId, reviews)) {
      throw ApiException.notFound();
    }
    if (!export.status().equals("READY")) {
      throw ApiException.conflict("NOT_READY", "The spreadsheet has not been built yet.");
    }
    return files.linkTo(fileKey(exportId));
  }

  @Override
  public JobType type() {
    return JobType.EXPORT;
  }

  /**
   * Builds an export's file and keeps it. A second run writes the same file over the first. An
   * export that is gone, with its catalog or at a reset, leaves nothing to build.
   */
  @Override
  public void handle(JsonNode subject) {
    long export = subject.required("exportId").asLong();
    jdbc.sql(
            """
            SELECT e.catalog_id, c.owner_id, e.file_name
            FROM catalog_export e
            JOIN catalog c ON c.id = e.catalog_id
            WHERE e.id = :id
            """)
        .param("id", export)
        .query(Asked.class)
        .optional()
        // Its owner may open a catalog whatever its status, and the file is the same for anyone.
        .ifPresent(
            asked ->
                catalogs
                    .find(asked.catalogId(), asked.ownerId())
                    .ifPresent(
                        catalog ->
                            files.put(
                                fileKey(export),
                                Spreadsheet.of(catalog.snapshot(), categories()),
                                asked.fileName())));
  }

  /** What each category is called, by its code and in the order categories are shown in. */
  private Map<String, String> categories() {
    var categories = new LinkedHashMap<String, String>();
    jdbc.sql("SELECT code, name FROM category ORDER BY sort_order")
        .query((row, _) -> categories.put(row.getString("code"), row.getString("name")))
        .list();
    return categories;
  }

  /**
   * What the file is called: the vehicle line, the model year, and the version number or the
   * working copy's name, with nothing in it that a file's name cannot have everywhere.
   */
  private static String fileName(CatalogView catalog) {
    var which = catalog.versionNumber() == null ? catalog.name() : "v" + catalog.versionNumber();
    return "%s %d %s"
            .formatted(catalog.vehicleLine(), catalog.modelYear(), which)
            .replaceAll("[^A-Za-z0-9 ._-]", "_")
        + ".xlsx";
  }

  /** What the job of an export shares with no other job. */
  private static String jobKey(long exportId) {
    return "export:" + exportId;
  }

  /** Where an export's file is kept. */
  static String fileKey(long exportId) {
    return exportId + ".xlsx";
  }

  /**
   * An export as the API shows it.
   *
   * @param status {@code QUEUED} until the file is kept, then {@code READY}, or {@code FAILED}
   */
  record Export(long id, long catalogId, String status, String fileName) {}

  private record Asked(long catalogId, long ownerId, String fileName) {}
}
