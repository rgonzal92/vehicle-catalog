package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that a signed-in person creates a working copy that starts from the right place, and that
 * a working copy in status Draft is its owner's alone. Every test starts from the seeded library
 * and catalogs.
 */
class WorkingCopiesIT extends ApplicationIT {
  /**
   * What a copy takes from each content table, which is everything but the approval-time labels.
   */
  private static final Map<String, String> COPIED =
      Map.of(
          "catalog_trim", "trim_id",
          "catalog_region", "region_code",
          "catalog_trim_region", "trim_id, region_code",
          "catalog_feature", "feature_id",
          "catalog_cell", "feature_id, trim_id, region_code, availability");

  @Autowired MeterRegistry metrics;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void aLineageWithAnApprovedVersionStartsFromItsCurrentOne() {
    var current = approved("COMPACT_SUV", 2026, 2);

    var start = startPoint(ana(), line("COMPACT_SUV"), 2026);

    assertThat(start).hasStatusOk().bodyJson().extractingPath("$.kind").isEqualTo("COPY");
    assertThat(start).bodyJson().extractingPath("$.catalogId").isEqualTo((int) current);
    assertThat(start).bodyJson().extractingPath("$.modelYear").isEqualTo(2026);
    assertThat(start).bodyJson().extractingPath("$.versionNumber").isEqualTo(2);

    var created = create(ana(), "Winter update", line("COMPACT_SUV"), 2026);

    assertThat(created).hasStatus(201);
    assertThat(created).bodyJson().extractingPath("$.name").isEqualTo("Winter update");
    assertThat(created).bodyJson().extractingPath("$.vehicleLine").isEqualTo("Compact SUV");
    assertThat(created).bodyJson().extractingPath("$.modelYear").isEqualTo(2026);
    assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("DRAFT");
    var copy = idOf(created);
    assertThat(header(copy))
        .containsEntry("base_catalog_id", current)
        .containsEntry("owner_id", person("ana"))
        .containsEntry("lineage_id", header(current).get("lineage_id"))
        .containsEntry("revision", 0L)
        .containsEntry("version_number", null);
    assertSameContents(copy, current);
  }

  @Test
  void aLineageWithoutOneStartsFromTheNearestEarlierModelYearThatHasOne() {
    var pickup2026 = approved("PICKUP_TRUCK", 2026, 1);

    var start = startPoint(ana(), line("PICKUP_TRUCK"), 2027);

    assertThat(start).hasStatusOk().bodyJson().extractingPath("$.kind").isEqualTo("CARRYOVER");
    assertThat(start).bodyJson().extractingPath("$.catalogId").isEqualTo((int) pickup2026);
    assertThat(start).bodyJson().extractingPath("$.modelYear").isEqualTo(2026);
    assertThat(start).bodyJson().extractingPath("$.versionNumber").isEqualTo(1);

    var copy = idOf(create(ana(), "Pickup 2027", line("PICKUP_TRUCK"), 2027));

    assertThat(header(copy)).containsEntry("base_catalog_id", pickup2026);
    assertThat(header(copy).get("lineage_id"))
        .as("the working copy belongs to its own lineage, not to the one it was carried over from")
        .isNotEqualTo(header(pickup2026).get("lineage_id"));
    assertSameContents(copy, pickup2026);

    assertThat(startPoint(ana(), line("COMPACT_SUV"), 2028))
        .as("2027 is nearer to 2028 than 2026 is")
        .bodyJson()
        .extractingPath("$.catalogId")
        .isEqualTo((int) approved("COMPACT_SUV", 2027, 1));
  }

  @Test
  void aLineageWithNeitherStartsEmpty() {
    var start = startPoint(ana(), line("SPORTS_COUPE"), 2026);

    assertThat(start).hasStatusOk().bodyJson().extractingPath("$.kind").isEqualTo("EMPTY");
    assertThat(start).bodyJson().extractingPath("$.catalogId").isNull();

    var empty = idOf(create(ana(), "Coupe", line("SPORTS_COUPE"), 2026));

    assertThat(header(empty)).containsEntry("base_catalog_id", null);
    for (var table : COPIED.keySet()) {
      assertThat(contents(table, empty)).as(table).isEmpty();
    }
    assertThat(startPoint(ana(), line("SEDAN"), 2026))
        .as("a later model year is no starting point: the sedan has only 2027")
        .bodyJson()
        .extractingPath("$.kind")
        .isEqualTo("EMPTY");
  }

  @Test
  void aCopyKeepsEntriesTheLibraryHasSinceRetiredOrDeactivated() {
    jdbc.sql("UPDATE trim SET active = false WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE region SET active = false WHERE code = 'EU'").update();
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();

    var copy = idOf(create(ana(), "Winter update", line("COMPACT_SUV"), 2026));

    assertSameContents(copy, approved("COMPACT_SUV", 2026, 2));
  }

  @Test
  void aWorkingCopyShowsTheLibrarysCurrentLabelsAndItsBaseKeepsItsOwn() {
    var copy = idOf(create(ana(), "Winter update", line("COMPACT_SUV"), 2026));
    jdbc.sql("UPDATE trim SET name = 'Sport Plus' WHERE name = 'Sport'").update();

    var opened = open(ana(), copy);

    assertThat(opened).hasStatusOk().headers().hasValue("ETag", "\"0\"");
    assertThat(opened).bodyJson().extractingPath("$.name").isEqualTo("Winter update");
    assertThat(opened).bodyJson().extractingPath("$.vehicleLine").isEqualTo("Compact SUV");
    assertThat(opened)
        .bodyJson()
        .extractingPath("$.vehicleLineId")
        .isEqualTo((int) line("COMPACT_SUV"));
    assertThat(opened).bodyJson().extractingPath("$.versionNumber").isNull();
    assertThat(opened).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
    assertThat(opened).bodyJson().extractingPath("$.base.modelYear").isEqualTo(2026);
    assertThat(opened).bodyJson().extractingPath("$.base.versionNumber").isEqualTo(2);
    assertThat(ApplicationIT.<List<String>>read(opened, "$.snapshot.trims[*].name"))
        .containsExactly("Base", "Sport Plus", "Touring", "Off-Road");
    assertThat(
            ApplicationIT.<List<String>>read(
                open(ana(), approved("COMPACT_SUV", 2026, 2)), "$.snapshot.trims[*].name"))
        .containsExactly("Base", "Sport", "Touring", "Off-Road");
  }

  @Test
  void aNameIsTakenOnlyAmongItsOwnersWorkingCopiesWhateverItsCase() {
    assertThat(create(ana(), "Winter update", line("SPORTS_COUPE"), 2026)).hasStatus(201);

    var again = create(ana(), "  WINTER Update ", line("SEDAN"), 2028);

    assertThat(again).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("NAME_TAKEN");
    assertThat(create(signedInAs(Role.AUTHOR, "ben"), "Winter update", line("SEDAN"), 2028))
        .as("another owner may use the name")
        .hasStatus(201);
    assertThat(create(ana(), "Hybrid and autumn update", line("SEDAN"), 2028))
        .as("the name of an Approved version is free")
        .hasStatus(201);
    assertThat(count("lineage WHERE vehicle_line_id = %d AND model_year = 2028", line("SEDAN")))
        .as("the refused one left no lineage behind, and the two that followed share one")
        .isEqualTo(1);
  }

  @Test
  void twoWorkingCopiesOfOneLineageExistAtOnce() {
    var one = idOf(create(ana(), "One", line("COMPACT_SUV"), 2026));
    var other = idOf(create(ana(), "Other", line("COMPACT_SUV"), 2026));
    var bens = idOf(create(signedInAs(Role.AUTHOR, "ben"), "One", line("COMPACT_SUV"), 2026));

    assertThat(List.of(header(one), header(other), header(bens)))
        .extracting(catalog -> catalog.get("lineage_id"))
        .containsOnly(header(approved("COMPACT_SUV", 2026, 2)).get("lineage_id"));
    assertThat(open(ana(), one)).hasStatusOk();
    assertThat(open(ana(), other)).hasStatusOk();
  }

  @Test
  void anOwnerHasAtMostTwentyWorkingCopies() {
    for (var number = 1; number <= 20; number++) {
      assertThat(create(ana(), "Coupe " + number, line("SPORTS_COUPE"), 2026)).hasStatus(201);
    }

    var oneTooMany = create(ana(), "Coupe 21", line("SPORTS_COUPE"), 2026);

    assertThat(oneTooMany)
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(create(signedInAs(Role.AUTHOR, "ben"), "Coupe 21", line("SPORTS_COUPE"), 2026))
        .as("the limit is each owner's own")
        .hasStatus(201);
  }

  @Test
  void aDeactivatedVehicleLineIsRefused() {
    jdbc.sql("UPDATE vehicle_line SET active = false WHERE code = 'SEDAN'").update();

    for (var refused :
        List.of(
            create(ana(), "Sedan", line("SEDAN"), 2027), startPoint(ana(), line("SEDAN"), 2027))) {
      assertThat(refused)
          .hasStatus(409)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VEHICLE_LINE_INACTIVE");
    }
    assertThat(count("catalog WHERE status <> 'APPROVED'")).isZero();
  }

  @Test
  void aModelYearOutsideTheConfiguredOnesAndAnUnknownVehicleLineAreRefused() {
    for (var refused :
        List.of(
            create(ana(), "Old sedan", line("SEDAN"), 2019),
            startPoint(ana(), line("SEDAN"), 2019),
            create(ana(), "Nothing", 987_654_321, 2026),
            startPoint(ana(), 987_654_321, 2026))) {
      assertThat(refused)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
    }
  }

  @Test
  void aNameIsOneToEightyCharacters() {
    assertThat(create(ana(), "   ", line("SEDAN"), 2027)).hasStatus(422);
    assertThat(create(ana(), "x".repeat(81), line("SEDAN"), 2027))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VALIDATION");
    assertThat(create(ana(), " " + "x".repeat(80) + " ", line("SEDAN"), 2027)).hasStatus(201);
  }

  @Test
  void everyRoleCreatesAWorkingCopyAndAVisitorWithoutASessionDoesNot() {
    for (var role : Role.values()) {
      var created = create(signedInAs(role, "a-" + role.key()), "Mine", line("SEDAN"), 2027);

      assertThat(created).as(role.name()).hasStatus(201);
      assertThat(header(idOf(created))).containsEntry("owner_id", person("a-" + role.key()));
    }
    assertThat(create(request -> request, "Nobody's", line("SEDAN"), 2027)).hasStatus(401);
  }

  @Test
  void onlyItsOwnerOpensAWorkingCopyInStatusDraft() {
    var copy = idOf(create(ana(), "Winter update", line("COMPACT_SUV"), 2026));

    assertThat(open(ana(), copy)).hasStatusOk();
    for (var role : Role.values()) {
      assertThat(open(signedInAs(role, "someone-else"), copy))
          .as(role.name())
          .hasStatus(404)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NOT_FOUND");
    }
  }

  @Test
  void myCatalogsListsTheCallersWorkingCopiesLastUpdatedFirst() {
    var coupe = idOf(create(ana(), "Coupe", line("SPORTS_COUPE"), 2026));
    var suv = idOf(create(ana(), "Winter update", line("COMPACT_SUV"), 2026));
    create(signedInAs(Role.AUTHOR, "ben"), "Ben's", line("SEDAN"), 2027);

    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(ana()).exchange();

    assertThat(mine).hasStatusOk();
    assertThat(ApplicationIT.<List<Integer>>read(mine, "$[*].id"))
        .containsExactly((int) suv, (int) coupe);
    assertThat(mine).bodyJson().extractingPath("$[0].name").isEqualTo("Winter update");
    assertThat(mine).bodyJson().extractingPath("$[0].vehicleLine").isEqualTo("Compact SUV");
    assertThat(mine).bodyJson().extractingPath("$[0].modelYear").isEqualTo(2026);
    assertThat(mine).bodyJson().extractingPath("$[0].status").isEqualTo("DRAFT");
    assertThat(mine).bodyJson().extractingPath("$[0].updatedAt").asString().isNotBlank();
    assertThat(mvc.get().uri("/api/catalogs").with(ana()))
        .as("the list says whose catalogs it wants")
        .hasStatus(400);
  }

  @Test
  void theTimeACopyTakesIsRecorded() {
    var before = copiesTimed();

    create(ana(), "Winter update", line("COMPACT_SUV"), 2026);
    create(ana(), "Coupe", line("SPORTS_COUPE"), 2026);

    assertThat(copiesTimed()).as("an empty start copies nothing").isEqualTo(before + 1);
  }

  private long copiesTimed() {
    var timer = metrics.find("catalog.copy").timer();
    return timer == null ? 0 : timer.count();
  }

  /** Ana, an author who is on record. */
  private RequestPostProcessor ana() {
    return signedInAs(Role.AUTHOR, "ana");
  }

  private MvcTestResult startPoint(RequestPostProcessor who, long vehicleLineId, int modelYear) {
    return mvc.get()
        .uri(
            "/api/catalogs/start-point?vehicleLineId={line}&modelYear={year}",
            vehicleLineId,
            modelYear)
        .with(who)
        .exchange();
  }

  private MvcTestResult create(
      RequestPostProcessor who, String name, long vehicleLineId, int modelYear) {
    return mvc.post()
        .uri("/api/catalogs")
        .with(who)
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"name": "%s", "vehicleLineId": %d, "modelYear": %d}
            """
                .formatted(name, vehicleLineId, modelYear))
        .exchange();
  }

  private MvcTestResult open(RequestPostProcessor who, long catalog) {
    return mvc.get().uri("/api/catalogs/" + catalog).with(who).exchange();
  }

  private static long idOf(MvcTestResult created) {
    return ApplicationIT.<Integer>read(created, "$.id");
  }

  private long line(String code) {
    return jdbc.sql("SELECT id FROM vehicle_line WHERE code = :code")
        .param("code", code)
        .query(Long.class)
        .single();
  }

  /** The catalog that is the given Approved version of the vehicle line's model year. */
  private long approved(String vehicleLine, int modelYear, int version) {
    return jdbc.sql(
            """
            SELECT c.id
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            WHERE l.vehicle_line_id = :line AND l.model_year = :year AND c.version_number = :version
            """)
        .param("line", line(vehicleLine))
        .param("year", modelYear)
        .param("version", version)
        .query(Long.class)
        .single();
  }

  private Map<String, Object> header(long catalog) {
    return jdbc.sql("SELECT * FROM catalog WHERE id = :id")
        .param("id", catalog)
        .query()
        .singleRow();
  }

  private List<Map<String, Object>> contents(String table, long catalog) {
    var columns = COPIED.get(table);
    return jdbc.sql(
            "SELECT %s FROM %s WHERE catalog_id = :id ORDER BY %s"
                .formatted(columns, table, columns))
        .param("id", catalog)
        .query()
        .listOfRows();
  }

  /** The copy holds the rows and keys of its source, and none of the labels frozen at approval. */
  private void assertSameContents(long copy, long source) {
    for (var table : COPIED.keySet()) {
      assertThat(contents(table, source)).as(table + " of the source").isNotEmpty();
      assertThat(contents(table, copy)).as(table).isEqualTo(contents(table, source));
    }
    assertThat(
            count(
                """
                catalog_trim WHERE catalog_id = %d
                AND (approved_name IS NOT NULL OR approved_sort_order IS NOT NULL)
                """,
                copy))
        .isZero();
    assertThat(count("catalog_region WHERE catalog_id = %d AND approved_name IS NOT NULL", copy))
        .isZero();
    assertThat(
            count(
                """
                catalog_feature WHERE catalog_id = %d
                AND (approved_name IS NOT NULL OR approved_category_code IS NOT NULL)
                """,
                copy))
        .isZero();
  }

  private long count(String from, Object... values) {
    return jdbc.sql("SELECT count(*) FROM " + from.formatted(values)).query(Long.class).single();
  }
}
