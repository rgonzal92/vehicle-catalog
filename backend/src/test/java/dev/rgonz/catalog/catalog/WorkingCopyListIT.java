package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks what a person's list of working copies costs and that the counts it shows are those that
 * opening each working copy shows. Each test starts from the seeded catalogs.
 */
class WorkingCopyListIT extends WorkingCopyTests {
  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
  }

  private RequestPostProcessor ada() {
    return signedInAs(Role.ADMIN, "ada");
  }

  @Test
  void aListOfTwentyWorkingCopiesWhoseCountsAreKeptIsAnsweredWithTwoStatements() {
    for (var number = 1; number <= 20; number++) {
      assertThat(create(ana(), "Sedan " + number, line("SEDAN"), 2027)).hasStatus(201);
    }

    var first = statementsOfTheList();
    var next = statementsOfTheList();

    // The caller looked up, and the list itself with each copy's kept counts and the library's
    // revision. No working copy is read, since each was counted at the revisions it and the
    // library are at.
    assertThat(next).hasSize(2);
    // The first ask after the copies were made reads each whole and keeps what it found: the ten
    // statements of a read, the library's revision read in the same view, and the counts written.
    assertThat(first).hasSize(2 + 20 * 12);
  }

  @Test
  void theListCountsAndCallsStaleWhatOpeningEachWorkingCopySays() {
    // After an edit that makes an Error and after one that removes it, the list is held to what
    // opening says by IssuesIT.myCatalogsCountsTheErrorsAndWarningsOfEachWorkingCopyAsTheyStand,
    // and after an approval makes a copy stale by StaleIT.
    var suv = workingCopy(ana(), "COMPACT_SUV", 2026);
    var sedan = workingCopy(ana(), "SEDAN", 2027);
    assertThat(listed()).as("counted when first listed").isEqualTo(opened(suv, sedan));
    assertThat(listed()).as("listed from what was kept").isEqualTo(opened(suv, sedan));

    assertThat(
            mvc.post()
                .uri("/api/features/{id}/retire", feature("FLOOR_CARPET_MATS"))
                .with(ada())
                .with(csrfToken()))
        .hasStatusOk();

    assertThat(opened(suv, sedan).get(suv)).as("the retired feature is an Error").startsWith("1,");
    assertThat(listed()).as("after a feature is retired").isEqualTo(opened(suv, sedan));
    assertThat(listed()).isEqualTo(opened(suv, sedan));
    var retired = opened(suv, sedan);

    // Adaptive cruise and the cargo cover are both Standard on a compact SUV, and on nothing else.
    assertThat(
            mvc.post()
                .uri("/api/global-rules")
                .with(ada())
                .with(csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"kind": "EXCLUDES", "sourceFeatureId": %d, "targetFeatureIds": [%d],
                     "allRegions": true, "regionCodes": []}
                    """
                        .formatted(feature("ADAS_ADAPTIVE_CRUISE"), feature("CARGO_COVER"))))
        .hasStatus(201);

    assertThat(opened(suv, sedan))
        .as("the broken rule adds Errors to the compact SUV alone")
        .doesNotContainEntry(suv, retired.get(suv))
        .containsEntry(sedan, retired.get(sedan));
    assertThat(listed()).as("after a global rule it breaks is added").isEqualTo(opened(suv, sedan));
    assertThat(listed()).isEqualTo(opened(suv, sedan));
  }

  /** The statements sent on this thread while Ana's list is asked for. */
  private List<String> statementsOfTheList() {
    // Ana is put on record before the counting begins, as she is once she has signed in.
    var ana = ana();
    var statements = new ListAppender<ILoggingEvent>();
    var logger = (Logger) LoggerFactory.getLogger(JdbcTemplate.class);
    var level = logger.getLevel();
    statements.start();
    logger.addAppender(statements);
    logger.setLevel(Level.DEBUG);

    try {
      assertThat(mvc.get().uri("/api/catalogs?scope=mine").with(ana)).hasStatusOk();
    } finally {
      logger.setLevel(level);
      logger.detachAppender(statements);
    }

    return statements.list.stream()
        // Other threads, such as the one that clears out expired sessions, log here too.
        .filter(event -> event.getThreadName().equals(Thread.currentThread().getName()))
        .map(ILoggingEvent::getFormattedMessage)
        .filter(message -> message.startsWith("Executing prepared SQL statement"))
        .toList();
  }

  /** Ana's working copies as her list has them: "errors, warnings, stale" by id. */
  private Map<Long, String> listed() {
    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(ana()).exchange();

    return ApplicationIT.<List<Map<String, Object>>>read(mine, "$").stream()
        .collect(
            Collectors.toMap(
                copy -> ((Number) copy.get("id")).longValue(),
                copy ->
                    "%s, %s, %s"
                        .formatted(copy.get("errors"), copy.get("warnings"), copy.get("stale"))));
  }

  /** The working copies as opening each has them: "errors, warnings, stale" by id. */
  private Map<Long, String> opened(long... copies) {
    var found = new HashMap<Long, String>();
    for (var copy : copies) {
      var opened = open(ana(), copy);
      List<String> severities = ApplicationIT.read(opened, "$.issues[*].severity");
      var errors = severities.stream().filter("ERROR"::equals).count();
      found.put(
          copy,
          "%d, %d, %s"
              .formatted(
                  errors,
                  severities.size() - errors,
                  ApplicationIT.<Boolean>read(opened, "$.stale")));
    }
    return found;
  }
}
