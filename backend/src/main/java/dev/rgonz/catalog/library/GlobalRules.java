package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.ApiException;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
 */
@Service
public class GlobalRules {
  /** The most targets a rule has. */
  static final int MOST_TARGETS = 20;

  private final JdbcClient jdbc;

  GlobalRules(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** What a rule says of its source and its targets. */
  enum Kind {
    REQUIRES("requires", 1),
    REQUIRES_ONE_OF("requires one of", 2),
    INCLUDES("includes", 1);

    private final String words;
    private final int fewestTargets;

    Kind(String words, int fewestTargets) {
      this.words = words;
      this.fewestTargets = fewestTargets;
    }
  }

  /** Every rule, by the code of its source. */
  @Transactional(readOnly = true)
  List<GlobalRule> list() {
    return rules("");
  }

  @Transactional
  GlobalRule add(RuleContent given) {
    check(given, null);
    var key = new GeneratedKeyHolder();
    jdbc.sql(
            """
            INSERT INTO global_rule (kind, source_feature_id, all_regions)
            VALUES (:kind, :source, :allRegions)
            """)
        .param("kind", given.kind().name())
        .param("source", given.sourceFeatureId())
        .param("allRegions", given.allRegions())
        .update(key, "id");
    long id = key.getKey().longValue();
    writeTargetsAndRegions(id, given);

    return find(id);
  }

  @Transactional
  GlobalRule change(long id, RuleContent given) {
    var before = lockedKind(id);
    if (before != given.kind()) {
      throw ApiException.invalid(
          "A rule's kind cannot be changed. Delete the rule and add another.");
    }
    check(given, id);
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

    return find(id);
  }

  @Transactional
  void delete(long id) {
    if (jdbc.sql("DELETE FROM global_rule WHERE id = :id").param("id", id).update() == 0) {
      throw ApiException.notFound();
    }
  }

  /**
   * The rules that name the feature, as its source or as a target, each in words. A feature that
   * any of them names cannot be retired.
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
                SELECT r.id, r.kind, r.all_regions, f.id AS source_id, f.code AS source_code,
                       f.name AS source_name
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
                        .toList()))
        .toList();
  }

  /** The kind of the rule, which is held against changes until the transaction ends. */
  private Kind lockedKind(long id) {
    return jdbc.sql("SELECT kind FROM global_rule WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(String.class)
        .optional()
        .map(Kind::valueOf)
        .orElseThrow(ApiException::notFound);
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
   * @param id the rule being changed, or null when the content is a new rule's
   */
  private void check(RuleContent given, Long id) {
    var kind = given.kind();
    var targets = given.targetFeatureIds();
    if (targets.size() > MOST_TARGETS) {
      throw ApiException.limitExceeded("A rule has at most %d targets.".formatted(MOST_TARGETS));
    }
    if (targets.size() < kind.fewestTargets) {
      throw ApiException.invalid(
          kind == Kind.REQUIRES_ONE_OF
              ? "A Requires one of rule has at least 2 targets."
              : "Choose at least one target.");
    }
    if (new HashSet<>(targets).size() < targets.size()) {
      throw ApiException.invalid("Name each target once.");
    }
    if (targets.contains(given.sourceFeatureId())) {
      throw ApiException.invalid("A rule's source cannot be one of its targets.");
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
    if (kind == Kind.INCLUDES && !features.get(given.sourceFeatureId()).kind().equals("PACKAGE")) {
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

    var sameAsAnother =
        rules(
                "WHERE r.kind = :kind AND r.source_feature_id = :source",
                Map.of("kind", kind.name(), "source", given.sourceFeatureId()))
            .stream()
            .filter(rule -> !Objects.equals(rule.id(), id))
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
      @NotNull(message = "Choose a kind.") Kind kind,
      @NotNull(message = "Choose a source.") Long sourceFeatureId,
      @NotNull(message = "Choose at least one target.") List<@NotNull Long> targetFeatureIds,
      boolean allRegions,
      List<String> regionCodes) {

    /** The regions the rule lists: none when it applies in every region. */
    Set<String> scope() {
      return allRegions || regionCodes == null ? Set.of() : new TreeSet<>(regionCodes);
    }
  }

  /**
   * A rule as the API shows it, with its features and its regions by name.
   *
   * @param regions the regions the rule applies in; empty when it applies in every region
   */
  record GlobalRule(
      long id,
      Kind kind,
      FeatureName source,
      List<FeatureName> targets,
      boolean allRegions,
      List<RegionName> regions) {

    /** The rule as a sentence without its full stop: "Tow Package requires Heavy-Duty Cooling". */
    String inWords() {
      var said =
          "%s %s %s"
              .formatted(
                  source.name(),
                  kind.words,
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
      Kind kind,
      boolean allRegions,
      long sourceId,
      String sourceCode,
      String sourceName) {}

  private record Target(long ruleId, long id, String code, String name) {}

  private record Scope(long ruleId, String code, String name) {}

  private record Named(long id, String name, String kind, String status) {}
}
