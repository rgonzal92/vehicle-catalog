package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Issue.Counts;
import dev.rgonz.catalog.catalog.Revisions.Change;
import dev.rgonz.catalog.catalog.Revisions.Outcome;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Submits working copies for review and withdraws them. A Submitted catalog takes no edits. Until a
 * reviewer has decided on it, its owner can withdraw it, which makes it a Draft again.
 */
@Service
class Submissions {
  /** The most characters a submit note has. */
  static final int LONGEST_NOTE = 1000;

  private final JdbcClient jdbc;
  private final Revisions revisions;
  private final Catalogs catalogs;
  private final SubmissionSummaries summaries;

  /** How many submits were refused for the state the catalog was in, by the refusal's code. */
  private final Map<String, Counter> refusals;

  Submissions(
      JdbcClient jdbc,
      Revisions revisions,
      Catalogs catalogs,
      SubmissionSummaries summaries,
      MeterRegistry metrics) {
    this.summaries = summaries;
    this.jdbc = jdbc;
    this.revisions = revisions;
    this.catalogs = catalogs;
    // Each reason is a metric of its own from the start, so the list of them never grows unseen.
    this.refusals =
        Stream.of("STALE", "VEHICLE_LINE_INACTIVE", "HAS_ERRORS")
            .collect(
                Collectors.toMap(
                    reason -> reason,
                    reason ->
                        Counter.builder("catalog.submit.refused")
                            .description(
                                "How many submits were refused for the state the catalog was in")
                            .tag("reason", reason)
                            .register(metrics)));
  }

  /**
   * Counts a submit that is refused for the state the catalog is in, and gives the refusal back.
   */
  private ApiException refused(String reason, ApiException refusal) {
    refusals.get(reason).increment();
    return refusal;
  }

  /**
   * Submits the owner's Draft for review and answers with where that led. It follows the rules of
   * an edit first: only the owner, only a Draft, and only as they last saw it. A stale catalog is
   * not submitted, nor one of an inactive vehicle line: both are states it is in and no issues of
   * its own. Neither is one that validation, run at this moment, finds an Error in.
   *
   * @param ifMatch the revision the submit was made from, as its {@code If-Match} header gave it
   * @param note what the owner says to the reviewer, if anything
   */
  Outcome submit(long catalogId, long ownerId, String ifMatch, String note) {
    return revisions.move(
        catalogId,
        ownerId,
        ifMatch,
        () -> {
          var said = note == null ? "" : note.strip();
          if (said.length() > LONGEST_NOTE) {
            throw ApiException.limitExceeded(
                "A note has at most %,d characters.".formatted(LONGEST_NOTE));
          }
          var catalog = catalogs.find(catalogId, ownerId).orElseThrow(ApiException::notFound);
          if (catalog.stale()) {
            throw refused(
                "STALE",
                ApiException.conflict(
                    "STALE",
                    "Approved v%d is now the current version of this catalog's lineage. Update the"
                            .formatted(catalog.current().versionNumber())
                        + " catalog from it, then submit it."));
          }
          if (!catalog.vehicleLineActive()) {
            throw refused(
                "VEHICLE_LINE_INACTIVE",
                ApiException.conflict(
                    "VEHICLE_LINE_INACTIVE",
                    "The vehicle line %s is deactivated, so its catalogs cannot be submitted."
                        .formatted(catalog.vehicleLine())));
          }
          if (Counts.of(catalog.issues()).errors() > 0) {
            throw refused(
                "HAS_ERRORS",
                ApiException.hasErrors(
                    "This catalog has Errors. Put them right, then submit it.", catalog.issues()));
          }

          jdbc.sql(
                  """
                  UPDATE catalog
                  SET status = 'SUBMITTED', submit_note = :note, submitted_at = now(),
                      updated_at = now()
                  WHERE id = :id
                  """)
              .param("note", said.isEmpty() ? null : said)
              .param("id", catalogId)
              .update();
          return List.of(Change.submitted(said.isEmpty() ? null : said));
        },
        revision -> summaries.expect(catalogId, revision));
  }

  /**
   * Makes the owner's Submitted catalog a Draft again and answers with where that led. It names no
   * revision: only a Submitted catalog can be withdrawn, and nothing changes one but a withdrawal
   * or a reviewer's decision.
   */
  Outcome withdraw(long catalogId, long ownerId) {
    return revisions.withdraw(
        catalogId,
        ownerId,
        () -> {
          jdbc.sql("UPDATE catalog SET status = 'DRAFT', updated_at = now() WHERE id = :id")
              .param("id", catalogId)
              .update();
          return List.of(Change.withdrawn());
        });
  }
}
