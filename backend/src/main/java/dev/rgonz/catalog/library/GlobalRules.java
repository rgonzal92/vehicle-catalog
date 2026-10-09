package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.ApiException;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library's global rules, which apply to every catalog. A rule relates a source feature to its
 * targets, in every region or in the ones it lists. It is added, changed, and deleted; its kind
 * never changes.
 *
 * <p>An exclusion holds both ways, so it is kept as two paired rules, A excludes B and B excludes
 * A, that share a pair key and a region scope, and are made, changed, and deleted as one.
 */
@Service
public class GlobalRules {
  private final JdbcClient jdbc;

  GlobalRules(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every rule, by the code of its source. */
  @Transactional(readOnly = true)
  List<GlobalRule> list() {
    return rules("");
  }

  /**
   * Adds a rule, and answers with what was made: the rule, or for an exclusion one rule of each
   * pair. An exclusion with several targets makes a pair for each of them.
   */
  @Transactional
  List<GlobalRule> add(RuleContent given) {
    if (given.kind() != RuleKind.EXCLUDES) {
      check(given, Set.of());
      return List.of(find(insert(given, null)));
    }
    var made = new ArrayList<GlobalRule>();
    checkWhatARuleNames(given);
    for (var target : given.targetFeatureIds()) {
      var forward = given.naming(given.sourceFeatureId(), target);
      checkNotThereAlready(forward, Set.of());
      var pairKey = UUID.randomUUID();
      long id = insert(forward, pairKey);
      insert(given.naming(target, given.sourceFeatureId()), pairKey);
      made.add(find(id));
    }
    return made;
  }

  /** Changes a rule. A paired rule takes the mirrored change to its pair with it. */
  @Transactional
  GlobalRule change(long id, RuleContent given) {
    var before = locked(id);
    if (before.kind() != given.kind()) {
      throw ApiException.invalid(
          "A rule's kind cannot be changed. Delete the rule and add another.");
    }
    var pair = before.pairKey() == null ? List.of(id) : idsOfPair(before.pairKey());
    if (given.kind() == RuleKind.EXCLUDES && given.targetFeatureIds().size() != 1) {
      throw ApiException.invalid("An Excludes rule has exactly one target.");
    }
    check(given, Set.copyOf(pair));
    rewrite(id, given);
    for (var other : pair) {
      if (other != id) {
        rewrite(other, given.naming(given.targetFeatureIds().getFirst(), given.sourceFeatureId()));
      }
    }

    return find(id);
  }

  /** Deletes a rule, and its pair with it. */
  @Transactional
  void delete(long id) {
    var deleted =
        jdbc.sql(
                """
                DELETE FROM global_rule
                WHERE id = :id
                   OR pair_key = (SELECT pair_key FROM global_rule WHERE id = :id)
                """)
            .param("id", id)
            .update();
    if (deleted == 0) {
      throw ApiException.notFound();
    }
  }

  /**
   * The Excludes rules that are not one of a whole pair: for each of them there must be exactly one
   * other rule with the same pair key, the source and the target swapped, and the same region
   * scope. None is ever found unless something has gone wrong.
   */
  @Transactional(readOnly = true)
  List<Long> brokenPairs() {
    return jdbc.sql(
            """
            SELECT r.id
            FROM global_rule r
            WHERE r.kind = 'EXCLUDES'
              AND NOT (
                (SELECT count(*) FROM global_rule_target t WHERE t.global_rule_id = r.id) = 1
                AND (SELECT count(*) FROM global_rule o WHERE o.pair_key = r.pair_key) = 2
                AND EXISTS (
                  SELECT 1
                  FROM global_rule o
                  WHERE o.pair_key = r.pair_key AND o.id <> r.id AND o.kind = 'EXCLUDES'
                    AND o.all_regions = r.all_regions
                    AND EXISTS (SELECT 1 FROM global_rule_target t
                                WHERE t.global_rule_id = r.id
                                  AND t.feature_id = o.source_feature_id)
                    AND EXISTS (SELECT 1 FROM global_rule_target t
                                WHERE t.global_rule_id = o.id
                                  AND t.feature_id = r.source_feature_id)
                    AND NOT EXISTS (
                      SELECT region_code FROM global_rule_region WHERE global_rule_id = r.id
                      EXCEPT
                      SELECT region_code FROM global_rule_region WHERE global_rule_id = o.id)
                    AND NOT EXISTS (
                      SELECT region_code FROM global_rule_region WHERE global_rule_id = o.id
                      EXCEPT
                      SELECT region_code FROM global_rule_region WHERE global_rule_id = r.id)))
            ORDER BY r.id
            """)
        .query(Long.class)
        .list();
  }

  /**
   * The rules that name the feature, as its source or as a target, each in words, and a pair once.
   * A feature that any of them names cannot be retired.
   */
  @Transactional(readOnly = true)
  public List<String> naming(long featureId) {
    return rules(
            """
            WHERE r.source_feature_id = :feature
               OR EXISTS (SELECT 1 FROM global_rule_target t
                          WHERE t.global_rule_id = r.id AND t.feature_id = :feature)
            """,
            Map.of("feature", featureId))
        .stream()
        // Of a pair, the one that starts from the feature says it for both.
        .filter(rule -> rule.pairKey() == null || rule.source().id() == featureId)
        .map(GlobalRule::inWords)
        .toList();
  }

  private GlobalRule find(long id) {
    return rules("WHERE r.id = :id", Map.of("id", id)).getFirst();
  }

  private List<GlobalRule> rules(String where) {
    return rules(where, Map.of());
  }

  /** The rules the condition lets through, each with its features and its regions by name. */
  private List<GlobalRule> rules(String where, Map<String, ?> parameters) {
    var headers =
        jdbc.sql(
                """
                SELECT r.id, r.kind, r.all_regions, r.pair_key, f.id AS source_id,
                       f.code AS source_code, f.name AS source_name
                FROM global_rule r
                JOIN feature f ON f.id = r.source_feature_id
                %s
                ORDER BY f.code, r.kind, r.id
                """
                    .formatted(where))
            .params(parameters)
            .query(Header.class)
            .list();
    if (headers.isEmpty()) {
      return List.of();
    }
    var ids = headers.stream().map(Header::id).toList();
    var targets =
        jdbc
            .sql(
                """
                SELECT t.global_rule_id AS rule_id, f.id, f.code, f.name
                FROM global_rule_target t
                JOIN feature f ON f.id = t.feature_id
                WHERE t.global_rule_id IN (:ids)
                ORDER BY f.code
                """)
            .param("ids", ids)
            .query(Target.class)
            .list()
            .stream()
            .collect(Collectors.groupingBy(Target::ruleId));
    var regions =
        jdbc
            .sql(
                """
                SELECT s.global_rule_id AS rule_id, r.code, r.name
                FROM global_rule_region s
                JOIN region r ON r.code = s.region_code
                WHERE s.global_rule_id IN (:ids)
                ORDER BY r.sort_order, r.code
                """)
            .param("ids", ids)
            .query(Scope.class)
            .list()
            .stream()
            .collect(Collectors.groupingBy(Scope::ruleId));

    return headers.stream()
        .map(
            header ->
                new GlobalRule(
                    header.id(),
                    header.kind(),
                    new FeatureName(header.sourceId(), header.sourceCode(), header.sourceName()),
                    targets.getOrDefault(header.id(), List.of()).stream()
                        .map(target -> new FeatureName(target.id(), target.code(), target.name()))
                        .toList(),
                    header.allRegions(),
                    regions.getOrDefault(header.id(), List.of()).stream()
                        .map(region -> new RegionName(region.code(), region.name()))
                        .toList(),
                    header.pairKey()))
        .toList();
  }

  /** The rule's kind and pair key. The rule is held against changes until the transaction ends. */
  private Locked locked(long id) {
    return jdbc.sql("SELECT kind, pair_key FROM global_rule WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(Locked.class)
        .optional()
        .orElseThrow(ApiException::notFound);
  }

  private List<Long> idsOfPair(UUID pairKey) {
    return jdbc.sql("SELECT id FROM global_rule WHERE pair_key = :pairKey FOR UPDATE")
        .param("pairKey", pairKey)
        .query(Long.class)
        .list();
  }

  private long insert(RuleContent given, UUID pairKey) {
    var key = new GeneratedKeyHolder();
    jdbc.sql(
            """
            INSERT INTO global_rule (kind, source_feature_id, all_regions, pair_key)
            VALUES (:kind, :source, :allRegions, :pairKey)
            """)
        .param("kind", given.kind().name())
        .param("source", given.sourceFeatureId())
        .param("allRegions", given.allRegions())
        .param("pairKey", pairKey)
        .update(key, "id");
    long id = key.getKey().longValue();
    writeTargetsAndRegions(id, given);

    return id;
  }

  /** Gives a rule the content, in place of what it had. Its kind and its pair key stay. */
  private void rewrite(long id, RuleContent given) {
    jdbc.sql(
            """
            UPDATE global_rule SET source_feature_id = :source, all_regions = :allRegions
            WHERE id = :id
            """)
        .param("source", given.sourceFeatureId())
        .param("allRegions", given.allRegions())
        .param("id", id)
        .update();
    jdbc.sql("DELETE FROM global_rule_target WHERE global_rule_id = :id").param("id", id).update();
    jdbc.sql("DELETE FROM global_rule_region WHERE global_rule_id = :id").param("id", id).update();
    writeTargetsAndRegions(id, given);
  }

  private void writeTargetsAndRegions(long id, RuleContent given) {
    for (var target : new TreeSet<>(given.targetFeatureIds())) {
      jdbc.sql("INSERT INTO global_rule_target (global_rule_id, feature_id) VALUES (:id, :target)")
          .param("id", id)
          .param("target", target)
          .update();
    }
    for (var region : given.scope()) {
      jdbc.sql("INSERT INTO global_rule_region (global_rule_id, region_code) VALUES (:id, :region)")
          .param("id", id)
          .param("region", region)
          .update();
    }
  }

  /**
   * Refuses content that breaks what holds of every rule.
   *
   * @param own the rule being changed, with its pair when it has one; empty for a new rule
   */
  private void check(RuleContent given, Set<Long> own) {
    checkWhatARuleNames(given);
    checkNotThereAlready(given, own);
  }

  /** Refuses content whose targets, features, or regions are not what a rule may name. */
  private void checkWhatARuleNames(RuleContent given) {
    var kind = given.kind();
    var targets = given.targetFeatureIds();
    if (targets.size() > RuleKind.MOST_TARGETS) {
      throw ApiException.limitExceeded(
          "A rule has at most %d targets.".formatted(RuleKind.MOST_TARGETS));
    }
    var refusal = kind.refusalOf(given.sourceFeatureId(), targets);
    if (refusal != null) {
      throw ApiException.invalid(refusal);
    }

    var named = new ArrayList<>(targets);
    named.add(given.sourceFeatureId());
    Map<Long, Named> features =
        jdbc
            .sql("SELECT id, name, kind, status FROM feature WHERE id IN (:ids)")
            .param("ids", named)
            .query(Named.class)
            .list()
            .stream()
            .collect(Collectors.toMap(Named::id, Function.identity()));
    if (features.size() < named.size()) {
      throw ApiException.invalid("Choose features from the library.");
    }
    for (var feature : features.values()) {
      if (feature.status().equals("RETIRED")) {
        throw ApiException.invalid(
            "%s is retired, and a rule cannot name it.".formatted(feature.name()));
      }
    }
    if (kind == RuleKind.INCLUDES
        && !features.get(given.sourceFeatureId()).kind().equals("PACKAGE")) {
      throw ApiException.invalid("Only a package includes other features.");
    }

    var scope = given.scope();
    if (!given.allRegions()) {
      if (scope.isEmpty()) {
        throw ApiException.invalid("Choose the regions the rule applies in, or every region.");
      }
      var active =
          jdbc.sql("SELECT code FROM region WHERE code IN (:codes) AND active")
              .param("codes", scope)
              .query(String.class)
              .list();
      if (active.size() < scope.size()) {
        throw ApiException.invalid("Choose active regions from the library.");
      }
    }
  }

  /**
   * Refuses content that says what another rule already says. Both rules of a pair are there to be
   * found, so an exclusion is found whichever way round it is given.
   */
  private void checkNotThereAlready(RuleContent given, Set<Long> own) {
    var kind = given.kind();
    var targets = given.targetFeatureIds();
    var scope = given.scope();
    var sameAsAnother =
        rules(
                "WHERE r.kind = :kind AND r.source_feature_id = :source",
                Map.of("kind", kind.name(), "source", given.sourceFeatureId()))
            .stream()
            .filter(rule -> !own.contains(rule.id()))
            .anyMatch(
                rule ->
                    rule.targets().stream()
                            .map(FeatureName::id)
                            .collect(Collectors.toSet())
                            .equals(Set.copyOf(targets))
                        && rule.allRegions() == given.allRegions()
                        && rule.regions().stream()
                            .map(RegionName::code)
                            .collect(Collectors.toSet())
                            .equals(scope));
    if (sameAsAnother) {
      throw ApiException.invalid("This rule already exists.");
    }
  }

  /**
   * What an admin gives to add a rule or to change one.
   *
   * @param regionCodes the regions the rule applies in; they count for nothing when it applies in
   *     every region
   */
  record RuleContent(
      @NotNull(message = "Choose a kind.") RuleKind kind,
      @NotNull(message = "Choose a source.") Long sourceFeatureId,
      @NotNull(message = "Choose at least one target.") List<@NotNull Long> targetFeatureIds,
      boolean allRegions,
      List<String> regionCodes) {

    /** The regions the rule lists: none when it applies in every region. */
    Set<String> scope() {
      return allRegions || regionCodes == null ? Set.of() : new TreeSet<>(regionCodes);
    }

    /** The same kind and scope, from one feature to another. */
    RuleContent naming(long source, long target) {
      return new RuleContent(kind, source, List.of(target), allRegions, regionCodes);
    }
  }

  /**
   * A rule as the API shows it, with its features and its regions by name.
   *
   * @param regions the regions the rule applies in; empty when it applies in every region
   * @param pairKey what a paired rule shares with its pair, or null for a rule that has none
   */
  record GlobalRule(
      long id,
      RuleKind kind,
      FeatureName source,
      List<FeatureName> targets,
      boolean allRegions,
      List<RegionName> regions,
      UUID pairKey) {

    /** The rule as a sentence without its full stop: "Tow Package requires Heavy-Duty Cooling". */
    String inWords() {
      var said =
          "%s %s %s"
              .formatted(
                  source.name(),
                  kind.words(),
                  targets.stream().map(FeatureName::name).collect(Collectors.joining(", ")));
      return allRegions
          ? said
          : "%s (in %s)"
              .formatted(
                  said, regions.stream().map(RegionName::name).collect(Collectors.joining(", ")));
    }
  }

  /** A feature by what identifies it and by what it is called. */
  record FeatureName(long id, String code, String name) {}

  /** A region by its code and by what it is called. */
  record RegionName(String code, String name) {}

  private record Header(
      long id,
      RuleKind kind,
      boolean allRegions,
      UUID pairKey,
      long sourceId,
      String sourceCode,
      String sourceName) {}

  private record Locked(RuleKind kind, UUID pairKey) {}

  private record Target(long ruleId, long id, String code, String name) {}

  private record Scope(long ruleId, String code, String name) {}

  private record Named(long id, String name, String kind, String status) {}
}
