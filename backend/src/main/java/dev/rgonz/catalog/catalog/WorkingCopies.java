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
  /** The most working copies one person can own at a time. */
  private static final int MOST_PER_OWNER = 20;

  /** A working copy as its owner's list shows it; a statement adds which ones it reads. */
  private static final String SUMMARY =
      """
      SELECT c.id, c.name, v.name AS vehicle_line, l.model_year, c.status, c.updated_at
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
      // The database keeps an owner's working copy names apart whatever their case.
      throw ApiException.conflict(
          "NAME_TAKEN", "Another of your working copies already has this name.");
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
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name,
      @NotNull(message = "Choose a vehicle line.") Long vehicleLineId,
      @NotNull(message = "Choose a model year.") Integer modelYear) {
    NewWorkingCopy {
      name = name == null ? null : name.strip();
    }
  }

  /** A working copy as its owner's list shows it. */
  record WorkingCopy(
      long id, String name, String vehicleLine, int modelYear, Status status, Instant updatedAt) {}
}
