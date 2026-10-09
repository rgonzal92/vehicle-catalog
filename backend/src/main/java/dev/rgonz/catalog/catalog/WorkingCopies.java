package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.reference.FixedLists;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates working copies and lists a person's own. A new working copy starts from its lineage's
 * current Approved version, or from the nearest earlier model year's when its own lineage has none
 * (carryover), or empty.
 */
@Service
class WorkingCopies {
  /** The most characters a catalog's name has. */
  static final int LONGEST_NAME = 80;

  /** What a catalog without a name is refused with, when it is created and when it is renamed. */
  static final String NAME_MISSING = "Enter a name.";

  /** What a name that is too long is refused with. */
  static final String NAME_TOO_LONG = "Keep the name to " + LONGEST_NAME + " characters or fewer.";

  /** The most working copies one person can own at a time. */
  private static final int MOST_PER_OWNER = 20;

  /** A working copy as its owner's list shows it; a statement adds which ones it reads. */
  private static final String SUMMARY =
      """
      SELECT c.id, c.name, v.name AS vehicle_line, l.model_year, c.status, c.revision,
             c.updated_at
      FROM catalog c
      JOIN lineage l ON l.id = c.lineage_id
      JOIN vehicle_line v ON v.id = l.vehicle_line_id
      """;

  private final JdbcClient jdbc;
  private final FixedLists fixedLists;
  private final Timer copyTime;

  WorkingCopies(JdbcClient jdbc, FixedLists fixedLists, MeterRegistry metrics) {
    this.jdbc = jdbc;
    this.fixedLists = fixedLists;
    this.copyTime =
        Timer.builder("catalog.copy")
            .description("How long copying a catalog's contents into a new working copy takes")
            .register(metrics);
  }

  /** What a new working copy for the vehicle line and model year would start from. */
  @Transactional(readOnly = true)
  StartPoint startPoint(long vehicleLineId, int modelYear) {
    requireOpenFor(vehicleLineId, modelYear);

    return startPointOf(vehicleLineId, modelYear);
  }

  /** Creates a working copy in status Draft for its owner and copies its starting point into it. */
  @Transactional
  WorkingCopy create(long ownerId, NewWorkingCopy given) {
    requireOpenFor(given.vehicleLineId(), given.modelYear());
    // One owner's creations wait for each other here, so two at once cannot both be the 20th.
    jdbc.sql("SELECT id FROM app_user WHERE id = :owner FOR UPDATE")
        .param("owner", ownerId)
        .query(Long.class)
        .single();
    var owned =
        jdbc.sql("SELECT count(*) FROM catalog WHERE owner_id = :owner AND status <> 'APPROVED'")
            .param("owner", ownerId)
            .query(Long.class)
            .single();
    if (owned >= MOST_PER_OWNER) {
      throw ApiException.limitExceeded(
          "You have %d working copies, which is the most one person can have."
              .formatted(MOST_PER_OWNER));
    }

    var start = startPointOf(given.vehicleLineId(), given.modelYear());
    var id = insert(ownerId, given, start);
    if (start.catalogId() != null) {
      copyTime.record(
          () ->
              jdbc.sql("SELECT copy_catalog(:source, :target)")
                  .param("source", start.catalogId())
                  .param("target", id)
                  // The function answers nothing, so there is nothing to read from its one row.
                  .query(row -> {}));
    }

    return jdbc.sql(SUMMARY + " WHERE c.id = :id")
        .param("id", id)
        .query(WorkingCopy.class)
        .single();
  }

  /** The person's working copies, the one changed last first. */
  List<WorkingCopy> ownedBy(long ownerId) {
    return jdbc.sql(
            SUMMARY
                + """
                WHERE c.owner_id = :owner AND c.status <> 'APPROVED'
                ORDER BY c.updated_at DESC, c.id DESC
                """)
        .param("owner", ownerId)
        .query(WorkingCopy.class)
        .list();
  }

  /**
   * Every catalog that is waiting for review, the one submitted first at the top, with whether it
   * is the viewer's own.
   */
  List<Submitted> submitted(long viewerId) {
    return jdbc.sql(
            """
            SELECT c.id, c.name, v.name AS vehicle_line, l.model_year, o.display_name AS owner,
                   c.submitted_at, c.submit_note AS note, c.owner_id = :viewer AS own
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            JOIN app_user o ON o.id = c.owner_id
            WHERE c.status = 'SUBMITTED'
            ORDER BY c.submitted_at, c.id
            """)
        .param("viewer", viewerId)
        .query(Submitted.class)
        .list();
  }

  /**
   * The refusal of a name another working copy of the owner's has. The database keeps an owner's
   * working copy names apart whatever their case, and says so when a name is written.
   */
  static ApiException nameTaken() {
    return ApiException.conflict(
        "NAME_TAKEN", "Another of your working copies already has this name.");
  }

  /** A catalog can be made only for a configured model year of an active vehicle line. */
  private void requireOpenFor(long vehicleLineId, int modelYear) {
    if (!fixedLists.hasModelYear(modelYear)) {
      throw ApiException.invalid("Choose one of the model years.");
    }
    var active =
        jdbc.sql("SELECT active FROM vehicle_line WHERE id = :id")
            .param("id", vehicleLineId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(() -> ApiException.invalid("Choose one of the vehicle lines."));
    if (!active) {
      throw ApiException.conflict(
          "VEHICLE_LINE_INACTIVE",
          "This vehicle line is deactivated, so it takes no new catalogs.");
    }
  }

  /**
   * The current Approved version of the nearest model year up to the given one. When that is the
   * given year itself, it is the lineage's own; an earlier year's is a carryover.
   */
  private StartPoint startPointOf(long vehicleLineId, int modelYear) {
    return jdbc.sql(
            """
            SELECT c.id AS catalog_id, l.model_year, c.version_number
            FROM lineage l
            JOIN catalog c ON c.id = l.current_catalog_id AND c.status = 'APPROVED'
            WHERE l.vehicle_line_id = :line AND l.model_year <= :year
            ORDER BY l.model_year DESC
            LIMIT 1
            """)
        .param("line", vehicleLineId)
        .param("year", modelYear)
        .query(
            (row, number) ->
                new StartPoint(
                    row.getInt("model_year") == modelYear
                        ? StartPoint.Kind.COPY
                        : StartPoint.Kind.CARRYOVER,
                    row.getLong("catalog_id"),
                    row.getInt("model_year"),
                    row.getInt("version_number")))
        .optional()
        .orElse(new StartPoint(StartPoint.Kind.EMPTY, null, null, null));
  }

  /** Inserts the catalog into its lineage, which is created with its first catalog. */
  private long insert(long ownerId, NewWorkingCopy given, StartPoint start) {
    var lineage =
        jdbc.sql(
                """
                INSERT INTO lineage (vehicle_line_id, model_year)
                VALUES (:line, :year)
                -- Changes nothing; it is here so that a lineage that exists answers with its id.
                ON CONFLICT (vehicle_line_id, model_year)
                    DO UPDATE SET model_year = lineage.model_year
                RETURNING id
                """)
            .param("line", given.vehicleLineId())
            .param("year", given.modelYear())
            .query(Long.class)
            .single();

    try {
      return jdbc.sql(
              """
              INSERT INTO catalog (lineage_id, name, owner_id, base_catalog_id)
              VALUES (:lineage, :name, :owner, :base)
              RETURNING id
              """)
          .param("lineage", lineage)
          .param("name", given.name())
          .param("owner", ownerId)
          .param("base", start.catalogId())
          .query(Long.class)
          .single();
    } catch (DuplicateKeyException taken) {
      throw nameTaken();
    }
  }

  /**
   * What a new working copy starts from.
   *
   * @param catalogId the Approved version it copies, which becomes its base; null when it starts
   *     empty
   * @param modelYear that version's model year, which for a carryover is an earlier one
   */
  record StartPoint(Kind kind, Long catalogId, Integer modelYear, Integer versionNumber) {
    enum Kind {
      COPY,
      CARRYOVER,
      EMPTY
    }
  }

  /** What a person gives to create a working copy. */
  record NewWorkingCopy(
      @NotBlank(message = NAME_MISSING) @Size(max = LONGEST_NAME, message = NAME_TOO_LONG)
          String name,
      @NotNull(message = "Choose a vehicle line.") Long vehicleLineId,
      @NotNull(message = "Choose a model year.") Integer modelYear) {
    NewWorkingCopy {
      name = name == null ? null : name.strip();
    }
  }

  /**
   * A Submitted catalog as the review queue lists it.
   *
   * @param note what its owner said when they submitted it, if anything
   * @param own whether the viewer owns it, who then cannot review it
   */
  record Submitted(
      long id,
      String name,
      String vehicleLine,
      int modelYear,
      String owner,
      Instant submittedAt,
      String note,
      boolean own) {}

  /**
   * A working copy as its owner's list shows it.
   *
   * @param revision what an edit made from the list, such as deleting it, names
   */
  record WorkingCopy(
      long id,
      String name,
      String vehicleLine,
      int modelYear,
      Status status,
      long revision,
      Instant updatedAt) {}
}
