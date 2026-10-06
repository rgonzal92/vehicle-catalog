package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edits working copies: their cells, the library trims and regions they have added, and where each
 * trim is sold. Only a catalog in status Draft can be edited, and only by its owner. Every edit
 * names the revision it was made from and runs in one transaction that locks the catalog, so edits
 * of one catalog happen one after another and none overwrites a change it has not seen.
 */
@Service
class CatalogEdits {
  /** The most cells one save sets. */
  private static final int MOST_CELLS = 500;

  /** The most trims a catalog has. */
  private static final int MOST_TRIMS = 12;

  /** The most regions a catalog has. */
  private static final int MOST_REGIONS = 8;

  /** What a save may set a cell to: S, A, or N. */
  private static final Set<String> AVAILABILITIES =
      Arrays.stream(Availability.values()).map(Enum::name).collect(Collectors.toSet());

  /** A revision as {@code If-Match} carries it: the number in quotes, as the entity tag gave it. */
  private static final Pattern REVISION = Pattern.compile("\"(\\d{1,18})\"");

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Timer editTime;

  CatalogEdits(JdbcClient jdbc, TransactionTemplate transactions, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.editTime =
        Timer.builder("catalog.edit")
            .description("How long saving an edit of a working copy takes")
            .register(metrics);
  }

  /**
   * Sets cells to the availabilities given, whatever they were, and answers with the catalog's
   * revision afterwards. A cell set to Not offered is no longer stored. Each cell whose
   * availability changed gets a change entry.
   *
   * @param ifMatch the revision the edit was made from, as its {@code If-Match} header gave it
   */
  long setCells(long catalogId, long actorId, String ifMatch, List<CellChange> given) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          if (given.size() > MOST_CELLS) {
            throw ApiException.limitExceeded("A save sets at most %d cells.".formatted(MOST_CELLS));
          }
          if (given.isEmpty()
              || !given.stream().allMatch(cell -> cell != null && cell.complete())) {
            throw ApiException.invalid(
                "Give at least one cell, each with a feature, a trim, a region, and S, A, or N.");
          }
          // A cell named more than once ends up as it was given last.
          var cells = new LinkedHashMap<List<Object>, CellChange>();
          given.forEach(
              cell -> cells.put(List.of(cell.featureId(), cell.trimId(), cell.regionCode()), cell));

          return store(catalogId, actorId, cells.values());
        });
  }

  /**
   * Adds library trims to the catalog. Only an active trim that the catalog does not have yet can
   * be added, and a catalog has at most {@value #MOST_TRIMS}. A new trim is sold nowhere until the
   * owner says where.
   */
  long addTrims(long catalogId, long actorId, String ifMatch, List<Long> trimIds) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          var ids = eachOnce(trimIds, "trim").toArray(Long[]::new);
          var counted =
              jdbc.sql(
                      """
                      SELECT (SELECT count(*) FROM trim t
                              WHERE t.id = ANY (:ids::bigint[]) AND t.active
                                AND NOT EXISTS (
                                    SELECT 1 FROM catalog_trim c
                                    WHERE c.catalog_id = :catalog AND c.trim_id = t.id)) AS addable,
                             (SELECT count(*) FROM catalog_trim WHERE catalog_id = :catalog) AS had
                      """)
                  .param("catalog", catalogId)
                  .param("ids", ids)
                  .query()
                  .singleRow();
          requireAddable(counted, ids.length, "trim", MOST_TRIMS);

          jdbc.sql(
                  """
                  INSERT INTO catalog_trim (catalog_id, trim_id)
                  SELECT :catalog, id FROM unnest(:ids::bigint[]) AS added (id)
                  """)
              .param("catalog", catalogId)
              .param("ids", ids)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  SELECT :catalog, :actor, 'TRIM_ADDED', jsonb_build_object('trimId', id)
                  FROM unnest(:ids::bigint[]) WITH ORDINALITY AS added (id, place)
                  ORDER BY place
                  """)
              .param("catalog", catalogId)
              .param("actor", actorId)
              .param("ids", ids)
              .update();
          return true;
        });
  }

  /**
   * Adds library regions to the catalog, under the same rules as trims, up to {@value
   * #MOST_REGIONS}.
   */
  long addRegions(long catalogId, long actorId, String ifMatch, List<String> regionCodes) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          var codes = eachOnce(regionCodes, "region").toArray(String[]::new);
          var counted =
              jdbc.sql(
                      """
                      SELECT (SELECT count(*) FROM region r
                              WHERE r.code = ANY (:codes::text[]) AND r.active
                                AND NOT EXISTS (
                                    SELECT 1 FROM catalog_region c
                                    WHERE c.catalog_id = :catalog AND c.region_code = r.code))
                                 AS addable,
                             (SELECT count(*) FROM catalog_region WHERE catalog_id = :catalog)
                                 AS had
                      """)
                  .param("catalog", catalogId)
                  .param("codes", codes)
                  .query()
                  .singleRow();
          requireAddable(counted, codes.length, "region", MOST_REGIONS);

          jdbc.sql(
                  """
                  INSERT INTO catalog_region (catalog_id, region_code)
                  SELECT :catalog, code FROM unnest(:codes::text[]) AS added (code)
                  """)
              .param("catalog", catalogId)
              .param("codes", codes)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  SELECT :catalog, :actor, 'REGION_ADDED', jsonb_build_object('regionCode', code)
                  FROM unnest(:codes::text[]) WITH ORDINALITY AS added (code, place)
                  ORDER BY place
                  """)
              .param("catalog", catalogId)
              .param("actor", actorId)
              .param("codes", codes)
              .update();
          return true;
        });
  }

  /**
   * Removes a trim from the catalog, and with it its offerings and their cells, which the database
   * sees to.
   */
  long removeTrim(long catalogId, long actorId, String ifMatch, long trimId) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          var removed =
              jdbc.sql("DELETE FROM catalog_trim WHERE catalog_id = :catalog AND trim_id = :trim")
                  .param("catalog", catalogId)
                  .param("trim", trimId)
                  .update();
          if (removed == 0) {
            throw ApiException.notFound();
          }
          jdbc.sql(
                  """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  VALUES (:catalog, :actor, 'TRIM_REMOVED', jsonb_build_object('trimId', :trim))
                  """)
              .param("catalog", catalogId)
              .param("actor", actorId)
              .param("trim", trimId)
              .update();
          return true;
        });
  }

  /** Removes a region from the catalog, and with it its offerings and their cells. */
  long removeRegion(long catalogId, long actorId, String ifMatch, String regionCode) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          var removed =
              jdbc.sql(
                      """
                      DELETE FROM catalog_region
                      WHERE catalog_id = :catalog AND region_code = :region
                      """)
                  .param("catalog", catalogId)
                  .param("region", regionCode)
                  .update();
          if (removed == 0) {
            throw ApiException.notFound();
          }
          jdbc.sql(
                  """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  VALUES (:catalog, :actor, 'REGION_REMOVED',
                          jsonb_build_object('regionCode', :region))
                  """)
              .param("catalog", catalogId)
              .param("actor", actorId)
              .param("region", regionCode)
              .update();
          return true;
        });
  }

  /**
   * Says where a trim of the catalog is sold: in exactly the regions given, each of which the
   * catalog has to have. An offering that is no longer wanted goes with its cells and nothing else;
   * a new one starts with every cell Not offered.
   */
  long sellIn(long catalogId, long actorId, String ifMatch, long trimId, List<String> regionCodes) {
    return edit(
        catalogId,
        actorId,
        ifMatch,
        () -> {
          if (regionCodes == null || regionCodes.contains(null) || repeats(regionCodes)) {
            throw ApiException.invalid("Name each region the trim is sold in once.");
          }
          var codes = regionCodes.toArray(String[]::new);
          var offering =
              jdbc.sql(
                      """
                      SELECT (SELECT count(*) FROM catalog_trim
                              WHERE catalog_id = :catalog AND trim_id = :trim) AS trims,
                             (SELECT count(*) FROM catalog_region
                              WHERE catalog_id = :catalog AND region_code = ANY (:codes::text[]))
                                 AS regions
                      """)
                  .param("catalog", catalogId)
                  .param("trim", trimId)
                  .param("codes", codes)
                  .query()
                  .singleRow();
          if ((long) offering.get("trims") == 0) {
            throw ApiException.notFound();
          }
          if ((long) offering.get("regions") != codes.length) {
            throw ApiException.invalid(
                "A trim can be sold only in a region the catalog has added.");
          }

          // The history is written first, while the offerings to be removed are still there and
          // the ones to be added are not.
          var removed =
              jdbc.sql(
                      """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  SELECT :catalog, :actor, 'OFFERING_REMOVED',
                         jsonb_build_object('trimId', o.trim_id, 'regionCode', o.region_code)
                  FROM catalog_trim_region o
                  JOIN region r ON r.code = o.region_code
                  WHERE o.catalog_id = :catalog AND o.trim_id = :trim
                    AND o.region_code <> ALL (:codes::text[])
                  ORDER BY r.sort_order, r.code
                  """)
                  .param("catalog", catalogId)
                  .param("actor", actorId)
                  .param("trim", trimId)
                  .param("codes", codes)
                  .update();
          var added =
              jdbc.sql(
                      """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  SELECT :catalog, :actor, 'OFFERING_ADDED',
                         jsonb_build_object('trimId', :trim, 'regionCode', code)
                  FROM unnest(:codes::text[]) WITH ORDINALITY AS wanted (code, place)
                  WHERE NOT EXISTS (
                      SELECT 1 FROM catalog_trim_region o
                      WHERE o.catalog_id = :catalog AND o.trim_id = :trim AND o.region_code = code)
                  ORDER BY place
                  """)
                  .param("catalog", catalogId)
                  .param("actor", actorId)
                  .param("trim", trimId)
                  .param("codes", codes)
                  .update();
          if (removed + added == 0) {
            return false;
          }
          jdbc.sql(
                  """
                  DELETE FROM catalog_trim_region
                  WHERE catalog_id = :catalog AND trim_id = :trim
                    AND region_code <> ALL (:codes::text[])
                  """)
              .param("catalog", catalogId)
              .param("trim", trimId)
              .param("codes", codes)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
                  SELECT :catalog, :trim, code FROM unnest(:codes::text[]) AS g (code)
                  ON CONFLICT DO NOTHING
                  """)
              .param("catalog", catalogId)
              .param("trim", trimId)
              .param("codes", codes)
              .update();
          return true;
        });
  }

  /** The library entries a request names to be added: at least one, each named once. */
  private static <K> List<K> eachOnce(List<K> keys, String entry) {
    if (keys == null || keys.isEmpty() || keys.contains(null) || repeats(keys)) {
      throw ApiException.invalid("Name at least one %s, each once.".formatted(entry));
    }
    return keys;
  }

  private static boolean repeats(List<?> keys) {
    return Set.copyOf(keys).size() != keys.size();
  }

  /**
   * Refuses entries that are not all addable, or that would take the catalog past the most it can
   * have.
   *
   * @param counted how many of the entries asked for are active and not yet in the catalog, as
   *     {@code addable}, and how many entries of the kind the catalog has, as {@code had}
   */
  private static void requireAddable(
      Map<String, Object> counted, int asked, String entry, int most) {
    if ((long) counted.get("addable") != asked) {
      throw ApiException.invalid(
          "Only an active %s of the library that the catalog does not have yet can be added."
              .formatted(entry));
    }
    if ((long) counted.get("had") + asked > most) {
      throw ApiException.limitExceeded("A catalog has at most %d %ss.".formatted(most, entry));
    }
  }

  /**
   * Stores the cells, each named once, records every one whose availability changes, and says
   * whether any did. They have to be cells of the catalog's own feature rows and offerings.
   */
  private boolean store(long catalogId, long actorId, Collection<CellChange> cells) {
    var outside =
        over(
                cells,
                """
                SELECT count(*)
                FROM given g
                WHERE NOT EXISTS (
                        SELECT 1 FROM catalog_feature f
                        WHERE f.catalog_id = :catalog AND f.feature_id = g.feature_id)
                   OR NOT EXISTS (
                        SELECT 1 FROM catalog_trim_region o
                        WHERE o.catalog_id = :catalog AND o.trim_id = g.trim_id
                          AND o.region_code = g.region_code)
                """)
            .param("catalog", catalogId)
            .query(Long.class)
            .single();
    if (outside > 0) {
      throw ApiException.invalid(
          "A cell can be set only for a feature row and an offering of this catalog.");
    }

    // The history is written first, while the cells still hold their old values.
    var changed =
        over(
                cells,
                """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            SELECT :catalog, :actor, 'CELL_SET',
                   jsonb_build_object('featureId', g.feature_id, 'trimId', g.trim_id,
                                      'regionCode', g.region_code,
                                      'old', COALESCE(c.availability, 'N'), 'new', g.wanted)
            FROM given g
            LEFT JOIN catalog_cell c
                ON c.catalog_id = :catalog AND c.feature_id = g.feature_id
                    AND c.trim_id = g.trim_id AND c.region_code = g.region_code
            WHERE COALESCE(c.availability, 'N') <> g.wanted
            ORDER BY g.place
            """)
            .param("catalog", catalogId)
            .param("actor", actorId)
            .update();
    if (changed == 0) {
      return false;
    }
    over(
            cells,
            """
            DELETE FROM catalog_cell c
            USING given g
            WHERE c.catalog_id = :catalog AND c.feature_id = g.feature_id
              AND c.trim_id = g.trim_id AND c.region_code = g.region_code AND g.wanted = 'N'
            """)
        .param("catalog", catalogId)
        .update();
    over(
            cells,
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, feature_id, trim_id, region_code, wanted FROM given WHERE wanted <> 'N'
            ON CONFLICT (catalog_id, feature_id, trim_id, region_code)
                DO UPDATE SET availability = EXCLUDED.availability
            """)
        .param("catalog", catalogId)
        .update();

    return true;
  }

  /** A statement over the cells, which it reads as the rows of {@code given}, in their order. */
  private StatementSpec over(Collection<CellChange> cells, String statement) {
    return jdbc.sql(
            """
            WITH given AS (
                SELECT *
                FROM unnest(:features::bigint[], :trims::bigint[], :regions::text[], :wanted::text[])
                    WITH ORDINALITY AS g (feature_id, trim_id, region_code, wanted, place)
            )
            """
                + statement)
        .param("features", cells.stream().map(CellChange::featureId).toArray(Long[]::new))
        .param("trims", cells.stream().map(CellChange::trimId).toArray(Long[]::new))
        .param("regions", cells.stream().map(CellChange::regionCode).toArray(String[]::new))
        .param("wanted", cells.stream().map(CellChange::availability).toArray(String[]::new));
  }

  /**
   * Runs one edit of a working copy and answers with the revision the catalog is at afterwards. An
   * edit that asks for what is already so changes nothing: the revision stays where it was, which
   * tells the caller so, and nothing is recorded. The catalog's row stays locked from the first
   * check to the commit. The checks come in one order for every edit: that the catalog is the
   * caller's, that it is in status Draft, that the edit names the revision the catalog is at, and
   * only then whatever the change itself asks. So no refusal tells anybody anything about a catalog
   * that is not theirs. A refused edit changes nothing.
   *
   * @param change makes the change and says whether it changed anything
   *     <p>The time is taken through the commit, and only of an edit that is saved.
   */
  private long edit(long catalogId, long actorId, String ifMatch, BooleanSupplier change) {
    var started = System.nanoTime();
    var revision =
        transactions.execute(
            transaction -> {
              var catalog =
                  jdbc.sql(
                          "SELECT owner_id, status, revision FROM catalog WHERE id = :id FOR UPDATE")
                      .param("id", catalogId)
                      .query(Locked.class)
                      .optional()
                      .filter(found -> found.ownerId() == actorId)
                      .orElseThrow(ApiException::notFound);
              if (catalog.status() != Status.DRAFT) {
                throw ApiException.conflict(
                    "NOT_DRAFT", "Only a catalog in status Draft can be edited.");
              }
              if (catalog.revision() != expectedRevision(ifMatch)) {
                throw ApiException.revisionConflict(catalog.revision());
              }

              if (!change.getAsBoolean()) {
                return catalog.revision();
              }

              return jdbc.sql(
                      """
                      UPDATE catalog SET revision = revision + 1, updated_at = now()
                      WHERE id = :id
                      RETURNING revision
                      """)
                  .param("id", catalogId)
                  .query(Long.class)
                  .single();
            });
    editTime.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);

    return revision;
  }

  /** The revision an edit says it was made from. */
  private static long expectedRevision(String ifMatch) {
    if (ifMatch == null) {
      throw ApiException.revisionRequired();
    }
    var revision = REVISION.matcher(ifMatch.strip());
    if (!revision.matches()) {
      throw ApiException.badRequest(
          "If-Match takes the catalog's revision in quotes, as in \"42\".");
    }
    return Long.parseLong(revision.group(1));
  }

  /** One cell of a save: the availability a feature is to have in an offering. */
  record CellChange(Long featureId, Long trimId, String regionCode, String availability) {
    boolean complete() {
      return featureId != null
          && trimId != null
          && regionCode != null
          && AVAILABILITIES.contains(availability);
    }
  }

  /** What an edit checks of a catalog once it has locked it. */
  private record Locked(long ownerId, Status status, long revision) {}
}
