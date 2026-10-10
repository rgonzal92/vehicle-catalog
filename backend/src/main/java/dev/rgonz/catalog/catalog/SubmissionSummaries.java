package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.ai.Model;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.job.JobHandler;
import dev.rgonz.catalog.job.JobType;
import dev.rgonz.catalog.job.Jobs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The summaries of submitted catalogs: a few lines the language model writes of what a catalog
 * changes against its base, for its reviewer to read beside the changes themselves. A summary is a
 * help in reading and never the record. The changes are computed here and given to the model, and a
 * summary that names a feature or a trim the changes do not hold is thrown away.
 *
 * <p>A submit expects a summary and leaves a job for it. The worker writes it, once: the model is
 * asked a single time, and when it cannot be asked or does not answer, the summary is unavailable
 * and says why.
 */
@Component
class SubmissionSummaries implements JobHandler {
  /** The names a job of this type gives the catalog it is about and the revision it was at. */
  static final String CATALOG = "catalogId";

  static final String REVISION = "revision";

  private static final int LONGEST_HEADLINE = 120;
  private static final int MOST_BULLETS = 6;

  /** The most the model may say: a headline and six bullets fit well within. */
  private static final int MOST_OUTPUT_TOKENS = 400;

  /** Changes larger than this are not sent whole. */
  private static final int LARGEST_CHANGES_BYTES = 20_000;

  private static final int MOST_CELLS_SENT = 200;

  private static final String INSTRUCTIONS =
      """
      You summarise, for a reviewer, what a vehicle catalog changes against the version it started \
      from.

      You are given JSON with the changes, computed by the application: trims, regions, offerings, \
      and feature rows that were added or removed, cells whose availability changed (S is \
      Standard, A is Available, N is Not offered), and rules that were added, removed, or changed. \
      When there were too many changes to give in full, counts says how many there are of each \
      kind and cellsChanged holds only the first of them.

      Answer with a headline of at most 120 characters and at most six bullets, most important \
      first. Say only what the changes hold. Name a feature or a trim only when it is in the \
      changes, by the name it has there. Give no advice and no opinion.
      """;

  private static final String ANSWER =
      """
      {
        "type": "object",
        "additionalProperties": false,
        "required": ["headline", "bullets"],
        "properties": {
          "headline": {"type": "string"},
          "bullets": {"type": "array", "items": {"type": "string"}}
        }
      }
      """;

  private final JdbcClient jdbc;
  private final Jobs jobs;
  private final Catalogs catalogs;
  private final Model model;
  private final JsonMapper json;

  SubmissionSummaries(JdbcClient jdbc, Jobs jobs, Catalogs catalogs, Model model, JsonMapper json) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.catalogs = catalogs;
    this.model = model;
    this.json = json;
  }

  /**
   * Expects a summary of the catalog as it was submitted at the revision, and leaves the job that
   * writes it. It is called in the submit's own transaction.
   */
  void expect(long catalogId, long revision) {
    jdbc.sql(
            """
            INSERT INTO submission_summary (catalog_id, revision) VALUES (:catalog, :revision)
            ON CONFLICT DO NOTHING
            """)
        .param("catalog", catalogId)
        .param("revision", revision)
        .update();
    jobs.queue(
        JobType.SUMMARISE_SUBMISSION,
        "summary:%d:%d".formatted(catalogId, revision),
        Map.of(CATALOG, catalogId, REVISION, revision));
  }

  /** The summary of the catalog as it was submitted at the revision, if one was expected. */
  Optional<Summary> find(long catalogId, long revision) {
    return jdbc.sql(
            """
            SELECT status, headline, bullets::text AS bullets, reason
            FROM submission_summary
            WHERE catalog_id = :catalog AND revision = :revision
            """)
        .param("catalog", catalogId)
        .param("revision", revision)
        .query(
            (row, number) ->
                new Summary(
                    row.getString("status"),
                    row.getString("headline"),
                    row.getString("bullets") == null
                        ? List.of()
                        : texts(json.readTree(row.getString("bullets"))),
                    row.getString("reason")))
        .optional();
  }

  @Override
  public JobType type() {
    return JobType.SUMMARISE_SUBMISSION;
  }

  /**
   * Writes the summary that is pending, by asking the model once. A summary that is no longer
   * pending is left as it is, so a message that is delivered again asks the model for nothing.
   */
  @Override
  public void handle(JsonNode subject) {
    long catalogId = subject.required(CATALOG).asLong();
    long revision = subject.required(REVISION).asLong();
    boolean pending =
        jdbc.sql(
                    """
                SELECT count(*) FROM submission_summary
                WHERE catalog_id = :catalog AND revision = :revision AND status = 'PENDING'
                """)
                .param("catalog", catalogId)
                .param("revision", revision)
                .query(Long.class)
                .single()
            == 1;
    if (!pending) {
      return;
    }
    var owner =
        jdbc.sql("SELECT owner_id FROM catalog WHERE id = :id")
            .param("id", catalogId)
            .query(Long.class)
            .single();
    var catalog = catalogs.find(catalogId, owner).orElseThrow();
    if (catalog.snapshot().status() != Status.SUBMITTED
        || catalog.snapshot().revision() != revision) {
      unavailable(catalogId, revision, "The catalog is no longer as it was submitted.");
      return;
    }
    var before =
        catalog.base() == null
            ? CatalogSnapshot.empty()
            : catalogs.contents(catalog.base().catalogId(), owner, true).orElseThrow();
    var changes = sendable(json.valueToTree(Diff.between(before, catalog.snapshot())));

    String said;
    try {
      said =
          model.ask(
              new Model.Question(
                  null, "SUBMISSION_SUMMARY", INSTRUCTIONS, changes, ANSWER, MOST_OUTPUT_TOKENS));
    } catch (ApiException refused) {
      unavailable(catalogId, revision, refused.getBody().getDetail());
      return;
    }

    JsonNode answer;
    try {
      answer = json.readTree(said);
    } catch (JacksonException unreadable) {
      answer = json.nullNode();
    }
    var headline = answer.path("headline").asString("").strip();
    var bullets = texts(answer.path("bullets"));
    if (headline.isEmpty() || !answer.path("bullets").isArray()) {
      unavailable(catalogId, revision, "The model's answer was not a summary.");
      return;
    }
    if (namesWhatTheChangesDoNotHold(headline + "\n" + String.join("\n", bullets), changes)) {
      unavailable(
          catalogId,
          revision,
          "The summary named a feature or a trim that the changes do not hold, so it was thrown"
              + " away.");
      return;
    }
    jdbc.sql(
            """
            UPDATE submission_summary
            SET status = 'READY', headline = :headline, bullets = :bullets::jsonb,
                updated_at = now()
            WHERE catalog_id = :catalog AND revision = :revision
            """)
        .param("headline", headline.substring(0, Math.min(headline.length(), LONGEST_HEADLINE)))
        .param(
            "bullets",
            json.writeValueAsString(bullets.subList(0, Math.min(bullets.size(), MOST_BULLETS))))
        .param("catalog", catalogId)
        .param("revision", revision)
        .update();
  }

  /**
   * The changes as they are sent to the model: whole when they are small, and otherwise with how
   * many there are of each kind and only the first of the changed cells.
   */
  private JsonNode sendable(JsonNode changes) {
    if (json.writeValueAsString(changes).getBytes(StandardCharsets.UTF_8).length
        <= LARGEST_CHANGES_BYTES) {
      return changes;
    }
    var cut = (ObjectNode) changes.deepCopy();
    var counts = cut.putObject("counts");
    changes.properties().forEach(kind -> counts.put(kind.getKey(), kind.getValue().size()));
    var cells = cut.putArray("cellsChanged");
    changes.path("cellsChanged").valueStream().limit(MOST_CELLS_SENT).forEach(cells::add);
    return cut;
  }

  /**
   * Whether the text names a feature of the library, by its code or its name, or a trim, that the
   * changes given to the model do not hold.
   */
  private boolean namesWhatTheChangesDoNotHold(String text, JsonNode changes) {
    var given = json.writeValueAsString(changes);
    var known = new ArrayList<String>();
    known.addAll(jdbc.sql("SELECT code FROM feature").query(String.class).list());
    known.addAll(jdbc.sql("SELECT name FROM feature").query(String.class).list());
    known.addAll(jdbc.sql("SELECT name FROM trim").query(String.class).list());

    return known.stream().anyMatch(name -> mentions(text, name) && !mentions(given, name));
  }

  /** Whether the text has the name as words of its own, and not as part of a longer word. */
  private static boolean mentions(String text, String name) {
    return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_])")
        .matcher(text)
        .find();
  }

  private void unavailable(long catalogId, long revision, String reason) {
    jdbc.sql(
            """
            UPDATE submission_summary
            SET status = 'UNAVAILABLE', reason = :reason, updated_at = now()
            WHERE catalog_id = :catalog AND revision = :revision
            """)
        .param("reason", reason)
        .param("catalog", catalogId)
        .param("revision", revision)
        .update();
  }

  /** The texts of a list, in order. What is no list holds none. */
  private static List<String> texts(JsonNode list) {
    var texts = new ArrayList<String>();
    if (list.isArray()) {
      list.forEach(
          one -> {
            if (one.isString() && !one.asString().isBlank()) {
              texts.add(one.asString().strip());
            }
          });
    }
    return texts;
  }

  /**
   * A summary as the review screen shows it.
   *
   * @param status PENDING while it is being written, READY, or UNAVAILABLE
   * @param reason why there is none, when it is unavailable
   */
  record Summary(String status, String headline, List<String> bullets, String reason) {}
}
