package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Revisions.Change;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.library.RuleKind;
import dev.rgonz.catalog.library.Rules;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The rules that belong to one catalog. A rule relates a source feature to its targets, in the
 * offerings its trim scope and its region scope cover. An exclusion holds both ways, so it is kept
 * as two paired rules that share a pair key and both scopes, and are made, changed, and deleted as
 * one.
 *
 * <p>Every method here is part of an edit of a working copy: it runs inside that edit's
 * transaction, once the catalog is locked and the caller is known to be its owner. A method that
 * changes rules answers with the changes it made, which the edit records.
 */
@Component
class CatalogRules {
  /** The most rules a catalog has. A pair counts as two. */
  static final int MOST_RULES = 500;

  private static final String ALREADY_THERE = "This rule already exists.";

  private final JdbcClient jdbc;

  CatalogRules(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Adds a rule, or for an exclusion a pair for each target, each of which is a change. */
  List<Change> add(long catalogId, RuleContent given) {
    var changes = new ArrayList<Change>();
    for (var rule : whatToAdd(catalogId, given)) {
      var pairKey = rule.kind() == RuleKind.EXCLUDES ? UUID.randomUUID() : null;
      var key = insert(catalogId, rule, pairKey);
      if (pairKey != null) {
        insert(
            catalogId,
            rule.naming(rule.targetFeatureIds().getFirst(), rule.sourceFeatureId()),
            pairKey);
      }
      changes.add(Change.ruleAdded(named(catalogId, key).inWords()));
    }
    return changes;
  }

  /**
   * Why the content cannot be added as a rule, or null when it can. Nothing is added: this is what
   * a suggested rule is held to before anyone is shown it.
   */
  String refusalOf(long catalogId, RuleContent given) {
    try {
      whatToAdd(catalogId, given);
      return null;
    } catch (ApiException refused) {
      return refused.getBody().getDetail();
    }
  }

  /**
   * The rules to add for the content, which is one, or for an exclusion one for each target. It
   * refuses content that may not be added: what a rule may not name, more rules than a catalog has,
   * and a rule that is there already.
   */
  private List<RuleContent> whatToAdd(long catalogId, RuleContent given) {
    Rules.checkWhatARuleNames(given.content(), inTheCatalog(catalogId));
    var forward =
        given.kind() == RuleKind.EXCLUDES
            ? given.targetFeatureIds().stream()
                .map(target -> given.naming(given.sourceFeatureId(), target))
                .toList()
            : List.of(given);
    var rows = given.kind() == RuleKind.EXCLUDES ? forward.size() * 2 : 1;
    if (count(catalogId) + rows > MOST_RULES) {
      throw ApiException.limitExceeded(
          "A catalog has at most %d rules, a pair counting as two.".formatted(MOST_RULES));
    }
    for (var rule : forward) {
      if (!sayingTheSame(catalogId, rule).isEmpty()) {
        throw ApiException.invalid(ALREADY_THERE);
      }
    }
    return forward;
  }

  /**
   * Changes a rule, and its pair with it by the mirrored change, which is one change, or none when
   * the rule says so already.
   */
  List<Change> change(long catalogId, UUID ruleKey, RuleContent given) {
    var before = find(catalogId, ruleKey).orElseThrow(ApiException::notFound);
    if (before.kind() != given.kind()) {
      throw ApiException.invalid(
          "A rule's kind cannot be changed. Delete the rule and add another.");
    }
    if (given.kind() == RuleKind.EXCLUDES
        && given.targetFeatureIds() != null
        && given.targetFeatureIds().size() != 1) {
      throw ApiException.invalid("An Excludes rule has exactly one target.");
    }
    Rules.checkWhatARuleNames(given.content(), inTheCatalog(catalogId));
    var pair =
        before.pairKey() == null ? List.of(ruleKey) : keysOfPair(catalogId, before.pairKey());
    var same = sayingTheSame(catalogId, given);
    // A pair says the same whichever of its two rules says it.
    if (same.stream().anyMatch(pair::contains)) {
      return List.of();
    }
    if (!same.isEmpty()) {
      throw ApiException.invalid(ALREADY_THERE);
    }
    var said = named(catalogId, ruleKey).inWords();
    rewrite(catalogId, ruleKey, given);
    for (var other : pair) {
      if (!other.equals(ruleKey)) {
        rewrite(
            catalogId,
            other,
            given.naming(given.targetFeatureIds().getFirst(), given.sourceFeatureId()));
      }
    }

    return List.of(Change.ruleUpdated(said, named(catalogId, ruleKey).inWords()));
  }

  /** Deletes a rule, and its pair with it, which is one change. */
  Change delete(long catalogId, UUID ruleKey) {
    var rule = find(catalogId, ruleKey).orElseThrow(ApiException::notFound);
    var removed = Change.ruleRemoved(named(catalogId, ruleKey).inWords());
    jdbc.sql(
            """
            DELETE FROM catalog_rule
            WHERE catalog_id = :catalog
              AND (rule_key = :key OR pair_key = :pairKey)
            """)
        .param("catalog", catalogId)
        .param("key", ruleKey)
        .param("pairKey", rule.pairKey())
        .update();

    return removed;
  }

  /**
   * The catalog's rules that name the feature, as their source or as a target, each in words, and a
   * pair once. A feature row that any of them names cannot be removed.
   */
  List<String> naming(long catalogId, long featureId) {
    return keysOfThoseNaming(catalogId, featureId).stream()
        .map(key -> named(catalogId, key).inWords())
        .sorted()
        .toList();
  }

  /**
   * Deletes the rules that name the feature, a pair as one, which is what lets its feature row be
   * removed. Each is a change, a pair once.
   */
  List<Change> deleteThoseNaming(long catalogId, long featureId) {
    return keysOfThoseNaming(catalogId, featureId).stream()
        .map(key -> delete(catalogId, key))
        .toList();
  }

  /** The rules that name the feature. Of a pair, it is the rule that starts from the feature. */
  private List<UUID> keysOfThoseNaming(long catalogId, long featureId) {
    return jdbc.sql(
            """
            SELECT r.rule_key
            FROM catalog_rule r
            WHERE r.catalog_id = :catalog
              AND (r.source_feature_id = :feature
                   OR (r.pair_key IS NULL
                       AND EXISTS (SELECT 1 FROM catalog_rule_target t
                                   WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                                     AND t.feature_id = :feature)))
            ORDER BY r.rule_key
            """)
        .param("catalog", catalogId)
        .param("feature", featureId)
        .query(UUID.class)
        .list();
  }

  /**
   * Deletes the rules whose trim scope lists the trim and no other, which are the ones that would
   * cover nothing once the trim has left the catalog. A pair has the same scopes, so it goes as
   * one. Each is a change, a pair once.
   */
  List<Change> deleteThoseOnlyOnTrim(long catalogId, long trimId) {
    return deleteThoseListingOnly(catalogId, "catalog_rule_trim", "trim_id", trimId);
  }

  /** The same for the rules whose region scope lists the region and no other. */
  List<Change> deleteThoseOnlyInRegion(long catalogId, String regionCode) {
    return deleteThoseListingOnly(catalogId, "catalog_rule_region", "region_code", regionCode);
  }

  private List<Change> deleteThoseListingOnly(
      long catalogId, String scope, String column, Object listed) {
    var alone =
        jdbc.sql(
                """
                SELECT r.rule_key, r.kind, r.source_feature_id, r.all_trims, r.all_regions,
                       r.pair_key
                FROM catalog_rule r
                JOIN feature f ON f.id = r.source_feature_id
                WHERE r.catalog_id = :catalog
                  AND EXISTS (SELECT 1 FROM %1$s s
                              WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key
                                AND s.%2$s = :listed)
                  AND NOT EXISTS (SELECT 1 FROM %1$s s
                                  WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key
                                    AND s.%2$s <> :listed)
                -- Of a pair, the rule whose source comes first by code says it for both.
                ORDER BY f.code, r.rule_key
                """
                    .formatted(scope, column))
            .param("catalog", catalogId)
            .param("listed", listed)
            .query(Header.class)
            .list();
    var deleted = new ArrayList<Change>();
    var pairsSaid = new HashSet<UUID>();
    for (var rule : alone) {
      if (rule.pairKey() == null || pairsSaid.add(rule.pairKey())) {
        deleted.add(Change.ruleRemoved(named(catalogId, rule.ruleKey()).inWords()));
      }
    }
    for (var rule : alone) {
      jdbc.sql("DELETE FROM catalog_rule WHERE catalog_id = :catalog AND rule_key = :key")
          .param("catalog", catalogId)
          .param("key", rule.ruleKey())
          .update();
    }

    return deleted;
  }

  /**
   * The Excludes rules, of any catalog, that are not one of a whole pair: for each of them there
   * must be exactly one other rule of its catalog with the same pair key, the source and the target
   * swapped, and the same trim scope and region scope. None is ever found unless something has gone
   * wrong.
   */
  List<UUID> brokenPairs() {
    return jdbc.sql(
            """
            SELECT r.rule_key
            FROM catalog_rule r
            WHERE r.kind = 'EXCLUDES'
              AND NOT (
                (SELECT count(*) FROM catalog_rule_target t
                 WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key) = 1
                AND (SELECT count(*) FROM catalog_rule o
                     WHERE o.catalog_id = r.catalog_id AND o.pair_key = r.pair_key) = 2
                AND EXISTS (
                  SELECT 1
                  FROM catalog_rule o
                  WHERE o.catalog_id = r.catalog_id AND o.pair_key = r.pair_key
                    AND o.rule_key <> r.rule_key AND o.kind = 'EXCLUDES'
                    AND o.all_trims = r.all_trims AND o.all_regions = r.all_regions
                    AND EXISTS (SELECT 1 FROM catalog_rule_target t
                                WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                                  AND t.feature_id = o.source_feature_id)
                    AND EXISTS (SELECT 1 FROM catalog_rule_target t
                                WHERE t.catalog_id = o.catalog_id AND t.rule_key = o.rule_key
                                  AND t.feature_id = r.source_feature_id)
                    AND NOT EXISTS (
                      SELECT trim_id FROM catalog_rule_trim
                      WHERE catalog_id = r.catalog_id AND rule_key = r.rule_key
                      EXCEPT
                      SELECT trim_id FROM catalog_rule_trim
                      WHERE catalog_id = o.catalog_id AND rule_key = o.rule_key)
                    AND NOT EXISTS (
                      SELECT trim_id FROM catalog_rule_trim
                      WHERE catalog_id = o.catalog_id AND rule_key = o.rule_key
                      EXCEPT
                      SELECT trim_id FROM catalog_rule_trim
                      WHERE catalog_id = r.catalog_id AND rule_key = r.rule_key)
                    AND NOT EXISTS (
                      SELECT region_code FROM catalog_rule_region
                      WHERE catalog_id = r.catalog_id AND rule_key = r.rule_key
                      EXCEPT
                      SELECT region_code FROM catalog_rule_region
                      WHERE catalog_id = o.catalog_id AND rule_key = o.rule_key)
                    AND NOT EXISTS (
                      SELECT region_code FROM catalog_rule_region
                      WHERE catalog_id = o.catalog_id AND rule_key = o.rule_key
                      EXCEPT
                      SELECT region_code FROM catalog_rule_region
                      WHERE catalog_id = r.catalog_id AND rule_key = r.rule_key)))
            ORDER BY r.rule_key
            """)
        .query(UUID.class)
        .list();
  }

  private long count(long catalogId) {
    return jdbc.sql("SELECT count(*) FROM catalog_rule WHERE catalog_id = :catalog")
        .param("catalog", catalogId)
        .query(Long.class)
        .single();
  }

  private Optional<Header> find(long catalogId, UUID ruleKey) {
    return jdbc.sql(
            """
            SELECT rule_key, kind, source_feature_id, all_trims, all_regions, pair_key
            FROM catalog_rule
            WHERE catalog_id = :catalog AND rule_key = :key
            """)
        .param("catalog", catalogId)
        .param("key", ruleKey)
        .query(Header.class)
        .optional();
  }

  private List<UUID> keysOfPair(long catalogId, UUID pairKey) {
    return jdbc.sql(
            "SELECT rule_key FROM catalog_rule WHERE catalog_id = :catalog AND pair_key = :pairKey")
        .param("catalog", catalogId)
        .param("pairKey", pairKey)
        .query(UUID.class)
        .list();
  }

  private UUID insert(long catalogId, RuleContent given, UUID pairKey) {
    var key = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO catalog_rule
                (catalog_id, rule_key, kind, source_feature_id, all_trims, all_regions, pair_key)
            VALUES (:catalog, :key, :kind, :source, :allTrims, :allRegions, :pairKey)
            """)
        .param("catalog", catalogId)
        .param("key", key)
        .param("kind", given.kind().name())
        .param("source", given.sourceFeatureId())
        .param("allTrims", given.allTrims())
        .param("allRegions", given.allRegions())
        .param("pairKey", pairKey)
        .update();
    writeTargetsAndScopes(catalogId, key, given);

    return key;
  }

  /** Gives a rule the content, in place of what it had. Its key, kind, and pair key stay. */
  private void rewrite(long catalogId, UUID ruleKey, RuleContent given) {
    jdbc.sql(
            """
            UPDATE catalog_rule
            SET source_feature_id = :source, all_trims = :allTrims, all_regions = :allRegions
            WHERE catalog_id = :catalog AND rule_key = :key
            """)
        .param("source", given.sourceFeatureId())
        .param("allTrims", given.allTrims())
        .param("allRegions", given.allRegions())
        .param("catalog", catalogId)
        .param("key", ruleKey)
        .update();
    for (var table : List.of("catalog_rule_target", "catalog_rule_trim", "catalog_rule_region")) {
      jdbc.sql("DELETE FROM " + table + " WHERE catalog_id = :catalog AND rule_key = :key")
          .param("catalog", catalogId)
          .param("key", ruleKey)
          .update();
    }
    writeTargetsAndScopes(catalogId, ruleKey, given);
  }

  private void writeTargetsAndScopes(long catalogId, UUID ruleKey, RuleContent given) {
    for (var target : new TreeSet<>(given.targetFeatureIds())) {
      jdbc.sql("INSERT INTO catalog_rule_target VALUES (:catalog, :key, :target)")
          .param("catalog", catalogId)
          .param("key", ruleKey)
          .param("target", target)
          .update();
    }
    for (var trim : given.trims()) {
      jdbc.sql("INSERT INTO catalog_rule_trim VALUES (:catalog, :key, :trim)")
          .param("catalog", catalogId)
          .param("key", ruleKey)
          .param("trim", trim)
          .update();
    }
    for (var region : given.regions()) {
      jdbc.sql("INSERT INTO catalog_rule_region VALUES (:catalog, :key, :region)")
          .param("catalog", catalogId)
          .param("key", ruleKey)
          .param("region", region)
          .update();
    }
  }

  /** What a rule of the catalog may name: its feature rows, and its active trims and regions. */
  private Rules.Nameable inTheCatalog(long catalogId) {
    return new Rules.Nameable(
        ids ->
            jdbc.sql(
                    """
                    SELECT f.id, f.name, f.kind, f.status
                    FROM catalog_feature c
                    JOIN feature f ON f.id = c.feature_id
                    WHERE c.catalog_id = :catalog AND f.id IN (:ids)
                    """)
                .param("catalog", catalogId)
                .param("ids", ids)
                .query(Rules.NamedFeature.class)
                .list(),
        "Choose features from the catalog's feature rows.",
        ids ->
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM catalog_trim c
                    JOIN trim t ON t.id = c.trim_id
                    WHERE c.catalog_id = :catalog AND t.id IN (:ids) AND t.active
                    """)
                .param("catalog", catalogId)
                .param("ids", ids)
                .query(Long.class)
                .single(),
        "Choose active trims of the catalog.",
        codes ->
            jdbc.sql(
                    """
                    SELECT count(*)
                    FROM catalog_region c
                    JOIN region r ON r.code = c.region_code
                    WHERE c.catalog_id = :catalog AND r.code IN (:codes) AND r.active
                    """)
                .param("catalog", catalogId)
                .param("codes", codes)
                .query(Long.class)
                .single(),
        "Choose active regions of the catalog.");
  }

  /**
   * The rules of the catalog that say what the content says. Both rules of a pair are there to be
   * found, so an exclusion is found whichever way round it is given.
   */
  private List<UUID> sayingTheSame(long catalogId, RuleContent given) {
    return jdbc.sql(
            """
            SELECT r.rule_key
            FROM catalog_rule r
            WHERE r.catalog_id = :catalog AND r.kind = :kind
              AND r.source_feature_id = :source
              AND r.all_trims = :allTrims AND r.all_regions = :allRegions
              AND ARRAY(SELECT feature_id FROM catalog_rule_target t
                        WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                        ORDER BY feature_id) = :targets::bigint[]
              AND ARRAY(SELECT trim_id FROM catalog_rule_trim t
                        WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                        ORDER BY trim_id) = :trims::bigint[]
              AND ARRAY(SELECT region_code FROM catalog_rule_region s
                        WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key
                        ORDER BY region_code) = :regions::text[]
            """)
        .param("catalog", catalogId)
        .param("kind", given.kind().name())
        .param("source", given.sourceFeatureId())
        .param("allTrims", given.allTrims())
        .param("allRegions", given.allRegions())
        .param("targets", new TreeSet<>(given.targetFeatureIds()).toArray(Long[]::new))
        .param("trims", given.trims().toArray(Long[]::new))
        .param("regions", given.regions().toArray(String[]::new))
        .query(UUID.class)
        .list();
  }

  /** A rule by the library's names of what it names. */
  private Rules.Named named(long catalogId, UUID ruleKey) {
    return jdbc.sql(
            """
            SELECT r.kind, f.name AS source,
                   ARRAY(SELECT tf.name
                         FROM catalog_rule_target t JOIN feature tf ON tf.id = t.feature_id
                         WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                         ORDER BY tf.code) AS targets,
                   CASE WHEN NOT r.all_trims THEN
                       ARRAY(SELECT tr.name
                             FROM catalog_rule_trim t JOIN trim tr ON tr.id = t.trim_id
                             WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                             ORDER BY tr.sort_order, tr.id) END AS trims,
                   CASE WHEN NOT r.all_regions THEN
                       ARRAY(SELECT rg.name
                             FROM catalog_rule_region s JOIN region rg ON rg.code = s.region_code
                             WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key
                             ORDER BY rg.sort_order, rg.code) END AS regions
            FROM catalog_rule r
            JOIN feature f ON f.id = r.source_feature_id
            WHERE r.catalog_id = :catalog AND r.rule_key = :key
            """)
        .param("catalog", catalogId)
        .param("key", ruleKey)
        .query(
            (row, _) ->
                new Rules.Named(
                    row.getString("source"),
                    RuleKind.valueOf(row.getString("kind")),
                    names(row.getArray("targets")),
                    names(row.getArray("trims")),
                    names(row.getArray("regions"))))
        .single();
  }

  /** The names an array holds, or null for no array. */
  private static List<String> names(Array names) throws SQLException {
    return names == null ? null : List.of((String[]) names.getArray());
  }

  /**
   * What an owner gives to add a rule or to change one.
   *
   * @param trimIds the trims the rule covers; they count for nothing when it covers every trim
   * @param regionCodes the regions the rule covers; they count for nothing when it covers every one
   */
  record RuleContent(
      RuleKind kind,
      Long sourceFeatureId,
      List<Long> targetFeatureIds,
      boolean allTrims,
      List<Long> trimIds,
      boolean allRegions,
      List<String> regionCodes) {

    /** What the rule names, as the check of what a rule may name takes it. */
    Rules.Content content() {
      return new Rules.Content(
          kind,
          sourceFeatureId,
          targetFeatureIds,
          allTrims ? null : trims(),
          allRegions ? null : regions());
    }

    /** The trims the rule lists, in order: none when it covers every trim. */
    List<Long> trims() {
      return allTrims ? List.of() : inOrder(trimIds);
    }

    /** The regions the rule lists, in order: none when it covers every region. */
    List<String> regions() {
      return allRegions ? List.of() : inOrder(regionCodes);
    }

    private static <K> List<K> inOrder(List<K> keys) {
      return keys == null
          ? List.of()
          : keys.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** The same kind and scopes, from one feature to another. */
    RuleContent naming(long source, long target) {
      return new RuleContent(
          kind, source, List.of(target), allTrims, trimIds, allRegions, regionCodes);
    }
  }

  private record Header(
      UUID ruleKey,
      RuleKind kind,
      long sourceFeatureId,
      boolean allTrims,
      boolean allRegions,
      UUID pairKey) {}
}
