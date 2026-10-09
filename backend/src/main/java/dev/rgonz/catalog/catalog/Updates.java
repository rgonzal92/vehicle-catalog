package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.Current;
import dev.rgonz.catalog.catalog.Diff.Changes;
import dev.rgonz.catalog.catalog.Merge.Conflict;
import dev.rgonz.catalog.catalog.Merge.Side;
import dev.rgonz.catalog.core.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Updates a stale working copy from its lineage's current Approved: a merge of the two over the
 * nearest catalog they both descend from, which brings in what was approved and keeps what the
 * owner changed. The owner is shown the merge first, and settles its conflicts before it is
 * applied.
 */
@Service
class Updates {
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final CatalogEdits edits;
  private final Catalogs catalogs;
  private final Counter updates;

  Updates(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      CatalogEdits edits,
      Catalogs catalogs,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.edits = edits;
    this.catalogs = catalogs;
    this.updates =
        Counter.builder("catalog.merged")
            .description("How many working copies were updated from Approved")
            .register(metrics);
  }

  /**
   * What an update from Approved would do to the owner's stale Draft. It saves nothing, and reads
   * the three catalogs from one view of the database.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Preview preview(long catalogId, long ownerId) {
    var catalog =
        catalogs
            .find(catalogId, ownerId)
            .filter(CatalogView::owned)
            .orElseThrow(ApiException::notFound);
    if (catalog.snapshot().status() != Status.DRAFT) {
      throw ApiException.conflict(
          "NOT_DRAFT", "Only a catalog in status Draft can be updated from Approved.");
    }
    if (!catalog.stale()) {
      throw notStale();
    }
    var merge = merge(catalog, Map.of());

    return new Preview(
        catalog.snapshot().revision(),
        catalog.current(),
        Diff.between(catalog.snapshot(), merge.merged()),
        merge.conflicts());
  }

  /**
   * Updates the owner's stale Draft from its lineage's current Approved and answers with the
   * revision it is at afterwards. It follows the rules of an edit first: only the owner, only a
   * Draft, and only as they last saw it. Then the merge is worked out again, with every conflict
   * settled as the owner chose, and written in place of the catalog's contents. The current
   * Approved becomes the catalog's base, so it is stale no longer.
   *
   * @param ifMatch the revision the update was worked out from, as its {@code If-Match} header gave
   *     it
   * @param approvedCatalogId the Approved version the owner saw the update worked out against
   * @param resolutions for each conflict, by its id, whose version the owner takes
   */
  long apply(
      long catalogId,
      long ownerId,
      String ifMatch,
      Long approvedCatalogId,
      Map<String, Side> resolutions) {
    long updated =
        transactions.execute(
            transaction -> {
              edits.lockToEdit(catalogId, ownerId, ifMatch);
              var catalog = catalogs.find(catalogId, ownerId).orElseThrow(ApiException::notFound);
              if (!catalog.stale()) {
                throw notStale();
              }
              var approved = catalog.current();
              if (!Long.valueOf(approved.catalogId()).equals(approvedCatalogId)) {
                throw ApiException.conflict(
                    "APPROVED_MOVED",
                    "Approved v%d is the current version of this catalog's lineage by now. Work the"
                            .formatted(approved.versionNumber())
                        + " update out again from it.");
              }
              var merge = merge(catalog, resolutions == null ? Map.of() : resolutions);
              if (!merge.unsettled().isEmpty()) {
                throw ApiException.unresolvedConflicts(
                    "Choose a side for every conflict, then update the catalog.",
                    merge.unsettled());
              }

              requireWithinLimits(merge.merged());

              write(catalogId, merge.merged());
              jdbc.sql(
                      """
                  INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
                  VALUES (:catalog, :actor, 'MERGED', jsonb_build_object('new', :approved::text))
                  """)
                  .param("catalog", catalogId)
                  .param("actor", ownerId)
                  .param("approved", "Approved v" + approved.versionNumber())
                  .update();
              return jdbc.sql(
                      """
                  UPDATE catalog
                  SET base_catalog_id = :approved, revision = revision + 1, updated_at = now()
                  WHERE id = :id
                  RETURNING revision
                  """)
                  .param("approved", approved.catalogId())
                  .param("id", catalogId)
                  .query(Long.class)
                  .single();
            });
    updates.increment();

    return updated;
  }

  /**
   * Makes sure the merged catalog is no larger than a catalog can be. Each side is, but an update
   * can bring both sides' additions together.
   */
  static void requireWithinLimits(CatalogSnapshot merged) {
    requireAtMost(CatalogEdits.MOST_TRIMS, merged.trims().size(), "trims");
    requireAtMost(CatalogEdits.MOST_REGIONS, merged.regions().size(), "regions");
    requireAtMost(CatalogEdits.MOST_FEATURE_ROWS, merged.featureRows().size(), "feature rows");
    requireAtMost(CatalogRules.MOST_RULES, merged.rules().size(), "rules");
  }

  private static void requireAtMost(int most, int has, String things) {
    if (has > most) {
      throw ApiException.limitExceeded(
          "The update would give this catalog %,d %s, and a catalog has at most %,d. Remove some"
                  .formatted(has, things, most)
              + " of its own first, then update it.");
    }
  }

  private static ApiException notStale() {
    return ApiException.conflict(
        "NOT_STALE", "This catalog already has its lineage's current Approved as its base.");
  }

  /**
   * Puts the merged contents in place of the catalog's. Everything is written anew, since an update
   * can touch any of it. The rules go first, as they hold on to the feature rows they name; the
   * offerings, the cells, and the rules' scopes go with their trims, regions, and feature rows.
   */
  private void write(long catalog, CatalogSnapshot merged) {
    for (String table :
        List.of("catalog_rule", "catalog_feature", "catalog_trim", "catalog_region")) {
      jdbc.sql("DELETE FROM %s WHERE catalog_id = :catalog".formatted(table))
          .param("catalog", catalog)
          .update();
    }
    jdbc.sql(
            """
            INSERT INTO catalog_trim (catalog_id, trim_id)
            SELECT :catalog, id FROM unnest(:trims::bigint[]) AS added (id)
            """)
        .param("catalog", catalog)
        .param("trims", merged.trims().stream().map(Trim::id).toArray(Long[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_region (catalog_id, region_code)
            SELECT :catalog, code FROM unnest(:regions::text[]) AS added (code)
            """)
        .param("catalog", catalog)
        .param("regions", merged.regions().stream().map(Region::code).toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
            SELECT :catalog, o.trim_id, o.region_code
            FROM unnest(:trims::bigint[], :regions::text[]) AS o (trim_id, region_code)
            """)
        .param("catalog", catalog)
        .param("trims", merged.offerings().stream().map(Offering::trimId).toArray(Long[]::new))
        .param(
            "regions", merged.offerings().stream().map(Offering::regionCode).toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_feature (catalog_id, feature_id)
            SELECT :catalog, id FROM unnest(:features::bigint[]) AS added (id)
            """)
        .param("catalog", catalog)
        .param("features", merged.featureRows().stream().map(FeatureRow::id).toArray(Long[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, c.feature_id, c.trim_id, c.region_code, c.availability
            FROM unnest(:features::bigint[], :trims::bigint[], :regions::text[],
                        :availabilities::text[])
                     AS c (feature_id, trim_id, region_code, availability)
            """)
        .param("catalog", catalog)
        .param("features", merged.cells().stream().map(Cell::featureId).toArray(Long[]::new))
        .param("trims", merged.cells().stream().map(Cell::trimId).toArray(Long[]::new))
        .param("regions", merged.cells().stream().map(Cell::regionCode).toArray(String[]::new))
        .param(
            "availabilities",
            merged.cells().stream().map(cell -> cell.availability().name()).toArray(String[]::new))
        .update();

    var rules = merged.rules();
    jdbc.sql(
            """
            INSERT INTO catalog_rule (catalog_id, rule_key, kind, source_feature_id, all_trims,
                                      all_regions, pair_key)
            SELECT :catalog, r.key::uuid, r.kind, r.source, r.all_trims, r.all_regions,
                   r.pair_key::uuid
            FROM unnest(:keys::text[], :kinds::text[], :sources::bigint[], :allTrims::boolean[],
                        :allRegions::boolean[], :pairKeys::text[])
                     AS r (key, kind, source, all_trims, all_regions, pair_key)
            """)
        .param("catalog", catalog)
        .param("keys", rules.stream().map(Rule::key).toArray(String[]::new))
        .param("kinds", rules.stream().map(rule -> rule.kind().name()).toArray(String[]::new))
        .param("sources", rules.stream().map(Rule::sourceFeatureId).toArray(Long[]::new))
        .param("allTrims", rules.stream().map(Rule::allTrims).toArray(Boolean[]::new))
        .param("allRegions", rules.stream().map(Rule::allRegions).toArray(Boolean[]::new))
        .param("pairKeys", rules.stream().map(Rule::pairKey).toArray(String[]::new))
        .update();
    listed(
        catalog, rules, "catalog_rule_target", "feature_id", new Long[0], Rule::targetFeatureIds);
    listed(
        catalog,
        rules,
        "catalog_rule_trim",
        "trim_id",
        new Long[0],
        rule -> rule.allTrims() ? List.of() : rule.trimIds());
    listed(
        catalog,
        rules,
        "catalog_rule_region",
        "region_code",
        new String[0],
        rule -> rule.allRegions() ? List.of() : rule.regionCodes());
  }

  /**
   * Writes what each of the rules lists of one kind: its targets, or the trims or the regions it
   * covers.
   *
   * @param none an empty array of what is listed, which tells the database its type
   */
  private <T> void listed(
      long catalog,
      List<Rule> rules,
      String table,
      String column,
      T[] none,
      Function<Rule, Collection<T>> of) {
    var keys = new ArrayList<String>();
    var named = new ArrayList<T>();
    for (Rule rule : rules) {
      for (T one : of.apply(rule)) {
        keys.add(rule.key());
        named.add(one);
      }
    }
    jdbc.sql(
            """
            INSERT INTO %s (catalog_id, rule_key, %s)
            SELECT :catalog, l.key::uuid, l.named
            FROM unnest(:keys::text[], :named::%s[]) AS l (key, named)
            """
                .formatted(table, column, none instanceof String[] ? "text" : "bigint"))
        .param("catalog", catalog)
        .param("keys", keys.toArray(String[]::new))
        .param("named", named.toArray(none))
        .update();
  }

  /**
   * The merge of a stale working copy with its lineage's current Approved. All three catalogs are
   * named by the library's labels of today, so that nothing reads as two things.
   */
  private Merge.Result merge(CatalogView mine, Map<String, Side> resolutions) {
    var theirs = mine.current().catalogId();
    var base =
        jdbc.sql(
                """
                WITH RECURSIVE theirs AS (
                    SELECT c.id, c.base_catalog_id FROM catalog c WHERE c.id = :theirs
                    UNION ALL
                    SELECT c.id, c.base_catalog_id
                    FROM catalog c
                    JOIN theirs t ON c.id = t.base_catalog_id
                ), mine AS (
                    SELECT c.base_catalog_id AS id, 1 AS distance FROM catalog c WHERE c.id = :mine
                    UNION ALL
                    SELECT c.base_catalog_id, m.distance + 1
                    FROM catalog c
                    JOIN mine m ON c.id = m.id
                )
                SELECT m.id
                FROM mine m
                WHERE m.id IN (SELECT id FROM theirs)
                ORDER BY m.distance
                LIMIT 1
                """)
            .param("theirs", theirs)
            .param("mine", mine.snapshot().catalogId())
            .query(Long.class)
            .optional();

    return Merge.of(
        base.map(catalogs::approvedAsLabelledToday).orElseGet(CatalogSnapshot::empty),
        mine.snapshot(),
        catalogs.approvedAsLabelledToday(theirs),
        resolutions);
  }

  /**
   * What an update from Approved would do to a working copy.
   *
   * @param revision the working copy's revision that the update was worked out from
   * @param approved the Approved version it was worked out against
   * @param taken what the update changes in the working copy without asking: everything it brings
   *     in from the Approved version but the conflicts
   * @param conflicts what the owner has to settle before the update
   */
  record Preview(long revision, Current approved, Changes taken, List<Conflict> conflicts) {}
}
