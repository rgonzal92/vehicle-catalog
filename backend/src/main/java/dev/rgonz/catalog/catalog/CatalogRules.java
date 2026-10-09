package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.library.RuleKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The rules that belong to one catalog. A rule relates a source feature to its targets, in the
 * offerings its trim scope and its region scope cover. An exclusion holds both ways, so it is kept
 * as two paired rules that share a pair key and both scopes, and are made, changed, and deleted as
 * one.
 *
 * <p>Every method here is part of an edit of a working copy: it runs inside that edit's
 * transaction, once the catalog is locked and the caller is known to be its owner.
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

  /** Adds a rule, or for an exclusion a pair for each target. It records each as a change. */
  void add(long catalogId, long actorId, RuleContent given) {
    checkWhatARuleNames(catalogId, given);
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
      var pairKey = rule.kind() == RuleKind.EXCLUDES ? UUID.randomUUID() : null;
      var key = insert(catalogId, rule, pairKey);
      if (pairKey != null) {
        insert(
            catalogId,
            rule.naming(rule.targetFeatureIds().getFirst(), rule.sourceFeatureId()),
            pairKey);
      }
      record(catalogId, actorId, "RULE_ADDED", null, inWords(catalogId, key));
    }
  }

  /**
   * Changes a rule, and its pair with it by the mirrored change, and says whether anything changed.
   * It records one change.
   */
  boolean change(long catalogId, long actorId, UUID ruleKey, RuleContent given) {
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
    checkWhatARuleNames(catalogId, given);
    var pair =
        before.pairKey() == null ? List.of(ruleKey) : keysOfPair(catalogId, before.pairKey());
    var same = sayingTheSame(catalogId, given);
    // A pair says the same whichever of its two rules says it.
    if (same.stream().anyMatch(pair::contains)) {
      return false;
    }
    if (!same.isEmpty()) {
      throw ApiException.invalid(ALREADY_THERE);
    }
    var said = inWords(catalogId, ruleKey);
    rewrite(catalogId, ruleKey, given);
    for (var other : pair) {
      if (!other.equals(ruleKey)) {
        rewrite(
            catalogId,
            other,
            given.naming(given.targetFeatureIds().getFirst(), given.sourceFeatureId()));
      }
    }
    record(catalogId, actorId, "RULE_UPDATED", said, inWords(catalogId, ruleKey));

    return true;
  }

  /** Deletes a rule, and its pair with it. It records one change. */
  void delete(long catalogId, long actorId, UUID ruleKey) {
    var rule = find(catalogId, ruleKey).orElseThrow(ApiException::notFound);
    record(catalogId, actorId, "RULE_REMOVED", inWords(catalogId, ruleKey), null);
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
  }

  /**
   * The catalog's rules that name the feature, as their source or as a target, each in words, and a
   * pair once. A feature row that any of them names cannot be removed.
   */
  List<String> naming(long catalogId, long featureId) {
    return keysOfThoseNaming(catalogId, featureId).stream()
        .map(key -> inWords(catalogId, key))
        .sorted()
        .toList();
  }

  /**
   * Deletes the rules that name the feature, a pair as one, which is what lets its feature row be
   * removed. It records each as a change, a pair once, and answers with the deleted rules in words.
   */
  List<String> deleteThoseNaming(long catalogId, long actorId, long featureId) {
    var deleted = new ArrayList<String>();
    for (var key : keysOfThoseNaming(catalogId, featureId)) {
      deleted.add(inWords(catalogId, key));
      delete(catalogId, actorId, key);
    }
    deleted.sort(null);

    return deleted;
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
   * one. It records each as a change, a pair once, and answers with the deleted rules in words.
   */
  List<String> deleteThoseOnlyOnTrim(long catalogId, long actorId, long trimId) {
    return deleteThoseListingOnly(catalogId, actorId, "catalog_rule_trim", "trim_id", trimId);
  }

  /** The same for the rules whose region scope lists the region and no other. */
  List<String> deleteThoseOnlyInRegion(long catalogId, long actorId, String regionCode) {
    return deleteThoseListingOnly(
        catalogId, actorId, "catalog_rule_region", "region_code", regionCode);
  }

  private List<String> deleteThoseListingOnly(
      long catalogId, long actorId, String scope, String column, Object listed) {
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
    var deleted = new ArrayList<String>();
    var pairsSaid = new HashSet<UUID>();
    for (var rule : alone) {
      if (rule.pairKey() == null || pairsSaid.add(rule.pairKey())) {
        var said = inWords(catalogId, rule.ruleKey());
        deleted.add(said);
        record(catalogId, actorId, "RULE_REMOVED", said, null);
      }
    }
    for (var rule : alone) {
      jdbc.sql("DELETE FROM catalog_rule WHERE catalog_id = :catalog AND rule_key = :key")
          .param("catalog", catalogId)
          .param("key", rule.ruleKey())
          .update();
    }
    deleted.sort(null);

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

  /** Refuses content whose targets, features, or scopes are not what a catalog rule may name. */
  private void checkWhatARuleNames(long catalogId, RuleContent given) {
    var targets = given.targetFeatureIds();
    if (given.kind() == null) {
      throw ApiException.invalid("Choose a kind.");
    }
    if (given.sourceFeatureId() == null) {
      throw ApiException.invalid("Choose a source.");
    }
    if (targets == null || targets.stream().anyMatch(Objects::isNull)) {
      throw ApiException.invalid("Choose at least one target.");
    }
    if (targets.size() > RuleKind.MOST_TARGETS) {
      throw ApiException.limitExceeded(
          "A rule has at most %d targets.".formatted(RuleKind.MOST_TARGETS));
    }
    var refusal = given.kind().refusalOf(given.sourceFeatureId(), targets);
    if (refusal != null) {
      throw ApiException.invalid(refusal);
    }

    var named = new ArrayList<>(targets);
    named.add(given.sourceFeatureId());
    Map<Long, Named> rows =
        jdbc
            .sql(
                """
                SELECT f.id, f.name, f.kind, f.status
                FROM catalog_feature c
                JOIN feature f ON f.id = c.feature_id
                WHERE c.catalog_id = :catalog AND f.id IN (:ids)
                """)
            .param("catalog", catalogId)
            .param("ids", named)
            .query(Named.class)
            .list()
            .stream()
            .collect(Collectors.toMap(Named::id, Function.identity()));
    if (rows.size() < named.size()) {
      throw ApiException.invalid("Choose features from the catalog's feature rows.");
    }
    for (var feature : rows.values()) {
      if (feature.status().equals("RETIRED")) {
        throw ApiException.invalid(
            "%s is retired, and a rule cannot name it.".formatted(feature.name()));
      }
    }
    if (given.kind() == RuleKind.INCLUDES
        && !rows.get(given.sourceFeatureId()).kind().equals("PACKAGE")) {
      throw ApiException.invalid("Only a package includes other features.");
    }

    if (!given.allTrims()) {
      var trims = given.trims();
      var active =
          trims.isEmpty()
              ? 0
              : jdbc.sql(
                      """
                      SELECT count(*)
                      FROM catalog_trim c
                      JOIN trim t ON t.id = c.trim_id
                      WHERE c.catalog_id = :catalog AND t.id IN (:ids) AND t.active
                      """)
                  .param("catalog", catalogId)
                  .param("ids", trims)
                  .query(Long.class)
                  .single();
      if (trims.isEmpty()) {
        throw ApiException.invalid("Choose the trims the rule covers, or every trim.");
      }
      if (active < trims.size()) {
        throw ApiException.invalid("Choose active trims of the catalog.");
      }
    }
    if (!given.allRegions()) {
      var regions = given.regions();
      if (regions.isEmpty()) {
        throw ApiException.invalid("Choose the regions the rule covers, or every region.");
      }
      var active =
          jdbc.sql(
                  """
                  SELECT count(*)
                  FROM catalog_region c
                  JOIN region r ON r.code = c.region_code
                  WHERE c.catalog_id = :catalog AND r.code IN (:codes) AND r.active
                  """)
              .param("catalog", catalogId)
              .param("codes", regions)
              .query(Long.class)
              .single();
      if (active < regions.size()) {
        throw ApiException.invalid("Choose active regions of the catalog.");
      }
    }
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

  /**
   * A rule as a sentence without its full stop, by the library's names: "Tow Package requires
   * Heavy-Duty Cooling (on Sport; in Europe)". A scope that covers everything is not said.
   */
  private String inWords(long catalogId, UUID ruleKey) {
    return jdbc.sql(
            """
            SELECT r.kind, f.name AS source,
                   (SELECT string_agg(tf.name, ', ' ORDER BY tf.code)
                    FROM catalog_rule_target t JOIN feature tf ON tf.id = t.feature_id
                    WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key) AS targets,
                   CASE WHEN NOT r.all_trims THEN
                       (SELECT string_agg(tr.name, ', ' ORDER BY tr.sort_order, tr.id)
                        FROM catalog_rule_trim t JOIN trim tr ON tr.id = t.trim_id
                        WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key) END AS trims,
                   CASE WHEN NOT r.all_regions THEN
                       (SELECT string_agg(rg.name, ', ' ORDER BY rg.sort_order, rg.code)
                        FROM catalog_rule_region s JOIN region rg ON rg.code = s.region_code
                        WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key)
                       END AS regions
            FROM catalog_rule r
            JOIN feature f ON f.id = r.source_feature_id
            WHERE r.catalog_id = :catalog AND r.rule_key = :key
            """)
        .param("catalog", catalogId)
        .param("key", ruleKey)
        .query(Worded.class)
        .single()
        .sentence();
  }

  /** Records a change to a rule: what the rule said before, and what it says now. */
  private void record(long catalogId, long actorId, String kind, String old, String now) {
    jdbc.sql(
            """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            VALUES (:catalog, :actor, :kind,
                    jsonb_strip_nulls(jsonb_build_object('old', :old::text, 'new', :new::text)))
            """)
        .param("catalog", catalogId)
        .param("actor", actorId)
        .param("kind", kind)
        .param("old", old)
        .param("new", now)
        .update();
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

  private record Named(long id, String name, String kind, String status) {}

  private record Worded(
      RuleKind kind, String source, String targets, String trims, String regions) {
    String sentence() {
      var scopes =
          Stream.of(trims == null ? null : "on " + trims, regions == null ? null : "in " + regions)
              .filter(Objects::nonNull)
              .collect(Collectors.joining("; "));
      var said = "%s %s %s".formatted(source, kind.words(), targets);

      return scopes.isEmpty() ? said : "%s (%s)".formatted(said, scopes);
    }
  }
}
