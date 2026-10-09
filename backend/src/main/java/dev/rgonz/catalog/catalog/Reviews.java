package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.Issue.Severity;
import dev.rgonz.catalog.core.ApiException;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides on Submitted catalogs. A reviewer, who is never the catalog's owner, approves it or
 * rejects it. Approving makes the catalog the next Approved version of its lineage in place:
 * nothing is copied. Every decision is kept as a review record.
 */
@Service
class Reviews {
  /** The most characters a reviewer's comment has. */
  static final int LONGEST_COMMENT = 1000;

  /** A revision as {@code If-Match} carries it: the number in quotes, as the entity tag gave it. */
  private static final Pattern REVISION = Pattern.compile("\"(\\d{1,18})\"");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Catalogs catalogs;

  Reviews(JdbcClient jdbc, TransactionTemplate transactions, Catalogs catalogs) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.catalogs = catalogs;
  }

  /**
   * Approves a Submitted catalog and answers with the revision it is at afterwards. In one
   * transaction, with the catalog's lineage locked, so that two approvals in one lineage happen one
   * after the other: the catalog records the labels the library has for its trims, features, and
   * regions; it becomes Approved, with the lineage's next version number; it becomes the lineage's
   * current Approved; and every other Submitted catalog of the lineage goes back to its owner.
   *
   * <p>A catalog is not approved when it is stale, when its vehicle line is inactive, or when
   * validation, run again at this moment, finds an Error: the global rules may have changed since
   * it was submitted.
   *
   * @param ifMatch the revision the reviewer saw, as the {@code If-Match} header gave it
   * @param comment what the reviewer says about it, if anything
   */
  long approve(long catalogId, long reviewerId, String ifMatch, String comment) {
    return transactions.execute(
        transaction -> {
          var lineage =
              jdbc.sql(
                      """
                      SELECT l.id, l.current_catalog_id
                      FROM lineage l
                      JOIN catalog c ON c.lineage_id = l.id
                      WHERE c.id = :id
                      FOR UPDATE OF l
                      """)
                  .param("id", catalogId)
                  .query(Lineage.class)
                  .optional()
                  .orElseThrow(ApiException::notFound);
          var submitted = lockToDecide(catalogId, reviewerId, ifMatch);
          var said = commentOf(comment);
          if (lineage.currentCatalogId() != null
              && !Objects.equals(lineage.currentCatalogId(), submitted.baseCatalogId())) {
            throw ApiException.conflict(
                "STALE",
                "Another version of this catalog's lineage was approved after it was made. Its"
                    + " owner has to update it from that version first.");
          }
          var catalog =
              catalogs.find(catalogId, reviewerId, true).orElseThrow(ApiException::notFound);
          if (!catalog.vehicleLineActive()) {
            throw ApiException.conflict(
                "VEHICLE_LINE_INACTIVE",
                "The vehicle line %s is deactivated, so its catalogs cannot be approved."
                    .formatted(catalog.vehicleLine()));
          }
          if (catalog.issues().stream().anyMatch(issue -> issue.severity() == Severity.ERROR)) {
            throw ApiException.hasErrors(
                "This catalog has Errors, so it cannot be approved.", catalog.issues());
          }

          freezeLabels(catalogId);
          var revision =
              jdbc.sql(
                      """
                      UPDATE catalog
                      SET status = 'APPROVED',
                          version_number =
                              (SELECT COALESCE(max(version_number), 0) + 1
                               FROM catalog WHERE lineage_id = :lineage),
                          approved_by = :reviewer, approved_at = now(),
                          revision = revision + 1, updated_at = now()
                      WHERE id = :id
                      RETURNING revision
                      """)
                  .param("lineage", lineage.id())
                  .param("reviewer", reviewerId)
                  .param("id", catalogId)
                  .query(Long.class)
                  .single();
          jdbc.sql("UPDATE lineage SET current_catalog_id = :catalog WHERE id = :lineage")
              .param("catalog", catalogId)
              .param("lineage", lineage.id())
              .update();
          returnTheOthers(lineage.id(), catalogId);
          record(catalogId, reviewerId, "APPROVED", said);

          return revision;
        });
  }

  /**
   * Rejects a Submitted catalog, which goes back to its owner as a Draft, and answers with the
   * revision it is at afterwards. A rejection says why.
   */
  long reject(long catalogId, long reviewerId, String ifMatch, String comment) {
    return transactions.execute(
        transaction -> {
          lockToDecide(catalogId, reviewerId, ifMatch);
          var said = commentOf(comment);
          if (said == null) {
            throw ApiException.invalid("Say why the catalog is rejected.");
          }
          record(catalogId, reviewerId, "REJECTED", said);

          return jdbc.sql(
                  """
                  UPDATE catalog
                  SET status = 'DRAFT', revision = revision + 1, updated_at = now()
                  WHERE id = :id
                  RETURNING revision
                  """)
              .param("id", catalogId)
              .query(Long.class)
              .single();
        });
  }

  /**
   * Locks the catalog's row until the transaction ends and makes sure the reviewer may decide on
   * the catalog as it is: that they may know of it, that it is not their own, that it is Submitted,
   * and that the decision names the revision it is at, in that order. A Draft is its owner's alone,
   * so to a reviewer it is as if it did not exist.
   */
  private Submitted lockToDecide(long catalogId, long reviewerId, String ifMatch) {
    var catalog =
        jdbc.sql(
                """
                SELECT owner_id, status, revision, base_catalog_id
                FROM catalog WHERE id = :id FOR UPDATE
                """)
            .param("id", catalogId)
            .query(Submitted.class)
            .optional()
            .orElseThrow(ApiException::notFound);
    if (catalog.ownerId() == reviewerId) {
      throw ApiException.forbidden("SELF_APPROVAL", "Nobody decides on a catalog of their own.");
    }
    if (catalog.status() == Status.DRAFT) {
      throw ApiException.notFound();
    }
    if (catalog.status() != Status.SUBMITTED) {
      throw ApiException.conflict(
          "NOT_SUBMITTED", "Only a catalog in status Submitted can be approved or rejected.");
    }
    if (ifMatch == null) {
      throw ApiException.revisionRequired();
    }
    var named = REVISION.matcher(ifMatch.strip());
    if (!named.matches()) {
      throw ApiException.badRequest(
          "If-Match takes the catalog's revision in quotes, as in \"42\".");
    }
    if (catalog.revision() != Long.parseLong(named.group(1))) {
      throw ApiException.revisionConflict(catalog.revision());
    }
    return catalog;
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
   * moment, and the order of its trims. An Approved version shows these from then on, whatever the
   * library changes.
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
            SET approved_name = r.name
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

  /**
   * Sends every other Submitted catalog of the lineage back to its owner as a Draft, each with a
   * record that says so: the approval has made them stale.
   */
  private void returnTheOthers(long lineageId, long approvedId) {
    jdbc.sql(
            """
            WITH returned AS (
                UPDATE catalog
                SET status = 'DRAFT', revision = revision + 1, updated_at = now()
                WHERE lineage_id = :lineage AND status = 'SUBMITTED' AND id <> :approved
                RETURNING id
            )
            INSERT INTO catalog_review (catalog_id, decision)
            SELECT id, 'RETURNED_STALE' FROM returned
            """)
        .param("lineage", lineageId)
        .param("approved", approvedId)
        .update();
  }

  private void record(long catalogId, long reviewerId, String decision, String comment) {
    jdbc.sql(
            """
            INSERT INTO catalog_review (catalog_id, reviewer_id, decision, comment)
            VALUES (:catalog, :reviewer, :decision, :comment)
            """)
        .param("catalog", catalogId)
        .param("reviewer", reviewerId)
        .param("decision", decision)
        .param("comment", comment)
        .update();
  }

  /** A lineage as an approval locks it: itself, and its current Approved if it has one. */
  private record Lineage(long id, Long currentCatalogId) {}

  /** What a decision checks of a catalog once it has locked it. */
  private record Submitted(long ownerId, Status status, long revision, Long baseCatalogId) {}
}
