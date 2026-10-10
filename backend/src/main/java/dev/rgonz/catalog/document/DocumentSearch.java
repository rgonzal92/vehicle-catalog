package dev.rgonz.catalog.document;

import dev.rgonz.catalog.ai.Model;
import dev.rgonz.catalog.ai.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Finds the passages of uploaded documents that are about what is asked: the ones whose meaning is
 * closest to the meaning of the words searched for. A search is of the documents of one vehicle
 * line's model year, which whoever starts it says and the model that words the search cannot
 * change. Only documents that are ready are searched.
 */
@Component
public class DocumentSearch {
  /** The most passages one search answers with. */
  static final int MOST_PASSAGES = 6;

  private final JdbcClient jdbc;
  private final Model model;
  private final double leastLikeness;

  DocumentSearch(
      JdbcClient jdbc,
      Model model,
      @Value("${app.documents.least-likeness}") double leastLikeness) {
    this.jdbc = jdbc;
    this.model = model;
    this.leastLikeness = leastLikeness;
  }

  /**
   * A vehicle line's model year that has documents to search.
   *
   * @param documents how many of its documents are ready
   */
  public record Subject(long vehicleLineId, String vehicleLine, int modelYear, int documents) {}

  /**
   * A passage a search found.
   *
   * @param number what the passage is called by in the answer it was found for
   * @param title its document's title
   */
  public record Found(int number, long documentId, String title, String passage) {}

  /** Every vehicle line's model year that has a document ready to be searched. */
  public List<Subject> subjects() {
    return jdbc.sql(
            """
            SELECT d.vehicle_line_id, v.name AS vehicle_line, d.model_year, count(*) AS documents
            FROM document d
            JOIN vehicle_line v ON v.id = d.vehicle_line_id
            WHERE d.status = 'READY'
            GROUP BY d.vehicle_line_id, v.name, d.model_year
            ORDER BY v.name, d.model_year
            """)
        .query(Subject.class)
        .list();
  }

  /** A search of the documents of one vehicle line's model year, for the tools of one answer. */
  public Search of(long vehicleLineId, int modelYear) {
    return new Search(vehicleLineId, modelYear);
  }

  /**
   * The tool the model searches the documents with while it answers one question, and what it was
   * given by it: the passages an answer may cite.
   */
  public final class Search implements Tool {
    private final long vehicleLineId;
    private final int modelYear;
    private final List<Found> returned = new ArrayList<>();

    private Search(long vehicleLineId, int modelYear) {
      this.vehicleLineId = vehicleLineId;
      this.modelYear = modelYear;
    }

    @Override
    public String name() {
      return "search_documents";
    }

    @Override
    public String description() {
      return "Searches the notes uploaded about the vehicle line and model year the person chose,"
          + " and answers with the passages closest in meaning to the query, each with a number"
          + " and its document's title. Use it for what the notes say: why something is as it"
          + " is, what is planned, what was decided. It does not say what a catalog offers.";
    }

    @Override
    public String arguments() {
      return """
          {
            "type": "object",
            "required": ["query"],
            "properties": {
              "query": {
                "type": "string",
                "description": "What to look for, in a few words or a sentence."
              }
            }
          }
          """;
    }

    /**
     * The passages closest to what is asked for, of ready documents of this vehicle line and model
     * year and of no other. A passage found before in this answer keeps its number.
     */
    @Override
    public Object answer(JsonNode asked, long accountId) {
      var query = asked.path("query").asString("").strip();
      if (query.isEmpty()) {
        return Map.of("error", "Say what to look for.");
      }
      var meaning =
          DocumentProcessing.asAVector(
              model.meaningsOf(accountId, "QUESTION_EMBEDDING", List.of(query)).getFirst());
      var closest =
          jdbc.sql(
                  """
                  SELECT d.id AS document_id, d.title, p.text
                  FROM document_passage p
                  JOIN document d ON d.id = p.document_id
                  WHERE d.status = 'READY'
                    AND d.vehicle_line_id = :line AND d.model_year = :year
                    AND 1 - (p.meaning <=> :meaning::vector) >= :least
                  ORDER BY p.meaning <=> :meaning::vector, d.id, p.position
                  LIMIT :most
                  """)
              .param("line", vehicleLineId)
              .param("year", modelYear)
              .param("meaning", meaning)
              .param("least", leastLikeness)
              .param("most", MOST_PASSAGES)
              .query(Passage.class)
              .list();
      if (closest.isEmpty()) {
        return new Answer(List.of(), "The documents have no passage about this.");
      }

      var passages = new ArrayList<Numbered>();
      for (var passage : closest) {
        var found =
            returned.stream()
                .filter(
                    one ->
                        one.documentId() == passage.documentId()
                            && one.passage().equals(passage.text()))
                .findFirst()
                .orElseGet(
                    () -> {
                      var next =
                          new Found(
                              returned.size() + 1,
                              passage.documentId(),
                              passage.title(),
                              passage.text());
                      returned.add(next);
                      return next;
                    });
        passages.add(new Numbered(found.number(), found.title(), found.passage()));
      }
      return new Answer(
          passages,
          "Mark what you take from a passage with its number in square brackets, as [1].");
    }

    /** The passages this search has answered with so far, by their numbers. */
    public List<Found> returned() {
      return List.copyOf(returned);
    }
  }

  private record Passage(long documentId, String title, String text) {}

  /** A passage as the model is given it. */
  private record Numbered(int number, String document, String text) {}

  /** What a search answers the model with. */
  private record Answer(List<Numbered> passages, String note) {}
}
