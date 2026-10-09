package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.core.Seed;
import dev.rgonz.catalog.library.RuleKind;
import dev.rgonz.catalog.user.DemoPeople;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a database without catalogs its starting Approved versions, from {@code
 * seed/catalogs.json}. They are owned by the demo author and approved by the demo manager, and each
 * carries the labels the library has when it is loaded. Catalogs are made of library entries, so
 * this runs after the library's own seed. Where the library lacks an entry the file names, or a
 * demo account does not say who it is, no catalogs are seeded and the application starts without
 * them.
 *
 * <p>The file lists the versions of each lineage in order, and a version after the one it is based
 * on. A version names its vehicle line by code, its regions by code with the names of the trims
 * sold in each, its rules by a name each, and its feature rows by code. A feature row's cells are
 * one string: a group of characters for each region, in the regions' order and separated by spaces,
 * and in each group a character for each trim sold there, in the trims' order. S is Standard, A is
 * Available, and - is Not offered.
 */
@Component
@Order(2)
class CatalogSeed implements Seed {
  private static final Logger log = LoggerFactory.getLogger(CatalogSeed.class);

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final DemoPeople demoPeople;
  private final ApprovedChecks checks;

  CatalogSeed(JdbcClient jdbc, JsonMapper json, DemoPeople demoPeople, ApprovedChecks checks) {
    this.jdbc = jdbc;
    this.json = json;
    this.demoPeople = demoPeople;
    this.checks = checks;
  }

  /**
   * Runs once the application has started, and in each demo reset. The whole load is one
   * transaction, so a file that cannot be loaded leaves the catalogs as they were and the next run
   * tries again.
   */
  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog)").query(Boolean.class).single()) {
      return;
    }
    var catalogs = read();
    var missing = missingFromTheLibrary(catalogs);
    if (!missing.isEmpty()) {
      log.warn("No catalogs were seeded, because the library has no {}", missing);
      return;
    }
    var owner = demoPeople.record(Role.AUTHOR);
    var approver = demoPeople.record(Role.MANAGER);
    if (owner.isEmpty() || approver.isEmpty()) {
      log.warn(
          "No catalogs were seeded, because the author and manager demo accounts each need a"
              + " subject, a username, and a display name in app.demo-accounts");
      return;
    }

    for (var catalog : catalogs) {
      try {
        add(catalog, owner.get(), approver.get());
      } catch (RuntimeException refused) {
        throw new IllegalStateException(
            "seed/catalogs.json: %s could not be loaded".formatted(catalog.title()), refused);
      }
    }
    // What is seeded as Approved has not been validated by an approval, so the worker does it.
    checks.queueForEveryCurrentApproved();
  }

  private List<SeededCatalog> read() {
    try (var content = new ClassPathResource("seed/catalogs.json").getInputStream()) {
      return List.of(json.readValue(content, SeededCatalog[].class));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /** Every library entry the catalogs name that the library does not have, said by its kind. */
  private List<String> missingFromTheLibrary(List<SeededCatalog> catalogs) {
    var missing = new ArrayList<String>();
    missing.addAll(
        unknown(
            "vehicle line",
            "SELECT code FROM vehicle_line",
            catalogs.stream().map(SeededCatalog::vehicleLine)));
    missing.addAll(
        unknown(
            "region",
            "SELECT code FROM region",
            catalogs.stream()
                .flatMap(
                    catalog ->
                        Stream.concat(
                            catalog.offerings().keySet().stream(),
                            catalog.storedRules().stream()
                                .flatMap(rule -> rule.regions().stream())))));
    missing.addAll(
        unknown(
            "trim",
            "SELECT name FROM trim",
            catalogs.stream()
                .flatMap(
                    catalog ->
                        Stream.concat(
                            catalog.offerings().values().stream().flatMap(List::stream),
                            catalog.storedRules().stream()
                                .flatMap(rule -> rule.trims().stream())))));
    missing.addAll(
        unknown(
            "feature",
            "SELECT code FROM feature",
            catalogs.stream()
                .flatMap(
                    catalog ->
                        Stream.concat(
                            catalog.features().keySet().stream(),
                            catalog.storedRules().stream()
                                .flatMap(
                                    rule ->
                                        Stream.concat(
                                            Stream.of(rule.source()), rule.targets().stream()))))));

    return missing;
  }

  private List<String> unknown(String kind, String known, Stream<String> named) {
    return jdbc
        .sql(
            """
            SELECT DISTINCT named
            FROM unnest(:named::text[]) AS named
            WHERE named NOT IN (%s)
            ORDER BY named
            """
                .formatted(known))
        .param("named", named.distinct().toArray(String[]::new))
        .query(String.class)
        .list()
        .stream()
        .map(name -> kind + " " + name)
        .toList();
  }

  /** Adds one Approved version: the catalog itself, then each content table in one statement. */
  private void add(SeededCatalog seeded, long owner, long approver) {
    var catalog = addApprovedVersion(seeded, owner, approver);
    var offerings = seeded.offeringsInOrder();
    var cells = seeded.cells();

    jdbc.sql(
            """
            INSERT INTO catalog_region (catalog_id, region_code, approved_name)
            SELECT :catalog, code, name FROM region WHERE code = ANY (:regions)
            """)
        .param("catalog", catalog)
        .param("regions", seeded.offerings().keySet().toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_trim (catalog_id, trim_id, approved_name, approved_sort_order)
            SELECT :catalog, id, name, sort_order FROM trim WHERE name = ANY (:trims)
            """)
        .param("catalog", catalog)
        .param(
            "trims", offerings.stream().map(SeededOffering::trim).distinct().toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
            SELECT :catalog, t.id, o.region
            FROM unnest(:trims::text[], :regions::text[]) AS o (trim, region)
            JOIN trim t ON t.name = o.trim
            """)
        .param("catalog", catalog)
        .param("trims", offerings.stream().map(SeededOffering::trim).toArray(String[]::new))
        .param("regions", offerings.stream().map(SeededOffering::region).toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_feature
                (catalog_id, feature_id, approved_name, approved_category_code)
            SELECT :catalog, id, name, category_code FROM feature WHERE code = ANY (:features)
            """)
        .param("catalog", catalog)
        .param("features", seeded.features().keySet().toArray(String[]::new))
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, f.id, t.id, c.region, c.availability
            FROM unnest(:features::text[], :trims::text[], :regions::text[], :availabilities::text[])
                     AS c (feature, trim, region, availability)
            JOIN feature f ON f.code = c.feature
            JOIN trim t ON t.name = c.trim
            """)
        .param("catalog", catalog)
        .param("features", cells.stream().map(SeededCell::feature).toArray(String[]::new))
        .param("trims", cells.stream().map(cell -> cell.offering().trim()).toArray(String[]::new))
        .param(
            "regions", cells.stream().map(cell -> cell.offering().region()).toArray(String[]::new))
        .param(
            "availabilities",
            cells.stream().map(cell -> String.valueOf(cell.availability())).toArray(String[]::new))
        .update();
    addRules(catalog, seeded.storedRules());
  }

  /**
   * Adds the catalog's rules, each with its targets and the trims and regions it lists, in one
   * statement per table. A rule names feature rows, trims, and regions of its own catalog, which
   * the database sees to.
   */
  private void addRules(long catalog, List<StoredRule> rules) {
    jdbc.sql(
            """
            INSERT INTO catalog_rule (catalog_id, rule_key, kind, source_feature_id, all_trims,
                                      all_regions, pair_key)
            SELECT :catalog, r.key::uuid, r.kind, f.id, r.all_trims, r.all_regions,
                   r.pair_key::uuid
            FROM unnest(:keys::text[], :kinds::text[], :sources::text[], :allTrims::boolean[],
                        :allRegions::boolean[], :pairKeys::text[])
                     AS r (key, kind, source, all_trims, all_regions, pair_key)
            JOIN feature f ON f.code = r.source
            """)
        .param("catalog", catalog)
        .param("keys", rules.stream().map(rule -> rule.key().toString()).toArray(String[]::new))
        .param("kinds", rules.stream().map(rule -> rule.kind().name()).toArray(String[]::new))
        .param("sources", rules.stream().map(StoredRule::source).toArray(String[]::new))
        .param(
            "allTrims", rules.stream().map(rule -> rule.trims().isEmpty()).toArray(Boolean[]::new))
        .param(
            "allRegions",
            rules.stream().map(rule -> rule.regions().isEmpty()).toArray(Boolean[]::new))
        .param(
            "pairKeys",
            rules.stream()
                .map(rule -> rule.pairKey() == null ? null : rule.pairKey().toString())
                .toArray(String[]::new))
        .update();
    addWhatRulesList(
        catalog,
        rules,
        StoredRule::targets,
        """
        INSERT INTO catalog_rule_target (catalog_id, rule_key, feature_id)
        SELECT :catalog, l.key::uuid, f.id
        FROM unnest(:keys::text[], :named::text[]) AS l (key, named)
        JOIN feature f ON f.code = l.named
        """);
    addWhatRulesList(
        catalog,
        rules,
        StoredRule::trims,
        """
        INSERT INTO catalog_rule_trim (catalog_id, rule_key, trim_id)
        SELECT :catalog, l.key::uuid, t.id
        FROM unnest(:keys::text[], :named::text[]) AS l (key, named)
        JOIN trim t ON t.name = l.named
        """);
    addWhatRulesList(
        catalog,
        rules,
        StoredRule::regions,
        """
        INSERT INTO catalog_rule_region (catalog_id, rule_key, region_code)
        SELECT :catalog, l.key::uuid, l.named
        FROM unnest(:keys::text[], :named::text[]) AS l (key, named)
        """);
  }

  /** Runs the statement over each rule's key with each entry the rule lists, as {@code l}. */
  private void addWhatRulesList(
      long catalog,
      List<StoredRule> rules,
      Function<StoredRule, List<String>> listed,
      String statement) {
    var keys = new ArrayList<String>();
    var named = new ArrayList<String>();
    for (var rule : rules) {
      for (var entry : listed.apply(rule)) {
        keys.add(rule.key().toString());
        named.add(entry);
      }
    }
    jdbc.sql(statement)
        .param("catalog", catalog)
        .param("keys", keys.toArray(String[]::new))
        .param("named", named.toArray(String[]::new))
        .update();
  }

  /**
   * Adds the catalog itself, in its lineage, whose current Approved version is then the one with
   * the highest number. A lineage begins with its first catalog.
   */
  private long addApprovedVersion(SeededCatalog seeded, long owner, long approver) {
    var lineage =
        jdbc.sql(
                """
                INSERT INTO lineage (vehicle_line_id, model_year)
                SELECT id, :modelYear FROM vehicle_line WHERE code = :vehicleLine
                -- Changes nothing; it is here so that an existing lineage answers with its id too.
                ON CONFLICT (vehicle_line_id, model_year)
                    DO UPDATE SET model_year = EXCLUDED.model_year
                RETURNING id
                """)
            .param("vehicleLine", seeded.vehicleLine())
            .param("modelYear", seeded.modelYear())
            .query(Long.class)
            .single();
    var approvedAt = Timestamp.from(seeded.approvedAt());
    var catalog =
        jdbc.sql(
                """
                INSERT INTO catalog (lineage_id, name, owner_id, status, base_catalog_id,
                                     version_number, submitted_at, approved_by, approved_at,
                                     created_at, updated_at)
                VALUES (:lineage, :name, :owner, 'APPROVED', :base, :version, :approvedAt,
                        :approver, :approvedAt, :approvedAt, :approvedAt)
                RETURNING id
                """)
            .param("lineage", lineage)
            .param("name", seeded.name())
            .param("owner", owner)
            .param("base", baseOf(seeded))
            .param("version", seeded.version())
            .param("approvedAt", approvedAt)
            .param("approver", approver)
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            UPDATE lineage
            SET current_catalog_id =
                (SELECT id FROM catalog WHERE lineage_id = :lineage
                 ORDER BY version_number DESC LIMIT 1)
            WHERE id = :lineage
            """)
        .param("lineage", lineage)
        .update();

    return catalog;
  }

  /** The catalog a seeded version was copied from, which the file lists before it, or null. */
  private Long baseOf(SeededCatalog seeded) {
    var base = seeded.base();
    if (base == null) {
      return null;
    }

    return jdbc.sql(
            """
            SELECT c.id
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            WHERE v.code = :vehicleLine AND l.model_year = :modelYear
              AND c.version_number = :version
            """)
        .param("vehicleLine", seeded.vehicleLine())
        .param("modelYear", base.modelYear())
        .param("version", base.version())
        .query(Long.class)
        .optional()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "its base, model year %d version %d, is not listed before it"
                        .formatted(base.modelYear(), base.version())));
  }

  /**
   * An Approved version as the seed file writes it.
   *
   * @param base the version this one was copied from, in the same vehicle line, if any
   * @param offerings each region's code with the names of the trims sold there
   * @param rules the rules that belong to the version, if any
   * @param features each feature row's code with its cells, a character for each offering
   */
  record SeededCatalog(
      String vehicleLine,
      int modelYear,
      int version,
      String name,
      Base base,
      Instant approvedAt,
      Map<String, List<String>> offerings,
      List<SeededRule> rules,
      Map<String, String> features) {

    /** The version a seeded one was copied from: its model year and its number there. */
    record Base(int modelYear, int version) {}

    String title() {
      return "%s %d version %d".formatted(vehicleLine, modelYear, version);
    }

    /**
     * The rules as they are stored. An exclusion is stored as a pair: the rule as written, and the
     * same rule the other way round.
     */
    List<StoredRule> storedRules() {
      var stored = new ArrayList<StoredRule>();
      for (var rule : rules == null ? List.<SeededRule>of() : rules) {
        var paired = rule.kind() == RuleKind.EXCLUDES;
        if (paired && rule.targets().size() != 1) {
          throw new IllegalStateException(
              "the exclusion %s needs exactly one target".formatted(rule.key()));
        }
        var pairKey = paired ? keyOf(rule.key() + " pair") : null;
        stored.add(rule.stored(keyOf(rule.key()), rule.source(), rule.targets(), pairKey));
        if (paired) {
          stored.add(
              rule.stored(
                  keyOf(rule.key() + " mirrored"),
                  rule.targets().getFirst(),
                  List.of(rule.source()),
                  pairKey));
        }
      }

      return stored;
    }

    /**
     * The key that a name stands for in every version of the vehicle line, so that a rule is the
     * same rule from one version to the next.
     */
    private UUID keyOf(String name) {
      return UUID.nameUUIDFromBytes((vehicleLine + " " + name).getBytes(StandardCharsets.UTF_8));
    }

    /** The offerings in the order a feature row's characters follow: region by region. */
    List<SeededOffering> offeringsInOrder() {
      return offerings.entrySet().stream()
          .flatMap(
              region ->
                  region.getValue().stream().map(trim -> new SeededOffering(trim, region.getKey())))
          .toList();
    }

    /** The Standard and Available cells that the feature rows' characters spell out. */
    List<SeededCell> cells() {
      var inOrder = offeringsInOrder();
      var cells = new ArrayList<SeededCell>();

      features.forEach(
          (feature, row) -> {
            var characters = row.replace(" ", "");
            if (characters.length() != inOrder.size() || !characters.matches("[SA-]*")) {
              throw new IllegalStateException(
                  "%s needs one S, A, or - for each of the %d offerings, but has \"%s\""
                      .formatted(feature, inOrder.size(), row));
            }
            for (int position = 0; position < inOrder.size(); position++) {
              if (characters.charAt(position) != '-') {
                cells.add(
                    new SeededCell(feature, inOrder.get(position), characters.charAt(position)));
              }
            }
          });

      return cells;
    }
  }

  /**
   * A rule of a seeded version, as the seed file writes it.
   *
   * @param key a name for the rule, which the versions of a vehicle line share for the same rule
   * @param source the source feature's code
   * @param targets the target features' codes; exactly one for an exclusion
   * @param trims the names of the trims the rule covers, or null when it covers every trim
   * @param regions the codes of the regions the rule covers, or null when it covers every region
   */
  record SeededRule(
      String key,
      RuleKind kind,
      String source,
      List<String> targets,
      List<String> trims,
      List<String> regions) {

    StoredRule stored(UUID ruleKey, String from, List<String> to, UUID pairKey) {
      return new StoredRule(
          ruleKey,
          kind,
          from,
          to,
          trims == null ? List.of() : trims,
          regions == null ? List.of() : regions,
          pairKey);
    }
  }

  /**
   * A rule as it is stored, by the names the seed file uses. A scope that lists nothing covers
   * everything.
   */
  record StoredRule(
      UUID key,
      RuleKind kind,
      String source,
      List<String> targets,
      List<String> trims,
      List<String> regions,
      UUID pairKey) {}

  /** One trim sold in one region, by the names the seed file uses. */
  record SeededOffering(String trim, String region) {}

  /** A Standard (S) or Available (A) cell of a seeded feature row. */
  record SeededCell(String feature, SeededOffering offering, char availability) {}
}
