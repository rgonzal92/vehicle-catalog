package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.EvaluationReport.Result;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The questions the analyst is asked about uploaded documents to measure the models, each asked
 * with the documents of one vehicle line's model year chosen, and each with the documents its
 * answer has to cite and the ones it must not. The documents are notes kept with the cases, about
 * two model years of one vehicle line and about another vehicle line. Nothing is judged by a model:
 * an answer's citations are compared with what the case names.
 */
final class DocumentCases {
  static final String PASSES_WHEN =
      "A case passes when the answer cites every document the case expects and none it forbids,"
          + " such as the note of another model year, and when the tools it names were called. A"
          + " case that expects no document passes only when nothing is cited: that is a question"
          + " the chosen documents do not cover, or one about what a catalog offers, which is"
          + " looked up and not read from a note. Nothing is judged by a model.";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private DocumentCases() {}

  /**
   * A note that is uploaded for the cases.
   *
   * @param file its file, among the evaluation's documents
   * @param vehicleLine the code of the vehicle line it is about
   */
  record Note(String title, String file, String vehicleLine, int modelYear) {

    /** The note's file, as it is uploaded. */
    byte[] bytes() {
      try (var note = DocumentCases.class.getResourceAsStream("/evaluation/documents/" + file)) {
        return note.readAllBytes();
      } catch (IOException unreadable) {
        throw new UncheckedIOException(unreadable);
      }
    }
  }

  /**
   * One question and what its answer has to cite.
   *
   * @param heldOut whether it is one of those that are not looked at while a prompt is changed
   * @param vehicleLine the code of the vehicle line whose documents are chosen for it
   * @param cites the titles of the documents the answer has to cite; none means that it has to cite
   *     nothing
   * @param mustNotCite the titles of documents it must not cite, whatever else it does
   * @param tools the tools that have to be called, whatever else is
   */
  record Case(
      String id,
      boolean heldOut,
      String vehicleLine,
      int modelYear,
      String question,
      List<String> cites,
      List<String> mustNotCite,
      List<String> tools) {}

  /** The notes and the cases, as the repository keeps them. */
  record Kept(List<Note> documents, List<Case> cases) {}

  static Kept all() {
    try (var cases = DocumentCases.class.getResourceAsStream("/evaluation/documents.json")) {
      return JSON.readValue(cases, Kept.class);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /**
   * Holds the analyst's answer to a case's question against what the case requires.
   *
   * @param answered what the application answered with: the answer, its tool calls, and its
   *     citations, or why there is none
   */
  static Result judge(Case one, JsonNode answered) {
    var cited = new ArrayList<String>();
    answered
        .path("citations")
        .forEach(
            citation -> {
              if (!cited.contains(citation.path("title").asString())) {
                cited.add(citation.path("title").asString());
              }
            });
    var called = new ArrayList<String>();
    answered.path("toolCalls").forEach(call -> called.add(call.path("tool").asString()));

    var passed =
        answered.path("answer").isString()
            && cited.containsAll(one.cites())
            && (one.cites().isEmpty()
                ? cited.isEmpty()
                : cited.stream().noneMatch(one.mustNotCite()::contains))
            && called.containsAll(one.tools());

    return new Result(
        one.id(),
        one.heldOut(),
        passed,
        "\"%s\", of %s %d".formatted(one.question(), one.vehicleLine(), one.modelYear()),
        "cites %s; calls %s"
            .formatted(
                one.cites().isEmpty() ? "nothing" : String.join(", ", one.cites()),
                one.tools().isEmpty() ? "whatever it calls" : String.join(", ", one.tools())),
        answered.path("answer").isString()
            ? "cited %s; called %s; answered: %s"
                .formatted(
                    cited.isEmpty() ? "nothing" : String.join(", ", cited),
                    called.isEmpty() ? "nothing" : String.join(", ", called),
                    answered.path("answer").asString().replaceAll("\\s+", " ").strip())
            : "no answer: %s"
                .formatted(answered.path("detail").asString("nothing was said of why")));
  }
}
