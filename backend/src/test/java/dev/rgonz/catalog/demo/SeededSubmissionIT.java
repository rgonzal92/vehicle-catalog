package dev.rgonz.catalog.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.ai.ModelStandIn;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks the catalog the demo starts with in its review queue: that a first start leaves it
 * Submitted by the demo author, with what the seed file lists as its changes and without an Error,
 * that its owner and a reviewer can do with it what they can with any submission, that the worker
 * has its summary written, and that a demo reset puts it back whatever was done to it. Every other
 * test runs with this seed turned off.
 */
@TestPropertySource(properties = "app.submission.seeded=true")
class SeededSubmissionIT extends ApplicationIT {
  private static final String NAME = "Winter content for Europe";
  private static final String NOTE =
      "The heated windshield is new, and offered in Europe: Standard on Luxury, Available on Base"
          + " and Sport. It cannot be built with the head-up display, so Luxury no longer offers"
          + " the display in Europe. The heated steering wheel becomes Available on Base and"
          + " Standard on Sport, also in Europe.";

  /** What the stand-in answers for the model, which holds to what the catalog changes. */
  private static final String SUMMARY =
      """
      {"headline": "Winter content for Europe", "bullets": ["Heated Windshield is added."]}
      """;

  @Autowired DemoReset reset;
  @Autowired ApplicationContext application;

  /** The catalog that waits for review. */
  private long submitted;

  @BeforeEach
  void aFirstStart() throws Exception {
    Worker.forgets(application);
    ModelStandIn.forgets();
    jdbc.sql("DELETE FROM ai_spend").update();
    seedLibraryAndCatalogs();
    submitted = theOneThatWaits();
  }

  /** Nothing of the seed is left for the tests that follow, which run without it. */
  @AfterEach
  void noCatalogsAndNoJobs() {
    emptyLibraryAndCatalogs();
    jdbc.sql("DELETE FROM ai_spend").update();
    Worker.forgets(application);
  }

  private RequestPostProcessor author() {
    return signedInAs(Role.AUTHOR, "author");
  }

  private RequestPostProcessor manager() {
    return signedInAs(Role.MANAGER, "manager");
  }

  private long theOneThatWaits() {
    return jdbc.sql("SELECT id FROM catalog WHERE status = 'SUBMITTED'").query(Long.class).single();
  }

  private <T> T asked(RequestPostProcessor who, String address, String path) {
    var answer = mvc.get().uri(address).with(who).exchange();
    assertThat(answer).as(address).hasStatusOk();
    return read(answer, path);
  }

  /** Has the person do something to a catalog, naming the revision it is at. */
  private MvcTestResult does(RequestPostProcessor who, String what, long catalog, String body) {
    var revision =
        jdbc.sql("SELECT revision FROM catalog WHERE id = :id")
            .param("id", catalog)
            .query(Long.class)
            .single();
    return mvc.post()
        .uri("/api/catalogs/{id}/{what}", catalog, what)
        .with(who)
        .with(csrfToken())
        .header("If-Match", "\"" + revision + "\"")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private String summaryStatus() {
    return asked(manager(), "/api/catalogs/" + submitted + "/summary", "$.status");
  }

  /** Each lineage with its current Approved version, as the dashboard lists them. */
  private List<String> lineages() {
    return ApplicationIT.<List<Map<String, Object>>>read(
            mvc.get().uri("/api/lineages").with(author()).exchange(), "$")
        .stream()
        .map(
            one ->
                one.get("vehicleLine")
                    + " "
                    + one.get("modelYear")
                    + " v"
                    + one.get("versionNumber"))
        .toList();
  }

  @Test
  void aFirstStartLeavesItInTheReviewQueueWithWhatItChangesAndWithoutAnError() {
    List<Map<String, Object>> queue = asked(manager(), "/api/catalogs?scope=review", "$");
    assertThat(queue).hasSize(1);
    assertThat(queue.getFirst())
        .containsEntry("name", NAME)
        .containsEntry("vehicleLine", "Sedan")
        .containsEntry("modelYear", 2027)
        .containsEntry("owner", "Demo Author")
        .containsEntry("note", NOTE)
        .containsEntry("own", false);
    assertThat((String) queue.getFirst().get("submittedAt")).isNotBlank();

    var review = "/api/catalogs/" + submitted;
    assertThat(this.<String>asked(manager(), review, "$.snapshot.status")).isEqualTo("SUBMITTED");
    assertThat(this.<Integer>asked(manager(), review, "$.base.versionNumber")).isEqualTo(1);
    assertThat(this.<Boolean>asked(manager(), review, "$.stale")).isFalse();
    assertThat(this.<List<String>>asked(manager(), review, "$.issues[?(@.severity == 'ERROR')]"))
        .as("its Errors")
        .isEmpty();
    long base = this.<Integer>asked(manager(), review, "$.base.catalogId");
    assertThat(this.<List<String>>asked(manager(), review, "$.issues[*].message"))
        .as("its issues are the Warnings its base has, and no other")
        .isEqualTo(asked(manager(), "/api/catalogs/" + base, "$.issues[*].message"));

    Map<String, List<Map<String, Object>>> changes =
        asked(manager(), review + "/diff?against=base", "$");
    assertThat(changes.get("featureRowsAdded"))
        .extracting(row -> row.get("code"))
        .containsExactly("WINDSHIELD_HEATED");
    assertThat(changes.get("cellsChanged"))
        .extracting(
            cell ->
                "%s on %s in %s: %s to %s"
                    .formatted(
                        cell.get("feature"),
                        cell.get("trim"),
                        cell.get("region"),
                        cell.get("before"),
                        cell.get("after")))
        .containsExactlyInAnyOrder(
            "Heated Steering Wheel on Base in Europe: N to A",
            "Heated Steering Wheel on Sport in Europe: A to S",
            "Head-Up Display on Luxury in Europe: A to N");
    assertThat(changes.get("rulesAdded"))
        .extracting(rule -> rule.get("rule"))
        .containsExactly("Head-Up Display excludes Heated Windshield");
    assertThat(
            Stream.of(
                    "trimsAdded",
                    "trimsRemoved",
                    "regionsAdded",
                    "regionsRemoved",
                    "offeringsAdded",
                    "offeringsRemoved",
                    "featureRowsRemoved",
                    "rulesRemoved",
                    "rulesChanged")
                .filter(kind -> !changes.get(kind).isEmpty()))
        .as("what else it changes")
        .isEmpty();
    // The new row's cells are its own: Available on Base and Sport, Standard on Luxury, in Europe.
    assertThat(
            jdbc.sql(
                    """
                    SELECT t.name || ' ' || c.region_code || ' ' || c.availability
                    FROM catalog_cell c
                    JOIN trim t ON t.id = c.trim_id
                    JOIN feature f ON f.id = c.feature_id
                    WHERE c.catalog_id = :catalog AND f.code = 'WINDSHIELD_HEATED'
                    ORDER BY t.sort_order
                    """)
                .param("catalog", submitted)
                .query(String.class)
                .list())
        .containsExactly("Base EU A", "Sport EU A", "Luxury EU S");
    assertThat(
            jdbc.sql(
                    """
                    SELECT string_agg(kind, ' ' ORDER BY id) FROM catalog_change
                    WHERE catalog_id = :catalog AND actor_id = :owner
                    """)
                .param("catalog", submitted)
                .param("owner", person("author"))
                .query(String.class)
                .single())
        .as("its change history, every entry its owner's")
        .isEqualTo(
            "FEATURE_ADDED CELL_SET CELL_SET CELL_SET CELL_SET CELL_SET CELL_SET RULE_ADDED"
                + " SUBMITTED");
  }

  @Test
  void itsOwnerFindsItAmongTheirCatalogsAsSubmittedAndCanWithdrawIt() {
    List<Map<String, Object>> mine = asked(author(), "/api/catalogs?scope=mine", "$");
    assertThat(mine).hasSize(1);
    assertThat(mine.getFirst())
        .containsEntry("name", NAME)
        .containsEntry("status", "SUBMITTED")
        .containsEntry("errors", 0)
        .containsEntry("stale", false);

    assertThat(does(author(), "withdraw", submitted, "{}")).hasStatusOk();

    assertThat(this.<List<String>>asked(author(), "/api/catalogs?scope=mine", "$[*].status"))
        .containsExactly("DRAFT");
    assertThat(this.<List<Object>>asked(manager(), "/api/catalogs?scope=review", "$")).isEmpty();
  }

  @Test
  void aManagerApprovesItAndItBecomesTheNextVersionOfItsLineage() {
    assertThat(lineages()).contains("Sedan 2027 v1");

    assertThat(does(manager(), "approve", submitted, "{}")).hasStatusOk();

    assertThat(lineages()).contains("Sedan 2027 v2").doesNotContain("Sedan 2027 v1");
    assertThat(this.<String>asked(author(), "/api/catalogs/" + submitted, "$.snapshot.status"))
        .isEqualTo("APPROVED");
  }

  @Test
  void theWorkerHasItsSummaryWrittenAsForAnySubmission() {
    assertThat(summaryStatus()).isEqualTo("PENDING");
    ModelStandIn.says(SUMMARY);

    Worker.runs(application);

    var summary = "/api/catalogs/" + submitted + "/summary";
    assertThat(summaryStatus()).isEqualTo("READY");
    assertThat(this.<String>asked(manager(), summary, "$.headline"))
        .isEqualTo("Winter content for Europe");
    assertThat(this.<List<String>>asked(manager(), summary, "$.bullets"))
        .containsExactly("Heated Windshield is added.");
    assertThat(ModelStandIn.asked()).as("the model is asked once").hasSize(1);
    assertThat(ModelStandIn.asked().getFirst().toString())
        .as("and is given what the catalog changes")
        .contains("WINDSHIELD_HEATED", "Head-Up Display excludes Heated Windshield");
  }

  @ParameterizedTest
  @ValueSource(strings = {"approved", "rejected", "withdrawn", "made stale"})
  void aDemoResetPutsItBackAsItWasSeededWhateverWasDoneToIt(String done) {
    var seeded = everything();
    ModelStandIn.says(SUMMARY);
    Worker.runs(application);
    assertThat(summaryStatus()).isEqualTo("READY");
    switch (done) {
      case "approved" -> assertThat(does(manager(), "approve", submitted, "{}")).hasStatusOk();
      case "rejected" ->
          assertThat(does(manager(), "reject", submitted, "{\"comment\": \"Not this winter.\"}"))
              .hasStatusOk();
      case "withdrawn" -> assertThat(does(author(), "withdraw", submitted, "{}")).hasStatusOk();
      default -> {
        // Someone else's working copy of the same lineage is approved first.
        var ana = signedInAs(Role.AUTHOR, "ana");
        var created =
            mvc.post()
                .uri("/api/catalogs")
                .with(ana)
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name": "Another", "vehicleLineId": %d, "modelYear": 2027}
                    """
                        .formatted(
                            jdbc.sql("SELECT id FROM vehicle_line WHERE code = 'SEDAN'")
                                .query(Long.class)
                                .single()))
                .exchange();
        assertThat(created).hasStatus(201);
        long another = ApplicationIT.<Integer>read(created, "$.id");
        assertThat(does(ana, "submit", another, "{}")).hasStatusOk();
        assertThat(does(manager(), "approve", another, "{}")).hasStatusOk();
        assertThat(this.<Boolean>asked(author(), "/api/catalogs/" + submitted, "$.stale")).isTrue();
      }
    }
    assertThat(everything()).as("the demo after it was %s", done).isNotEqualTo(seeded);

    reset.run();

    assertThat(everything()).isEqualTo(seeded);
    assertThat(theOneThatWaits()).as("it is a catalog of its own").isNotEqualTo(submitted);
    submitted = theOneThatWaits();
    assertThat(summaryStatus()).as("its summary is to be written again").isEqualTo("PENDING");
    ModelStandIn.says(SUMMARY);
    Worker.runs(application);
    assertThat(summaryStatus()).isEqualTo("READY");
  }

  /**
   * Everything the library and the catalogs hold, as the test of the demo reset tells it, and of
   * the catalog that waits what that leaves out: its note, its change history, and how far its
   * summary is. A rule that a working copy adds gets a key of its own each time, so the keys that
   * its base does not have are left out.
   */
  private List<String> everything() {
    var ownKeys =
        jdbc.sql(
                """
                SELECT key
                FROM catalog c
                JOIN catalog_rule r ON r.catalog_id = c.id
                CROSS JOIN LATERAL unnest(ARRAY[r.rule_key::text, r.pair_key::text]) AS key
                WHERE c.status <> 'APPROVED' AND key IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM catalog_rule b
                      WHERE b.catalog_id = c.base_catalog_id AND b.rule_key = r.rule_key)
                """)
            .query(String.class)
            .list();
    var waiting =
        jdbc.sql(
                """
                SELECT 'waiting ' || concat_ws('|', c.name, c.submit_note, c.submitted_at IS NOT NULL,
                           (SELECT string_agg(h.kind, ' ' ORDER BY h.id)
                            FROM catalog_change h WHERE h.catalog_id = c.id),
                           (SELECT string_agg(s.revision || ' ' || s.status, ' ')
                            FROM submission_summary s WHERE s.catalog_id = c.id))
                FROM catalog c WHERE c.status = 'SUBMITTED'
                """)
            .query(String.class)
            .list();

    return Stream.concat(DemoResetIT.content(jdbc).stream(), waiting.stream())
        .map(
            line -> {
              for (var key : ownKeys) {
                line = line.replace(key, "a key of its own");
              }
              return line;
            })
        .sorted()
        .toList();
  }
}
