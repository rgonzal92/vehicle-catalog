package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.catalog.EvaluationReport.ACase;
import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import dev.rgonz.catalog.catalog.EvaluationReport.Suite;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a case the model is measured with through the application, as a person or a job would ask
 * for it, and holds what came against what the case expects. Whether the model that answers is the
 * real one or a stand-in is for what extends this to say.
 */
abstract class EvaluationRuns extends WorkingCopyTests {
  protected static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired protected ApplicationContext application;

  private static JsonNode body(MvcTestResult answered) {
    try {
      return JSON.readTree(answered.getResponse().getContentAsString());
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private String rev(long catalog) {
    return "\"" + revision(catalog) + "\"";
  }

  /** A rule suggested from the case's sentence for a working copy of Ana's. */
  protected Result suggested(long copy, RuleSuggestionCases.Case one) {
    return RuleSuggestionCases.run(
        one,
        sentence ->
            body(
                edit(
                    ana(),
                    mvc.post().uri("/api/catalogs/{id}/rule-suggestions", copy),
                    null,
                    JSON.writeValueAsString(Map.of("sentence", sentence)))),
        id ->
            jdbc.sql("SELECT code FROM feature WHERE id = ?")
                .param(id)
                .query(String.class)
                .single(),
        id ->
            jdbc.sql("SELECT name FROM trim WHERE id = ?").param(id).query(String.class).single());
  }

  /**
   * A summary of the case's changes: they are made to a working copy of the seeded Compact SUV
   * 2026, which is submitted, and the worker has the summary written. It starts from the seed.
   */
  protected Result summarised(SummaryCases.Case one) throws Exception {
    seedLibraryAndCatalogs();
    Worker.forgets(application);
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    if (!one.cells().isEmpty()) {
      assertThat(
              setCells(
                  ana(),
                  copy,
                  rev(copy),
                  one.cells().stream()
                      .map(
                          cell ->
                              cell(cell.feature(), cell.trim(), cell.region(), cell.availability()))
                      .toArray(String[]::new)))
          .as("the cells of %s", one.id())
          .hasStatusOk();
    }
    for (var rule : one.rules()) {
      var content = new LinkedHashMap<String, Object>();
      content.put("kind", rule.kind());
      content.put("sourceFeatureId", feature(rule.source()));
      content.put("targetFeatureIds", rule.targets().stream().map(this::feature).toList());
      content.put("allTrims", rule.trims() == null);
      content.put(
          "trimIds",
          rule.trims() == null ? List.of() : rule.trims().stream().map(this::trim).toList());
      content.put("allRegions", rule.regions() == null);
      content.put("regionCodes", rule.regions() == null ? List.of() : rule.regions());
      assertThat(
              edit(
                  ana(),
                  mvc.post().uri("/api/catalogs/{id}/rules", copy),
                  rev(copy),
                  JSON.writeValueAsString(content)))
          .as("a rule of %s", one.id())
          .hasStatus2xxSuccessful();
    }
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), rev(copy), "{}"))
        .as("the submit of %s", one.id())
        .hasStatusOk();
    Worker.runs(application);

    return SummaryCases.judge(
        one,
        body(
            mvc.get()
                .uri("/api/catalogs/{id}/summary", copy)
                .with(signedInAs(Role.MANAGER, "mia"))
                .exchange()));
  }

  /**
   * A working copy of Ana's of the seeded Compact SUV 2026 that has a trim no Approved catalog has,
   * for the questions that ask for what a working copy holds.
   */
  protected long workingCopyWithATrimOfItsOwn() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(
            edit(
                ana(),
                mvc.post().uri("/api/catalogs/{id}/trims", copy),
                rev(copy),
                "{\"trimIds\": [%d]}".formatted(trim(AnalystCases.TRIM_OF_THE_WORKING_COPY))))
        .hasStatusOk();
    return copy;
  }

  /**
   * Uploads the notes the questions about documents are asked of, and has the worker make each
   * ready to be searched. It starts from no documents.
   */
  protected void theNotesAreReady() {
    jdbc.sql("DELETE FROM document").update();
    documentFiles().forEach(ApplicationIT::removeDocumentFile);
    for (var note : DocumentCases.all().documents()) {
      assertThat(
              mvc.post()
                  .uri("/api/documents")
                  .multipart()
                  .file(new MockMultipartFile("file", note.file(), null, note.bytes()))
                  .param("title", note.title())
                  .param("vehicleLineId", String.valueOf(line(note.vehicleLine())))
                  .param("modelYear", String.valueOf(note.modelYear()))
                  .with(csrfToken())
                  .with(signedInAs(Role.ADMIN, "ada")))
          .as("the upload of %s", note.file())
          .hasStatus(201);
    }
    // The worker takes ten jobs a turn, and the runs before this one can have left jobs of their
    // own waiting ahead of the notes'.
    for (int turn = 0; turn < 5 && count("document WHERE status <> 'READY'") > 0; turn++) {
      Worker.runs(application);
    }
    assertThat(count("document WHERE status <> 'READY'")).as("notes that are not ready").isZero();
  }

  /** The analyst's answer to a question about the notes, asked by Ana with its notes chosen. */
  protected Result answeredFromDocuments(DocumentCases.Case one) {
    return DocumentCases.judge(
        one,
        body(
            edit(
                ana(),
                mvc.post().uri("/api/analyst"),
                null,
                JSON.writeValueAsString(
                    Map.of(
                        "turns",
                        List.of(Map.of("by", "PERSON", "text", one.question())),
                        "documentsOf",
                        Map.of(
                            "vehicleLineId",
                            line(one.vehicleLine()),
                            "modelYear",
                            one.modelYear()))))));
  }

  /**
   * Asks chosen cases several times each, as a whole run asks them once. Every asking is a result
   * of its own in its case's set, and a set none of whose cases is asked is left out.
   *
   * @param named the ids of cases that are worked on, and for every such case of a set its name:
   *     {@code rule-suggestions}, {@code analyst}, or {@code documents}
   * @throws IllegalArgumentException before anything is asked, when a name is a held-out case's or
   *     is no case's and no set's
   */
  protected List<Suite> askedOften(List<String> named, int times) throws Exception {
    var notFound = new LinkedHashSet<>(named);
    var sentences = chosen("rule-suggestions", RuleSuggestionCases.all(), named, notFound);
    var questions = chosen("analyst", AnalystCases.all(), named, notFound);
    var aboutDocuments = chosen("documents", DocumentCases.all().cases(), named, notFound);
    if (!notFound.isEmpty()) {
      throw new IllegalArgumentException(
          ("%s cannot be asked often. What can is a case of the rule suggestions, of the analyst,"
                  + " or of the documents by its id, and every worked-on case of one of them as"
                  + " rule-suggestions, analyst, or documents. The summaries are asked once, in a"
                  + " whole run.")
              .formatted(String.join(", ", notFound)));
    }

    var suites = new ArrayList<Suite>();
    if (!sentences.isEmpty()) {
      seedLibraryAndCatalogs();
      long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
      suites.add(
          new Suite(
              "Rule suggestions",
              RuleSuggestionCases.PASSES_WHEN,
              each(times, sentences, one -> suggested(copy, one))));
    }
    if (!questions.isEmpty()) {
      seedLibraryAndCatalogs();
      long copy = workingCopyWithATrimOfItsOwn();
      suites.add(
          new Suite(
              "The analyst",
              AnalystCases.PASSES_WHEN,
              each(times, questions, one -> answered(one, copy))));
    }
    if (!aboutDocuments.isEmpty()) {
      seedLibraryAndCatalogs();
      theNotesAreReady();
      suites.add(
          new Suite(
              "Answers from documents",
              DocumentCases.PASSES_WHEN,
              each(times, aboutDocuments, this::answeredFromDocuments)));
    }
    return suites;
  }

  /**
   * The cases of a set that are named, by their ids or all that are worked on by the set's name,
   * with what was found taken from the names not found yet.
   */
  private static <C extends ACase> List<C> chosen(
      String set, List<C> cases, List<String> named, Set<String> notFound) {
    var chosen = new ArrayList<C>();
    for (var one : cases) {
      if (named.contains(one.id()) && one.heldOut()) {
        throw new IllegalArgumentException(
            ("%s is held out, and a held-out case is not asked often: those cases are looked at"
                    + " in a whole run and not while a prompt is changed.")
                .formatted(one.id()));
      }
      if (named.contains(one.id()) || named.contains(set) && !one.heldOut()) {
        chosen.add(one);
        notFound.remove(one.id());
      }
    }
    notFound.remove(set);
    return chosen;
  }

  private static <C> List<Result> each(int times, List<C> cases, Function<C, Result> asks) {
    var results = new ArrayList<Result>();
    for (var one : cases) {
      for (int time = 0; time < times; time++) {
        results.add(asks.apply(one));
      }
    }
    return results;
  }

  /** The analyst's answer to the case's question, asked by Ana, who owns the working copy. */
  protected Result answered(AnalystCases.Case one, long copy) {
    return AnalystCases.judge(
        one,
        copy,
        body(
            edit(
                ana(),
                mvc.post().uri("/api/analyst"),
                null,
                JSON.writeValueAsString(
                    Map.of("turns", List.of(Map.of("by", "PERSON", "text", one.asked(copy))))))));
  }
}
