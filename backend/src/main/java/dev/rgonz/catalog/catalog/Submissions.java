package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.Issue.Severity;
import dev.rgonz.catalog.core.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Submits working copies for review and withdraws them. A Submitted catalog takes no edits. Until a
 * reviewer has decided on it, its owner can withdraw it, which makes it a Draft again.
 */
@Service
class Submissions {
  /** The most characters a submit note has. */
  static final int LONGEST_NOTE = 1000;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final CatalogEdits edits;
  private final Catalogs catalogs;

  Submissions(
      JdbcClient jdbc, TransactionTemplate transactions, CatalogEdits edits, Catalogs catalogs) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.edits = edits;
    this.catalogs = catalogs;
  }

  /**
   * Submits the owner's Draft for review and answers with the revision it is at afterwards. It
   * follows the rules of an edit first: only the owner, only a Draft, and only as they last saw it.
   * A stale catalog is not submitted, nor one of an inactive vehicle line: both are states it is in
   * and no issues of its own. Neither is one that validation, run at this moment, finds an Error
   * in.
   *
   * @param ifMatch the revision the submit was made from, as its {@code If-Match} header gave it
   * @param note what the owner says to the reviewer, if anything
   */
  long submit(long catalogId, long ownerId, String ifMatch, String note) {
    return transactions.execute(
        transaction -> {
          edits.lockToEdit(catalogId, ownerId, ifMatch);
          var said = note == null ? "" : note.strip();
          if (said.length() > LONGEST_NOTE) {
            throw ApiException.limitExceeded(
                "A note has at most %,d characters.".formatted(LONGEST_NOTE));
          }
          var catalog = catalogs.find(catalogId, ownerId).orElseThrow(ApiException::notFound);
          if (catalog.stale()) {
            throw ApiException.conflict(
                "STALE",
                "Approved v%d is now the current version of this catalog's lineage. Update the"
                        .formatted(catalog.current().versionNumber())
                    + " catalog from it, then submit it.");
          }
          if (!catalog.vehicleLineActive()) {
            throw ApiException.conflict(
                "VEHICLE_LINE_INACTIVE",
                "The vehicle line %s is deactivated, so its catalogs cannot be submitted."
                    .formatted(catalog.vehicleLine()));
          }
          if (catalog.issues().stream().anyMatch(issue -> issue.severity() == Severity.ERROR)) {
            throw ApiException.hasErrors(
                "This catalog has Errors. Put them right, then submit it.", catalog.issues());
          }

          jdbc.sql(
                  """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  VALUES (:catalog, :actor, 'SUBMITTED',
                          jsonb_strip_nulls(jsonb_build_object('new', :note::text)))
                  """)
              .param("catalog", catalogId)
              .param("actor", ownerId)
              .param("note", said.isEmpty() ? null : said)
              .update();
          return jdbc.sql(
                  """
                  UPDATE catalog
                  SET status = 'SUBMITTED', submit_note = :note, submitted_at = now(),
                      revision = revision + 1, updated_at = now()
                  WHERE id = :id
                  RETURNING revision
                  """)
              .param("note", said.isEmpty() ? null : said)
              .param("id", catalogId)
              .query(Long.class)
              .single();
        });
  }

  /**
   * Makes the owner's Submitted catalog a Draft again and answers with the revision it is at
   * afterwards. It names no revision: only a Submitted catalog can be withdrawn, and nothing
   * changes one but a withdrawal or a reviewer's decision.
   */
  long withdraw(long catalogId, long ownerId) {
    return transactions.execute(
        transaction -> {
          var status =
              jdbc.sql("SELECT status FROM catalog WHERE id = :id AND owner_id = :owner FOR UPDATE")
                  .param("id", catalogId)
                  .param("owner", ownerId)
                  .query(Status.class)
                  .optional()
                  .orElseThrow(ApiException::notFound);
          if (status != Status.SUBMITTED) {
            throw ApiException.conflict(
                "NOT_SUBMITTED", "Only a catalog in status Submitted can be withdrawn.");
          }

          jdbc.sql(
                  """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  VALUES (:catalog, :actor, 'WITHDRAWN', '{}')
                  """)
              .param("catalog", catalogId)
              .param("actor", ownerId)
              .update();
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
}
