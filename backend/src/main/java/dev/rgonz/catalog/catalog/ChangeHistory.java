package dev.rgonz.catalog.catalog;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a catalog's history: every change an edit made and every decision a review made, in one
 * timeline, newest first. A change holds the identities of what it touched; the history names them
 * the way the catalog itself does, with the labels frozen at approval where there are any and the
 * library's current ones otherwise.
 */
@Repository
class ChangeHistory {
  /** A page holds at most this many changes. */
  private static final int LARGEST_PAGE = 100;

  /** The last page there can be, so that no page's offset overflows. */
  private static final int LAST_PAGE = Integer.MAX_VALUE / LARGEST_PAGE - 1;

  private final JdbcClient jdbc;

  ChangeHistory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * One page of the catalog's changes and decisions and how many there are in all. Pages are
   * numbered from 0. A page or size out of range is brought into it: a page holds between 1 and
   * {@value #LARGEST_PAGE} changes. Both are read from one view of the database, so they agree.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  ChangePage page(long catalogId, int page, int size) {
    var limit = Math.clamp(size, 1, LARGEST_PAGE);
    var items =
        jdbc.sql(
                """
                SELECT *
                FROM (
                    SELECT h.id, h.at, a.display_name AS actor, h.kind,
                           f.code AS feature_code,
                           COALESCE(cf.approved_name, f.name) AS feature_name,
                           COALESCE(ct.approved_name, t.name) AS trim,
                           COALESCE(cr.approved_name, r.name) AS region,
                           h.payload ->> 'old' AS old_value, h.payload ->> 'new' AS new_value
                    FROM catalog_change h
                    JOIN app_user a ON a.id = h.actor_id
                    -- Compared as JSON, so that a payload holding anything but a number matches
                    -- nothing where a cast would fail the whole page.
                    LEFT JOIN feature f ON to_jsonb(f.id) = h.payload -> 'featureId'
                    LEFT JOIN trim t ON to_jsonb(t.id) = h.payload -> 'trimId'
                    LEFT JOIN region r ON r.code = h.payload ->> 'regionCode'
                    LEFT JOIN catalog_feature cf
                        ON cf.catalog_id = h.catalog_id AND cf.feature_id = f.id
                    LEFT JOIN catalog_trim ct
                        ON ct.catalog_id = h.catalog_id AND ct.trim_id = t.id
                    LEFT JOIN catalog_region cr
                        ON cr.catalog_id = h.catalog_id AND cr.region_code = r.code
                    WHERE h.catalog_id = :catalog
                    UNION ALL
                    -- A decision is told from a change by its negative id. A return is nobody's.
                    SELECT -d.id, d.created_at, a.display_name, d.decision, NULL, NULL, NULL, NULL,
                           NULL,
                           CASE d.decision
                               WHEN 'RETURNED_STALE'
                                   THEN 'Another catalog of its lineage was approved first.'
                               ELSE d.comment
                           END
                    FROM catalog_review d
                    LEFT JOIN app_user a ON a.id = d.reviewer_id
                    WHERE d.catalog_id = :catalog
                ) AS timeline
                -- Entries of one moment are one transaction's: its decision came last, and its
                -- changes in the order of their ids.
                ORDER BY at DESC, id < 0 DESC, abs(id) DESC
                LIMIT :limit OFFSET :offset
                """)
            .param("catalog", catalogId)
            .param("limit", limit)
            .param("offset", Math.clamp(page, 0, LAST_PAGE) * limit)
            .query(Change.class)
            .list();
    var total =
        jdbc.sql(
                """
                SELECT (SELECT count(*) FROM catalog_change WHERE catalog_id = :catalog)
                     + (SELECT count(*) FROM catalog_review WHERE catalog_id = :catalog)
                """)
            .param("catalog", catalogId)
            .query(Long.class)
            .single();

    return new ChangePage(items, total);
  }

  /** A page of a catalog's changes, newest first, and the number of changes in all. */
  record ChangePage(List<Change> items, long total) {}

  /**
   * One entry of a catalog's history: a change, with who made it, when, its kind, and what it
   * touched, named by its labels; or a decision, with its reviewer, its time, and the decision as
   * its kind. An entry names only what its kind is about, and the rest is null.
   *
   * @param id a change's own id, or the negative of a decision's, so that no two entries share one
   * @param actor who made the change or the decision; nobody for a catalog that was returned
   * @param kind a change's kind, or {@code APPROVED}, {@code REJECTED}, or {@code RETURNED_STALE}
   * @param oldValue what the change replaced: a cell's availability (S, A, or N) for a change of
   *     kind {@code CELL_SET}, a catalog's name for one of kind {@code RENAMED}, or a rule in words
   *     for one that changed or removed a rule
   * @param newValue what it was replaced with; a change that added a rule has this alone, and so
   *     has a decision: the reviewer's comment, or why the catalog was returned
   */
  record Change(
      long id,
      Instant at,
      String actor,
      String kind,
      String featureCode,
      String featureName,
      String trim,
      String region,
      String oldValue,
      String newValue) {}
}
