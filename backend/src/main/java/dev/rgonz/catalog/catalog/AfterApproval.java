package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.job.JobHandler;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.notification.Notifications;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * What follows an approval, which the worker does: the catalog's owner is told that it was
 * approved, as which version, and by whom.
 */
@Component
class AfterApproval implements JobHandler {
  /** The name a job of this type gives the catalog it is about. */
  static final String CATALOG = "catalogId";

  private final JdbcClient jdbc;
  private final Notifications notifications;

  AfterApproval(JdbcClient jdbc, Notifications notifications) {
    this.jdbc = jdbc;
    this.notifications = notifications;
  }

  @Override
  public JobType type() {
    return JobType.AFTER_APPROVAL;
  }

  /** Tells the owner, once. A catalog that is no longer there has no owner to tell. */
  @Override
  public void handle(JsonNode subject) {
    long catalog = subject.required(CATALOG).asLong();
    jdbc.sql(
            """
            SELECT c.owner_id, c.name, l.id AS lineage_id, v.name AS vehicle_line, l.model_year,
                   c.version_number, a.display_name AS reviewer
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            JOIN app_user a ON a.id = c.approved_by
            WHERE c.id = :id AND c.status = 'APPROVED'
            """)
        .param("id", catalog)
        .query(Approved.class)
        .optional()
        .ifPresent(
            approved ->
                notifications.tell(
                    approved.ownerId(),
                    "CATALOG_APPROVED",
                    "approved:" + catalog,
                    Map.of(
                        "catalogId", catalog,
                        "catalog", approved.name(),
                        "lineageId", approved.lineageId(),
                        "vehicleLine", approved.vehicleLine(),
                        "modelYear", approved.modelYear(),
                        "versionNumber", approved.versionNumber(),
                        "reviewer", approved.reviewer())));
  }

  private record Approved(
      long ownerId,
      String name,
      long lineageId,
      String vehicleLine,
      int modelYear,
      int versionNumber,
      String reviewer) {}
}
