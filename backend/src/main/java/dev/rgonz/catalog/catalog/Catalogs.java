package dev.rgonz.catalog.catalog;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.rgonz.catalog.catalog.ApprovedContents.Contents;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.library.RuleKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads lineages, their Approved versions, and whole catalogs. */
@Repository
class Catalogs {
  /**
   * The condition on a catalog {@code c} under which the reader may open it, with the parameters
   * {@link #readBy} binds.
   */
  /**
   * The condition under which a catalog {@code c} of the lineage {@code l} is stale: it is a
   * working copy whose base is no longer its lineage's current Approved.
   */
  private static final String STALE =
      "c.status <> 'APPROVED' AND l.current_catalog_id IS NOT NULL"
          + " AND l.current_catalog_id IS DISTINCT FROM c.base_catalog_id";

  private static final String OPENS_FOR_READER =
      "(:everyone OR c.status = 'APPROVED' OR c.owner_id = :viewer"
          + " OR (c.status = 'SUBMITTED' AND :reviews))";

  private final JdbcClient jdbc;
  private final ApprovedContents approvedContents;
  private final ObservationRegistry observations;
  private final Timer validateTime;

  Catalogs(
      JdbcClient jdbc,
      ApprovedContents approvedContents,
      ObservationRegistry observations,
      MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.approvedContents = approvedContents;
    this.observations = observations;
    this.validateTime =
        Timer.builder("catalog.validate")
            .description("How long reading a catalog and working out its issues takes")
            .register(metrics);
  }

  /**
   * Every lineage that has an Approved version, with its current one and what validation last found
   * in it. A catalog that has not been checked yet counts as one without an Error.
   */
  List<LineageSummary> lineages() {
    return jdbc.sql(
            """
            SELECT l.id, v.name AS vehicle_line, l.model_year, c.id AS catalog_id,
                   c.version_number, a.display_name AS approved_by, c.approved_at,
                   COALESCE(k.status = 'NEEDS_REVISION', false) AS needs_revision,
                   COALESCE(k.error_count, 0) AS error_count
            FROM lineage l
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            JOIN catalog c ON c.id = l.current_catalog_id AND c.status = 'APPROVED'
            JOIN app_user a ON a.id = c.approved_by
            LEFT JOIN approved_check k ON k.catalog_id = c.id
            ORDER BY v.name, l.model_year
            """)
        .query(LineageSummary.class)
        .list();
  }

  boolean hasLineage(long id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lineage WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  /** The Approved versions of a lineage, newest first. */
  List<VersionSummary> versions(long lineageId) {
    return jdbc.sql(
            """
            SELECT c.id AS catalog_id, c.version_number, c.name, a.display_name AS approved_by,
                   c.approved_at
            FROM catalog c
            JOIN app_user a ON a.id = c.approved_by
            WHERE c.lineage_id = :lineageId AND c.status = 'APPROVED'
            ORDER BY c.version_number DESC
            """)
        .param("lineageId", lineageId)
        .query(VersionSummary.class)
        .list();
  }

  /**
   * Whether the reader may open the catalog. Everyone opens an Approved version. A working copy is
   * its owner's, and once it is Submitted a reviewer's too; to any other person it is as if it did
   * not exist. The system opens every catalog.
   */
  boolean opensFor(long id, Reader reader) {
    return readBy(
            jdbc.sql(
                    "SELECT EXISTS (SELECT 1 FROM catalog c WHERE c.id = :id AND %s)"
                        .formatted(OPENS_FOR_READER))
                .param("id", id),
            reader)
        .query(Boolean.class)
        .single();
  }

  /** Whether the catalog is stale, as its read would say now. */
  boolean stale(long id) {
    return jdbc.sql(
            """
            SELECT %s
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            WHERE c.id = :id
            """
                .formatted(STALE))
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  /** Binds who reads to the parameters of {@link #OPENS_FOR_READER}. */
  private static JdbcClient.StatementSpec readBy(
      JdbcClient.StatementSpec statement, Reader reader) {
    return switch (reader) {
      case Reader.Person(var id, var reviews) ->
          statement.param("viewer", id).param("reviews", reviews).param("everyone", false);
      // No person has the id 0, so the system owns no catalog.
      case Reader.System system ->
          statement.param("viewer", 0L).param("reviews", false).param("everyone", true);
    };
  }

  /**
   * A step inside a request or a job: it leaves a span of its own in their trace, named after the
   * step, and no metric, since no step's name is in the lists of {@code Telemetry}. The catalog's
   * id goes on the span alone, as a value that no metric is told apart by.
   */
  static Observation step(ObservationRegistry observations, String name, long catalogId) {
    return Observation.createNotStarted(name, observations)
        .highCardinalityKeyValue("catalog.id", String.valueOf(catalogId));
  }

  /**
   * A catalog with its contents and its issues, if the reader may open it: one query for the
   * catalog itself, one for each content table, and one for its rules. The queries share one view
   * of the database, so the contents always belong to the revision. Reading the catalog, reading
   * what validation needs of the library, and working out the issues are three steps of the trace.
   *
   * <p>The time is taken from before the catalog is read until its issues are worked out, and only
   * of a catalog that is found.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Optional<CatalogView> shown(long id, Reader reader) {
    var started = System.nanoTime();
    var found =
        read(id, reader)
            .map(
                read -> {
                  var library = library(id);
                  return new CatalogView(
                      read,
                      step(observations, "catalog.issues", id)
                          .observe(() -> Validation.issues(read.snapshot(), library)));
                });
    if (found.isPresent()) {
      validateTime.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    return found;
  }

  /**
   * A catalog with its contents, if the reader may open it, for what has no use for its issues:
   * they are not worked out.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  Optional<Read> contents(long id, Reader reader) {
    return read(id, reader);
  }

  /** The catalog's own row and its contents, from the cache or the database: one step. */
  private Optional<Read> read(long id, Reader reader) {
    return step(observations, "catalog.read", id).observe(() -> row(id, reader));
  }

  private Optional<Read> row(long id, Reader reader) {
    return readBy(
            jdbc.sql(
                    """
            SELECT c.id, c.lineage_id, c.status, c.revision, c.name, c.version_number,
                   v.id AS vehicle_line_id, v.name AS vehicle_line, l.model_year,
                   a.display_name AS approved_by, c.approved_at, c.owner_id = :viewer AS owned,
                   o.display_name AS owner,
                   v.active AS vehicle_line_active, c.submit_note, c.submitted_at,
                   %s AS stale,
                   cur.id AS current_catalog_id, cur.version_number AS current_version_number,
                   d.decision, d.reviewer AS decided_by, d.comment AS decision_comment,
                   d.created_at AS decided_at,
                   b.id AS base_catalog_id,
                   bl.model_year AS base_model_year, b.version_number AS base_version_number
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            JOIN app_user o ON o.id = c.owner_id
            LEFT JOIN app_user a ON a.id = c.approved_by
            LEFT JOIN catalog b ON b.id = c.base_catalog_id
            LEFT JOIN lineage bl ON bl.id = b.lineage_id
            LEFT JOIN catalog cur ON cur.id = l.current_catalog_id
            -- The decision that sent a Draft back to its owner, until it is submitted again.
            LEFT JOIN LATERAL (
                SELECT r.decision, ra.display_name AS reviewer, r.comment, r.created_at
                FROM catalog_review r
                LEFT JOIN app_user ra ON ra.id = r.reviewer_id
                WHERE r.catalog_id = c.id AND c.status = 'DRAFT'
                  AND r.created_at >= c.submitted_at
                ORDER BY r.id DESC
                LIMIT 1
            ) AS d ON true
            WHERE c.id = :id AND %s
            """
                        .formatted(STALE, OPENS_FOR_READER))
                .param("id", id),
            reader)
        .query(Header.class)
        .optional()
        .map(
            header ->
                new Read(
                    header.name(),
                    header.versionNumber(),
                    header.vehicleLineId(),
                    header.vehicleLine(),
                    header.modelYear(),
                    header.approvedBy(),
                    header.approvedAt(),
                    header.owned(),
                    header.owner(),
                    header.vehicleLineActive(),
                    header.submitNote(),
                    header.submittedAt(),
                    header.stale(),
                    header.currentCatalogId() == null
                        ? null
                        : new Current(header.currentCatalogId(), header.currentVersionNumber()),
                    header.decision() == null
                        ? null
                        : new Decision(
                            header.decision(),
                            header.decidedBy(),
                            header.decisionComment(),
                            header.decidedAt()),
                    header.baseCatalogId() == null
                        ? null
                        : new Base(
                            header.baseCatalogId(),
                            header.baseModelYear(),
                            header.baseVersionNumber()),
                    snapshot(
                        header.id(),
                        header.lineageId(),
                        header.status(),
                        header.revision(),
                        true)));
  }

  /**
   * An Approved version's contents by the labels the library has today, whatever labels it was
   * approved with, for what sets it beside a working copy: a working copy always shows today's.
   */
  CatalogSnapshot asLabelledToday(long id) {
    var approved =
        jdbc.sql("SELECT lineage_id, revision FROM catalog WHERE id = :id AND status = 'APPROVED'")
            .param("id", id)
            .query(Approved.class)
            .single();

    return snapshot(id, approved.lineageId(), Status.APPROVED, approved.revision(), false);
  }

  /**
   * Gives the cache an Approved version's contents, as they read with the labels of its approval,
   * so that the first read of the version finds them there. A catalog that is no Approved version
   * is left out of the cache, as it always is.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  void keepInTheCache(long id) {
    boolean approved =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog WHERE id = :id AND status = 'APPROVED')")
            .param("id", id)
            .query(Boolean.class)
            .single();
    if (approved) {
      approvedContents.keep(id, contentsOf(id, true, true));
    }
  }

  /** What places an Approved version: its lineage, and the revision it was approved at. */
  private record Approved(long lineageId, long revision) {}

  /**
   * What validation needs of the library as it is today: which of the catalog's features are
   * retired, which of its trims and regions are inactive, and the global rules with the names of
   * the features they name. It is read with three queries, however many rules there are, as one
   * step of the trace.
   */
  Validation.Library library(long catalogId) {
    return step(observations, "catalog.library", catalogId).observe(() -> libraryOf(catalogId));
  }

  private Validation.Library libraryOf(long catalogId) {
    var out =
        jdbc.sql(
                """
                SELECT 'FEATURE' AS kind, f.id::text AS key
                FROM catalog_feature c
                JOIN feature f ON f.id = c.feature_id
                WHERE c.catalog_id = :id AND f.status = 'RETIRED'
                UNION ALL
                SELECT 'TRIM', t.id::text
                FROM catalog_trim c
                JOIN trim t ON t.id = c.trim_id
                WHERE c.catalog_id = :id AND NOT t.active
                UNION ALL
                SELECT 'REGION', r.code
                FROM catalog_region c
                JOIN region r ON r.code = c.region_code
                WHERE c.catalog_id = :id AND NOT r.active
                """)
            .param("id", catalogId)
            .query(OutOfUse.class)
            .list();

    var rules =
        jdbc.sql(
                """
                SELECT r.id, r.kind, r.source_feature_id, r.all_regions, r.pair_key,
                       ARRAY(SELECT t.feature_id
                             FROM global_rule_target t JOIN feature f ON f.id = t.feature_id
                             WHERE t.global_rule_id = r.id ORDER BY f.code) AS targets,
                       ARRAY(SELECT s.region_code
                             FROM global_rule_region s JOIN region g ON g.code = s.region_code
                             WHERE s.global_rule_id = r.id
                             ORDER BY g.sort_order, g.code) AS regions
                FROM global_rule r
                ORDER BY r.id
                """)
            .query(
                (row, _) ->
                    new Rule(
                        Rule.Origin.GLOBAL,
                        String.valueOf(row.getLong("id")),
                        RuleKind.valueOf(row.getString("kind")),
                        row.getLong("source_feature_id"),
                        List.of((Long[]) row.getArray("targets").getArray()),
                        true,
                        Set.of(),
                        row.getBoolean("all_regions"),
                        new LinkedHashSet<>(List.of((String[]) row.getArray("regions").getArray())),
                        row.getString("pair_key")))
            .list();
    var names =
        jdbc
            .sql(
                """
                SELECT f.id, f.name
                FROM feature f
                WHERE f.id IN (SELECT source_feature_id FROM global_rule
                               UNION
                               SELECT feature_id FROM global_rule_target)
                """)
            .query(Named.class)
            .list()
            .stream()
            .collect(Collectors.toMap(Named::id, Named::name));

    return new Validation.Library(
        keys(out, "FEATURE").map(Long::valueOf).collect(Collectors.toSet()),
        keys(out, "TRIM").map(Long::valueOf).collect(Collectors.toSet()),
        keys(out, "REGION").collect(Collectors.toSet()),
        rules,
        names);
  }

  /** A feature by its identity and what it is called. */
  private record Named(long id, String name) {}

  private static Stream<String> keys(List<OutOfUse> out, String kind) {
    return out.stream().filter(entry -> entry.kind().equals(kind)).map(OutOfUse::key);
  }

  /** A retired feature or an inactive trim or region of a catalog, by what identifies it. */
  private record OutOfUse(String kind, String key) {}

  /**
   * A catalog's contents. Those of an Approved version as it was approved never change, so they are
   * taken from the cache when it holds them. A working copy's are always read from the database,
   * and so are an Approved version's by the labels of today.
   *
   * @param frozen whether an Approved version shows the labels and the order it was approved with,
   *     and not the library's of today
   */
  private CatalogSnapshot snapshot(
      long id, long lineageId, Status status, long revision, boolean frozen) {
    boolean approved = status == Status.APPROVED;
    var held =
        frozen && approved
            ? approvedContents.of(id, () -> contentsOf(id, true, true))
            : contentsOf(id, frozen, approved);

    return new CatalogSnapshot(
        id,
        lineageId,
        status,
        revision,
        held.trims(),
        held.regions(),
        held.offerings(),
        held.featureRows(),
        held.cells(),
        held.rules());
  }

  /**
   * A catalog's contents as the database holds them, read with one query for each table. The
   * offerings are read in the order of their key, and so are the cells of an Approved version, so
   * that a version reads the same every time, from the database as from the cache: left to itself,
   * PostgreSQL gives rows in one order when it reads along an index and in another when it reads
   * the table. A working copy's cells are read as PostgreSQL gives them: every edit reads them
   * again, and sorting 24,000 of them takes PostgreSQL 11 ms where reading them takes 3. Nothing
   * goes by their order: an update from Approved lists its conflicts in an order of its own, by the
   * feature's code and then by the offering as the matrix has them, and everything else looks the
   * cells up.
   *
   * @param approved whether the catalog is an Approved version
   */
  private Contents contentsOf(long id, boolean frozen, boolean approved) {
    var trims =
        content(
            """
            SELECT c.trim_id AS id,
                   COALESCE(CASE WHEN :frozen THEN c.approved_name END, t.name) AS name,
                   COALESCE(CASE WHEN :frozen THEN c.approved_sort_order END, t.sort_order)
                       AS sort_order
            FROM catalog_trim c
            JOIN trim t ON t.id = c.trim_id
            WHERE c.catalog_id = :id
            ORDER BY sort_order, id
            """,
            id,
            frozen,
            Trim.class);
    var regions =
        content(
            """
            SELECT c.region_code AS code,
                   COALESCE(CASE WHEN :frozen THEN c.approved_name END, r.name) AS name
            FROM catalog_region c
            JOIN region r ON r.code = c.region_code
            WHERE c.catalog_id = :id
            ORDER BY COALESCE(CASE WHEN :frozen THEN c.approved_sort_order END, r.sort_order),
                     r.code
            """,
            id,
            frozen,
            Region.class);
    var offerings =
        content(
            """
            SELECT trim_id, region_code
            FROM catalog_trim_region
            WHERE catalog_id = :id
            ORDER BY trim_id, region_code
            """,
            id,
            frozen,
            Offering.class);
    var featureRows =
        content(
            """
            SELECT c.feature_id AS id, f.code, f.kind,
                   COALESCE(CASE WHEN :frozen THEN c.approved_name END, f.name) AS name,
                   COALESCE(CASE WHEN :frozen THEN c.approved_category_code END, f.category_code)
                       AS category_code
            FROM catalog_feature c
            JOIN feature f ON f.id = c.feature_id
            WHERE c.catalog_id = :id
            ORDER BY f.code
            """,
            id,
            frozen,
            FeatureRow.class);
    // Mapped by hand: by the record's class, 24,000 cells take a third of a second to map.
    var cells =
        jdbc.sql(
                """
                SELECT feature_id, trim_id, region_code, availability
                FROM catalog_cell
                WHERE catalog_id = :id
                %s
                """
                    .formatted(approved ? "ORDER BY feature_id, trim_id, region_code" : ""))
            .param("id", id)
            .query(
                (row, number) ->
                    new Cell(
                        row.getLong("feature_id"),
                        row.getLong("trim_id"),
                        row.getString("region_code"),
                        Availability.valueOf(row.getString("availability"))))
            .list();

    return new Contents(trims, regions, offerings, featureRows, cells, rules(id, frozen));
  }

  /**
   * The catalog's own rules, read with one query: each with its targets by their codes, and with
   * the trims and regions it lists in the order the catalog has them in.
   *
   * @param frozen whether that is the order an Approved version was approved with, and not the
   *     library's of today
   */
  private List<Rule> rules(long catalogId, boolean frozen) {
    return jdbc.sql(
            """
            SELECT r.rule_key, r.kind, r.source_feature_id, r.all_trims, r.all_regions, r.pair_key,
                   ARRAY(SELECT t.feature_id
                         FROM catalog_rule_target t
                         JOIN feature tf ON tf.id = t.feature_id
                         WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                         ORDER BY tf.code) AS targets,
                   ARRAY(SELECT t.trim_id
                         FROM catalog_rule_trim t
                         JOIN catalog_trim ct
                             ON ct.catalog_id = t.catalog_id AND ct.trim_id = t.trim_id
                         JOIN trim tr ON tr.id = t.trim_id
                         WHERE t.catalog_id = r.catalog_id AND t.rule_key = r.rule_key
                         ORDER BY COALESCE(CASE WHEN :frozen THEN ct.approved_sort_order END,
                                           tr.sort_order),
                                  tr.id) AS trims,
                   ARRAY(SELECT s.region_code
                         FROM catalog_rule_region s
                         JOIN catalog_region cr
                             ON cr.catalog_id = s.catalog_id AND cr.region_code = s.region_code
                         JOIN region rg ON rg.code = s.region_code
                         WHERE s.catalog_id = r.catalog_id AND s.rule_key = r.rule_key
                         ORDER BY COALESCE(CASE WHEN :frozen THEN cr.approved_sort_order END,
                                           rg.sort_order),
                                  rg.code) AS regions
            FROM catalog_rule r
            JOIN feature f ON f.id = r.source_feature_id
            WHERE r.catalog_id = :id
            ORDER BY f.code, r.kind, r.rule_key
            """)
        .param("id", catalogId)
        .param("frozen", frozen)
        .query(
            (row, _) ->
                new Rule(
                    Rule.Origin.CATALOG,
                    row.getString("rule_key"),
                    RuleKind.valueOf(row.getString("kind")),
                    row.getLong("source_feature_id"),
                    List.of((Long[]) row.getArray("targets").getArray()),
                    row.getBoolean("all_trims"),
                    new LinkedHashSet<>(List.of((Long[]) row.getArray("trims").getArray())),
                    row.getBoolean("all_regions"),
                    new LinkedHashSet<>(List.of((String[]) row.getArray("regions").getArray())),
                    row.getString("pair_key")))
        .list();
  }

  private <T> List<T> content(String sql, long id, boolean frozen, Class<T> type) {
    return jdbc.sql(sql).param("id", id).param("frozen", frozen).query(type).list();
  }

  /**
   * A lineage and its current Approved version, as the dashboard lists them.
   *
   * @param needsRevision whether validation last found an Error in it: a change of the library has
   *     broken it since it was approved
   * @param errorCount how many Errors that was
   */
  record LineageSummary(
      long id,
      String vehicleLine,
      int modelYear,
      long catalogId,
      int versionNumber,
      String approvedBy,
      Instant approvedAt,
      boolean needsRevision,
      int errorCount) {}

  /** One Approved version in a lineage's list of versions. */
  record VersionSummary(
      long catalogId, int versionNumber, String name, String approvedBy, Instant approvedAt) {}

  /**
   * Who reads a catalog: a person, or the system, as a job does. A person opens an Approved
   * version, their own catalogs, and a Submitted one when they review catalogs; the system opens
   * every catalog.
   */
  sealed interface Reader {
    /** The system, which opens every catalog. */
    Reader SYSTEM = new System();

    /**
     * The signed-in person as a reader, who reviews catalogs when they are a manager or an admin.
     */
    static Person of(long id, Authentication signedIn, RoleHierarchy roles) {
      return new Person(id, reviews(signedIn, roles));
    }

    /** Whether the signed-in person reviews catalogs, as a manager and an admin do. */
    static boolean reviews(Authentication signedIn, RoleHierarchy roles) {
      return Role.heldBy(signedIn.getAuthorities(), roles).contains(Role.MANAGER);
    }

    /**
     * A person on record.
     *
     * @param reviews whether they review catalogs, as a manager and an admin do
     */
    record Person(long id, boolean reviews) implements Reader {}

    /** The system: {@link #SYSTEM} names it. */
    record System() implements Reader {}
  }

  /**
   * A catalog as it is read, without its issues: what describes it, and its contents.
   *
   * @param owned whether the reader owns it, which lets them edit it while it is in status Draft
   * @param owner the name of the person who owns it
   * @param vehicleLineActive whether its vehicle line is active; a catalog of an inactive line
   *     cannot be submitted or approved
   * @param submitNote what its owner said when they last submitted it, if anything
   * @param submittedAt when it was last submitted, or null when it never was
   * @param stale whether it is a working copy whose base is no longer its lineage's current
   *     Approved, which cannot be submitted or approved until it has been updated from it
   * @param current its lineage's current Approved, or null while the lineage has none
   * @param decision the rejection or the return that sent it back to its owner, while it stands:
   *     until it is submitted again
   * @param base the Approved version it was copied from, or null when it started empty
   */
  record Read(
      String name,
      Integer versionNumber,
      long vehicleLineId,
      String vehicleLine,
      int modelYear,
      String approvedBy,
      Instant approvedAt,
      boolean owned,
      String owner,
      boolean vehicleLineActive,
      String submitNote,
      Instant submittedAt,
      boolean stale,
      Current current,
      Decision decision,
      Base base,
      CatalogSnapshot snapshot) {}

  /**
   * A catalog as the API shows it: what describes it and its contents, read, and its issues, all
   * side by side in its JSON.
   *
   * @param issues what validation finds in the contents against the library as it is today, Errors
   *     before Warnings
   */
  record CatalogView(@JsonUnwrapped Read read, List<Issue> issues) {}

  /** The current Approved of a catalog's lineage: the catalog that is it, and its number. */
  record Current(long catalogId, int versionNumber) {}

  /**
   * A decision that sent a catalog back to its owner.
   *
   * @param decision {@code REJECTED} or {@code RETURNED_STALE}
   * @param reviewer the name of who rejected it; nobody for a return, which is the system's
   * @param comment why it was rejected; nothing for a return
   */
  record Decision(String decision, String reviewer, String comment, Instant at) {}

  /** A catalog's base. After a carryover its model year is an earlier one than the catalog's. */
  record Base(long catalogId, int modelYear, int versionNumber) {}

  /** The catalog's own row, joined with what names its lineage and its approver. */
  private record Header(
      long id,
      long lineageId,
      Status status,
      long revision,
      String name,
      Integer versionNumber,
      long vehicleLineId,
      String vehicleLine,
      int modelYear,
      String approvedBy,
      Instant approvedAt,
      boolean owned,
      String owner,
      boolean vehicleLineActive,
      String submitNote,
      Instant submittedAt,
      boolean stale,
      Long currentCatalogId,
      Integer currentVersionNumber,
      String decision,
      String decidedBy,
      String decisionComment,
      Instant decidedAt,
      Long baseCatalogId,
      Integer baseModelYear,
      Integer baseVersionNumber) {}
}
