package dev.rgonz.catalog.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.core.Seed;
import dev.rgonz.catalog.user.SandboxRoles;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks that the demo reset leaves the library and the catalogs as a first start leaves them,
 * whatever was done to them and by whom, and leaves the people who signed in as they are.
 */
class DemoResetIT extends ApplicationIT {
  /** The subject of the sandbox account "visitor", which is configured as an author. */
  private static final String VISITOR = "sandbox-visitor";

  @Autowired DemoReset reset;
  @Autowired DataSource dataSource;
  @Autowired TransactionTemplate transactions;
  @Autowired SandboxRoles sandboxRoles;
  @Autowired ApplicationContext context;
  @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

  @BeforeEach
  void aFirstStart() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void itRunsAtThreeInTheMorningUtcWhateverTheHostsTimezone() throws Exception {
    var schedule = DemoReset.class.getDeclaredMethod("run").getAnnotation(Scheduled.class);
    assertThat(schedule.zone()).isEqualTo("UTC");
    assertThat(schedule.cron()).isEqualTo("${app.demo-reset.cron}");

    // The tests turn the schedule off, so what it is set to is read from where the app reads it.
    var settings = new Properties();
    try (var file = new ClassPathResource("application.properties").getInputStream()) {
      settings.load(file);
    }
    var everyDay = CronExpression.parse(settings.getProperty("app.demo-reset.cron"));
    var summerNoon = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneId.of("UTC"));
    var winterNoon = ZonedDateTime.of(2026, 12, 31, 12, 0, 0, 0, ZoneId.of("UTC"));
    assertThat(everyDay.next(summerNoon))
        .isEqualTo(ZonedDateTime.of(2026, 7, 2, 3, 0, 0, 0, ZoneId.of("UTC")));
    assertThat(everyDay.next(winterNoon))
        .isEqualTo(ZonedDateTime.of(2027, 1, 1, 3, 0, 0, 0, ZoneId.of("UTC")));
  }

  @Test
  void theApplicationRunsWhatIsScheduled() {
    assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
        .as("without this, nothing that is scheduled ever runs")
        .hasSize(1);
  }

  @Test
  void whateverWasDoneItLeavesExactlyTheSeededLibraryAndCatalogs() {
    var seeded = content();
    var owners = List.of("author", "manager", "operator", VISITOR, "sub-someone-else");
    var people = owners.stream().map(this::person).toList();
    var peopleOnRecord = count("app_user");
    var fixedLists = count("vehicle_type") + " vehicle types, " + count("category") + " categories";

    // The library: an entry added, renamed, deactivated, and retired.
    jdbc.sql("INSERT INTO trim (name, sort_order) VALUES ('A visitor''s trim', 99)").update();
    jdbc.sql("INSERT INTO region (code, name, sort_order) VALUES ('VISIT', 'Visitland', 99)")
        .update();
    jdbc.sql("UPDATE trim SET name = name || ' (renamed)' WHERE id = (SELECT min(id) FROM trim)")
        .update();
    jdbc.sql("UPDATE vehicle_line SET active = false").update();
    jdbc.sql(
            "UPDATE feature SET status = 'RETIRED', version = version + 1"
                + " WHERE id = (SELECT min(id) FROM feature)")
        .update();
    // The catalogs: work of the demo accounts, of the operator, of a sandbox account, and of
    // someone who is none of these, each edited.
    for (var subject : owners) {
      var copy = workingCopyOf(subject);
      jdbc.sql("DELETE FROM catalog_cell WHERE catalog_id = ?").param(copy).update();
      jdbc.sql(
              "INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)"
                  + " VALUES (?, ?, 'RENAMED', '{}')")
          .params(copy, person(subject))
          .update();
    }
    // And a version someone approved since.
    jdbc.sql(
            """
            UPDATE catalog
            SET status = 'APPROVED', approved_by = owner_id, approved_at = now(),
                version_number = 1 + (SELECT max(version_number) FROM catalog)
            WHERE id = (SELECT max(id) FROM catalog)
            """)
        .update();
    assertThat(content()).as("the demo has been worked in").isNotEqualTo(seeded);

    reset.run();

    assertThat(content()).isEqualTo(seeded);
    assertThat(count("catalog WHERE status <> 'APPROVED'")).as("working copies").isZero();
    assertThat(count("catalog_change")).as("change history").isZero();
    assertThat(count("app_user")).as("people on record").isEqualTo(peopleOnRecord);
    assertThat(owners.stream().map(this::person).toList())
        .as("each is still who they were")
        .isEqualTo(people);
    assertThat(count("vehicle_type") + " vehicle types, " + count("category") + " categories")
        .isEqualTo(fixedLists);
    assertThat(mvc.get().uri("/api/me").with(signedInAs(Role.AUTHOR, "author"))).hasStatusOk();
  }

  @Test
  void whatItLeavesIsWhatTheSeedFilesHold() throws Exception {
    jdbc.sql("DELETE FROM catalog_change").update();
    jdbc.sql("UPDATE trim SET name = name || ' (renamed)'").update();
    jdbc.sql("DELETE FROM global_rule").update();

    reset.run();

    assertThat(jdbc.sql("SELECT name FROM trim ORDER BY sort_order").query(String.class).list())
        .isEqualTo(namesIn("trims.json", "name"));
    assertThat(jdbc.sql("SELECT code FROM region ORDER BY sort_order").query(String.class).list())
        .isEqualTo(namesIn("regions.json", "code"));
    assertThat(count("vehicle_line")).isEqualTo(namesIn("vehicle-lines.json", "code").size());
    assertThat(count("feature")).isEqualTo(namesIn("features.json", "code").size());
    var kinds = namesIn("global-rules.json", "kind");
    assertThat(count("global_rule"))
        .as("each seeded rule, and for an exclusion its pair too")
        .isEqualTo(kinds.size() + kinds.stream().filter("EXCLUDES"::equals).count());
    assertThat(count("catalog")).isEqualTo(namesIn("catalogs.json", "vehicleLine").size());
  }

  @Test
  void identifiersGoOnCountingUpAndNoneIsUsedAgain() {
    var tables = List.of("trim", "feature", "vehicle_line", "lineage", "catalog");
    var highestBefore = new HashMap<String, Long>();
    for (var table : tables) {
      highestBefore.put(table, jdbc.sql("SELECT max(id) FROM " + table).query(Long.class).single());
    }

    reset.run();

    for (var table : tables) {
      assertThat(jdbc.sql("SELECT min(id) FROM " + table).query(Long.class).single())
          .as("the lowest %s id after the reset", table)
          .isGreaterThan(highestBefore.get(table));
    }
  }

  @Test
  void anUnprotectedSandboxAccountIsBackInItsConfiguredRoleWithItsSessionsEnded() {
    assertThat(
            mvc.put()
                .uri("/api/admin/users/visitor/role")
                .with(signedInAs(Role.ADMIN, "operator"))
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"admin\"}"))
        .hasStatusOk();
    signIn(VISITOR);
    signIn("author");
    var authorsSessions = sessions.findByPrincipalName("author").size();

    reset.run();

    assertThat(mvc.get().uri("/api/admin/users").with(signedInAs(Role.ADMIN, "operator")))
        .bodyJson()
        .extractingPath("$[?(@.username == 'visitor')].role")
        .asArray()
        .containsExactly("author");
    assertThat(sessions.findByPrincipalName(VISITOR)).isEmpty();
    assertThat(sessions.findByPrincipalName("author"))
        .as("no one else is signed out")
        .hasSize(authorsSessions);
  }

  @Test
  void whenTheLibraryCannotBeSeededNothingIsLostAndTheRolesAreStillPutBack() {
    jdbc.sql("INSERT INTO trim (name, sort_order) VALUES ('A visitor''s trim', 99)").update();
    var worked = content();
    assertThat(
            mvc.put()
                .uri("/api/admin/users/visitor/role")
                .with(signedInAs(Role.ADMIN, "operator"))
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\": \"admin\"}"))
        .hasStatusOk();
    Seed failing =
        arguments -> {
          throw new IllegalStateException("A seed file could not be read");
        };

    new DemoReset(jdbc, transactions, List.of(failing), sandboxRoles, "-").run();

    assertThat(content()).as("emptying the tables was undone with the rest").isEqualTo(worked);
    assertThat(mvc.get().uri("/api/admin/users").with(signedInAs(Role.ADMIN, "operator")))
        .bodyJson()
        .extractingPath("$[?(@.username == 'visitor')].role")
        .asArray()
        .containsExactly("author");
  }

  @Test
  void aRequestThatArrivesDuringItWaitsAndThenFindsTheSeededState() throws Exception {
    var seededTrims = trimNames();
    var seededLines = vehicleLineCodes();
    var seededLineages = lineages();
    jdbc.sql("INSERT INTO trim (name, sort_order) VALUES ('A visitor''s trim', 99)").update();
    jdbc.sql("UPDATE vehicle_line SET active = false").update();
    workingCopyOf("author");

    try (var holder = dataSource.getConnection()) {
      // The seed of the catalogs writes to the demo author's record. While that record is held,
      // the reset stands still in the middle of its work, with the library seeded and no catalogs.
      holder.setAutoCommit(false);
      holder
          .createStatement()
          .execute("SELECT 1 FROM app_user WHERE cognito_sub = 'author' FOR UPDATE");
      var resetting = CompletableFuture.runAsync(reset::run);
      await("the reset to hold the tables", () -> tablesHeld() > 0);

      var trims = CompletableFuture.supplyAsync(this::trimNames);
      var lines = CompletableFuture.supplyAsync(this::vehicleLineCodes);
      var catalogs = CompletableFuture.supplyAsync(this::lineages);
      Thread.sleep(500);
      assertThat(List.of(resetting, trims, lines, catalogs))
          .as("the reset and every request made during it are still waiting")
          .noneMatch(CompletableFuture::isDone);

      holder.rollback();
      resetting.get(20, TimeUnit.SECONDS);
      assertThat(trims.get(20, TimeUnit.SECONDS)).isEqualTo(seededTrims);
      assertThat(lines.get(20, TimeUnit.SECONDS)).isEqualTo(seededLines);
      assertThat(catalogs.get(20, TimeUnit.SECONDS)).isEqualTo(seededLineages);
    }
  }

  @Test
  void itWaitsForARequestThatIsUsingATableHoldingNothingMeanwhile() throws Exception {
    var seeded = content();
    jdbc.sql("INSERT INTO trim (name, sort_order) VALUES ('A visitor''s trim', 99)").update();

    try (var request = dataSource.getConnection()) {
      // A request in the middle of its work: it has read one table and has more to do.
      request.setAutoCommit(false);
      request.createStatement().execute("SELECT count(*) FROM vehicle_line");
      var resetting = CompletableFuture.runAsync(reset::run);
      Thread.sleep(700);

      assertThat(resetting).as("the reset has not gone ahead").isNotDone();
      assertThat(tablesHeld()).as("and holds no table while it waits").isZero();
      // The request goes on to the tables the reset would have taken first, and is not stopped.
      request.createStatement().execute("SELECT count(*) FROM lineage");
      request.createStatement().execute("SELECT count(*) FROM catalog");
      request.commit();

      resetting.get(20, TimeUnit.SECONDS);
    }
    assertThat(content()).isEqualTo(seeded);
  }

  /** How many of the content tables another session holds for itself alone. */
  private long tablesHeld() {
    return jdbc.sql(
            """
            SELECT count(*) FROM pg_locks
            WHERE granted AND mode = 'AccessExclusiveLock' AND pid <> pg_backend_pid()
              AND relation IN ('trim'::regclass, 'catalog'::regclass, 'lineage'::regclass)
            """)
        .query(Long.class)
        .single();
  }

  private static void await(String what, BooleanSupplier reached) throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (reached.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Waited five seconds for " + what);
  }

  /** The values of one field of the entries of a seed file, in the file's order. */
  private List<String> namesIn(String seedFile, String field) throws IOException {
    try (var file = new ClassPathResource("seed/" + seedFile).getInputStream()) {
      return JsonPath.read(file, "$[*]." + field);
    }
  }

  /** The codes of the active vehicle lines, as an author is told them. */
  private List<String> vehicleLineCodes() {
    return read(
        mvc.get().uri("/api/vehicle-lines").with(signedInAs(Role.AUTHOR)).exchange(),
        "$[?(@.active)].code");
  }

  /** Each lineage with its current Approved version, as an author is told them, without ids. */
  private List<String> lineages() {
    var answer = mvc.get().uri("/api/lineages").with(signedInAs(Role.AUTHOR)).exchange();
    List<String> lines = read(answer, "$[*].vehicleLine");
    List<Integer> years = read(answer, "$[*].modelYear");
    List<Integer> versions = read(answer, "$[*].versionNumber");

    return IntStream.range(0, lines.size())
        .mapToObj(at -> lines.get(at) + " " + years.get(at) + " v" + versions.get(at))
        .toList();
  }

  /** The names of the library's trims, as an author is told them. */
  private List<String> trimNames() {
    return read(mvc.get().uri("/api/trims").with(signedInAs(Role.AUTHOR)).exchange(), "$[*].name");
  }

  /** A working copy of the first Approved version, owned by the person with the subject. */
  private long workingCopyOf(String subject) {
    var copy =
        jdbc.sql(
                """
                INSERT INTO catalog (lineage_id, name, owner_id, base_catalog_id)
                SELECT lineage_id, 'Work of ' || :subject, :owner, id
                FROM catalog WHERE status = 'APPROVED' ORDER BY id LIMIT 1
                RETURNING id
                """)
            .param("subject", subject)
            .param("owner", person(subject))
            .query(Long.class)
            .single();
    jdbc.sql("SELECT copy_catalog(base_catalog_id, id) FROM catalog WHERE id = ?")
        .param(copy)
        .query((row, number) -> 0)
        .list();

    return copy;
  }

  /** Leaves the person with one more session, as a login does. */
  private <S extends Session> void signIn(String subject) {
    @SuppressWarnings("unchecked")
    var repository = (FindByIndexNameSessionRepository<S>) sessions;
    var session = repository.createSession();
    session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, subject);
    repository.save(session);
  }

  private long count(String from) {
    return jdbc.sql("SELECT count(*) FROM " + from).query(Long.class).single();
  }

  /**
   * Everything the library and the catalogs hold, one line for each row, named by what a person
   * would call it and never by an id, since a reset gives everything new ids.
   */
  private List<String> content() {
    return jdbc.sql(
            """
            -- A catalog is told from every other by its lineage, its owner, and its name, with
            -- its version if it is an Approved one.
            WITH which AS (
                SELECT c.id, concat_ws(' ', v.code, l.model_year, owner.username, c.name,
                                       'v' || c.version_number) AS catalog
                FROM catalog c
                JOIN lineage l ON l.id = c.lineage_id
                JOIN vehicle_line v ON v.id = l.vehicle_line_id
                JOIN app_user owner ON owner.id = c.owner_id
            )
            SELECT 'vehicle line ' || concat_ws('|', code, name, vehicle_type_code, active)
            FROM vehicle_line
            UNION ALL
            SELECT 'trim ' || concat_ws('|', name, sort_order, active) FROM trim
            UNION ALL
            SELECT 'region ' || concat_ws('|', code, name, sort_order, active) FROM region
            UNION ALL
            SELECT 'feature ' || concat_ws('|', code, name, description, category_code, kind,
                                           status, version)
            FROM feature
            UNION ALL
            SELECT 'catalog ' || concat_ws('|', v.code, l.model_year, c.name, c.status,
                       c.version_number, c.revision, owner.username, approver.username,
                       base.version_number, l.current_catalog_id = c.id)
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            JOIN vehicle_line v ON v.id = l.vehicle_line_id
            JOIN app_user owner ON owner.id = c.owner_id
            LEFT JOIN app_user approver ON approver.id = c.approved_by
            LEFT JOIN catalog base ON base.id = c.base_catalog_id
            UNION ALL
            SELECT 'catalog trim ' || concat_ws('|', which.catalog, t.name, ct.approved_name,
                       ct.approved_sort_order)
            FROM catalog_trim ct
            JOIN which ON which.id = ct.catalog_id
            JOIN trim t ON t.id = ct.trim_id
            UNION ALL
            SELECT 'catalog region ' || concat_ws('|', which.catalog, cr.region_code,
                       cr.approved_name)
            FROM catalog_region cr
            JOIN which ON which.id = cr.catalog_id
            UNION ALL
            SELECT 'offering ' || concat_ws('|', which.catalog, t.name, o.region_code)
            FROM catalog_trim_region o
            JOIN which ON which.id = o.catalog_id
            JOIN trim t ON t.id = o.trim_id
            UNION ALL
            SELECT 'feature row ' || concat_ws('|', which.catalog, f.code, cf.approved_name,
                       cf.approved_category_code)
            FROM catalog_feature cf
            JOIN which ON which.id = cf.catalog_id
            JOIN feature f ON f.id = cf.feature_id
            UNION ALL
            SELECT 'cell ' || concat_ws('|', which.catalog, f.code, t.name, cc.region_code,
                       cc.availability)
            FROM catalog_cell cc
            JOIN which ON which.id = cc.catalog_id
            JOIN feature f ON f.id = cc.feature_id
            JOIN trim t ON t.id = cc.trim_id
            ORDER BY 1
            """)
        .query(String.class)
        .list();
  }
}
