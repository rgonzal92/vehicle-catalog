package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
