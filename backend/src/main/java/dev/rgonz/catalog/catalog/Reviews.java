package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Issue.Counts;
import dev.rgonz.catalog.catalog.Revisions.Decision;
import dev.rgonz.catalog.catalog.Revisions.Outcome;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.job.Jobs;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Decides on Submitted catalogs. A reviewer, who is never the catalog's owner, approves it or
 * rejects it. Approving makes the catalog the next Approved version of its lineage in place:
 * nothing is copied. Every decision is kept as a review record.
 */
@Service
class Reviews {
  /** The most characters a reviewer's comment has. */
  static final int LONGEST_COMMENT = 1000;

  private final JdbcClient jdbc;
  private final Revisions revisions;
  private final Jobs jobs;
  private final ApprovedChecks checks;
  private final Counter approvals;
  private final Counter rejections;

  Reviews(
      JdbcClient jdbc,
      Revisions revisions,
      Jobs jobs,
      ApprovedChecks checks,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.revisions = revisions;
    this.jobs = jobs;
    this.checks = checks;
    this.approvals =
        Counter.builder("catalog.approved")
            .description("How many catalogs were approved")
            .register(metrics);
    this.rejections =
        Counter.builder("catalog.rejected")
            .description("How many catalogs were rejected")
            .register(metrics);
  }

  /**
   * Approves a Submitted catalog and answers with where that led. In one transaction, with the
   * catalog's lineage locked, so that two approvals in one lineage happen one after the other: the
   * catalog records the labels the library has for its trims, features, and regions; it becomes
   * Approved, with the lineage's next version number; it becomes the lineage's current Approved;
   * every other Submitted catalog of the lineage goes back to its owner; it is kept that the
   * catalog was found without an Error; and the job that follows an approval is written, for the
   * worker to do.
   *
   * <p>A catalog is not approved when it is stale, when its vehicle line is inactive, or when
   * validation, run again at this moment, finds an Error: the global rules may have changed since
   * it was submitted.
   *
   * @param ifMatch the revision the reviewer saw, as the {@code If-Match} header gave it
   * @param comment what the reviewer says about it, if anything
   */
  Outcome approve(long catalogId, long reviewerId, String ifMatch, String comment) {
    var approved =
        revisions.approve(
            catalogId,
            reviewerId,
            ifMatch,
            (submitted, catalog) -> {
              var said = commentOf(comment);
              var current =
                  jdbc.sql("SELECT current_catalog_id FROM lineage WHERE id = :lineage")
                      .param("lineage", submitted.lineageId())
                      .query(Long.class)
                      .optional();
              if (current.isPresent()
                  && !Objects.equals(current.get(), submitted.baseCatalogId())) {
                throw ApiException.conflict(
                    "STALE",
                    "Another version of this catalog's lineage was approved after it was made. Its"
                        + " owner has to update it from that version first.");
              }
              if (!catalog.read().vehicleLineActive()) {
                throw ApiException.conflict(
                    "VEHICLE_LINE_INACTIVE",
                    "The vehicle line %s is deactivated, so its catalogs cannot be approved."
                        .formatted(catalog.read().vehicleLine()));
              }
              if (Counts.of(catalog.issues()).errors() > 0) {
                throw ApiException.hasErrors(
                    "This catalog has Errors, so it cannot be approved.", catalog.issues());
              }

              freezeLabels(catalogId);
              jdbc.sql(
                      """
                      UPDATE catalog
                      SET status = 'APPROVED',
                          version_number =
                              (SELECT COALESCE(max(version_number), 0) + 1
                               FROM catalog WHERE lineage_id = :lineage),
                          approved_by = :reviewer, approved_at = now(), updated_at = now()
                      WHERE id = :id
                      """)
                  .param("lineage", submitted.lineageId())
                  .param("reviewer", reviewerId)
                  .param("id", catalogId)
                  .update();
              jdbc.sql("UPDATE lineage SET current_catalog_id = :catalog WHERE id = :lineage")
                  .param("catalog", catalogId)
                  .param("lineage", submitted.lineageId())
                  .update();
              revisions.returnTheOthers(submitted.lineageId(), catalogId);
              checks.passedAtItsApproval(catalogId);
              jobs.queue(
                  JobType.AFTER_APPROVAL,
                  "after-approval:" + catalogId,
                  Map.of(AfterApproval.CATALOG, catalogId));

              return Decision.approved(said);
            });
    approvals.increment();

    return approved;
  }

  /**
   * Rejects a Submitted catalog, which goes back to its owner as a Draft, and answers with where
   * that led. A rejection says why.
   */
  Outcome reject(long catalogId, long reviewerId, String ifMatch, String comment) {
    var rejected =
        revisions.decide(
            catalogId,
            reviewerId,
            ifMatch,
            submitted -> {
              var said = commentOf(comment);
              if (said == null) {
                throw ApiException.invalid("Say why the catalog is rejected.");
              }
              jdbc.sql("UPDATE catalog SET status = 'DRAFT', updated_at = now() WHERE id = :id")
                  .param("id", catalogId)
                  .update();

              return Decision.rejected(said);
            });
    rejections.increment();

    return rejected;
  }

  /** The comment without the spaces around it, or null when there is none. */
  private static String commentOf(String comment) {
    var said = comment == null ? "" : comment.strip();
    if (said.length() > LONGEST_COMMENT) {
      throw ApiException.limitExceeded(
          "A comment has at most %,d characters.".formatted(LONGEST_COMMENT));
    }
    return said.isEmpty() ? null : said;
  }

  /**
   * Records, with the catalog, what the library calls its trims, features, and regions at this
   * moment, and the order of its trims and of its regions. An Approved version shows these from
   * then on, whatever the library changes.
   */
  private void freezeLabels(long catalogId) {
    jdbc.sql(
            """
            UPDATE catalog_trim c
            SET approved_name = t.name, approved_sort_order = t.sort_order
            FROM trim t
            WHERE c.catalog_id = :catalog AND t.id = c.trim_id
            """)
        .param("catalog", catalogId)
        .update();
    jdbc.sql(
            """
            UPDATE catalog_region c
            SET approved_name = r.name, approved_sort_order = r.sort_order
            FROM region r
            WHERE c.catalog_id = :catalog AND r.code = c.region_code
            """)
        .param("catalog", catalogId)
        .update();
    jdbc.sql(
            """
            UPDATE catalog_feature c
            SET approved_name = f.name, approved_category_code = f.category_code
            FROM feature f
            WHERE c.catalog_id = :catalog AND f.id = c.feature_id
            """)
        .param("catalog", catalogId)
        .update();
  }
}
