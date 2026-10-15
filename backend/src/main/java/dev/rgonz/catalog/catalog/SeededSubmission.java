package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogEdits.CellChange;
import dev.rgonz.catalog.catalog.CatalogRules.RuleContent;
import dev.rgonz.catalog.catalog.WorkingCopies.NewWorkingCopy;
import dev.rgonz.catalog.library.RuleKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the catalog the demo starts with in its review queue, so that someone who signs in as the
 * demo manager finds something to read, approve, or reject. It is a working copy of a seeded
 * lineage that the demo author owns, with the changes and the note that {@code
 * seed/submitted-catalog.json} lists: feature rows added, cells set, and rules of its own. It is
 * made as its owner would make it, by what the app does for a person, so it has its change history,
 * its revision, and the job that has the worker write its summary.
 *
 * <p>The seed of the catalogs makes it together with the Approved versions and at no other time. So
 * it is there after a first start, each demo reset puts it back whatever was done to it, and a
 * restart does not make it again once it was approved, rejected, or deleted. {@code
 * app.submission.seeded=false} turns it off.
 */
@Component
class SeededSubmission {
  private static final String FILE = "seed/submitted-catalog.json";

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final WorkingCopies workingCopies;
  private final CatalogEdits edits;
  private final Submissions submissions;
  private final boolean wanted;

  SeededSubmission(
      JdbcClient jdbc,
      JsonMapper json,
      WorkingCopies workingCopies,
      CatalogEdits edits,
      Submissions submissions,
      @Value("${app.submission.seeded}") boolean wanted) {
    this.jdbc = jdbc;
    this.json = json;
    this.workingCopies = workingCopies;
    this.edits = edits;
    this.submissions = submissions;
    this.wanted = wanted;
  }

  /**
   * Makes the catalog for its owner and submits it, in the transaction that seeds the catalogs. A
   * file that the app refuses, as it would refuse a person, fails the whole seed.
   */
  void add(long owner) {
    if (!wanted) {
      return;
    }
    var seeded = read();
    try {
      var copy =
          workingCopies.create(
              owner,
              new NewWorkingCopy(
                  seeded.name(),
                  id("vehicle_line", "code", seeded.vehicleLine()),
                  seeded.modelYear()));
      long revision =
          edits
              .addFeatures(
                  copy.id(),
                  owner,
                  at(copy.revision()),
                  seeded.featureRows().stream().map(this::feature).toList())
              .revision();
      revision =
          edits
              .setCells(
                  copy.id(),
                  owner,
                  at(revision),
                  seeded.cells().stream()
                      .map(
                          cell ->
                              new CellChange(
                                  feature(cell.feature()),
                                  id("trim", "name", cell.trim()),
                                  cell.region(),
                                  cell.availability()))
                      .toList())
              .revision();
      for (var rule : seeded.rules()) {
        revision =
            edits
                .addRule(
                    copy.id(),
                    owner,
                    at(revision),
                    new RuleContent(
                        rule.kind(),
                        feature(rule.source()),
                        rule.targets().stream().map(this::feature).toList(),
                        true,
                        List.of(),
                        true,
                        List.of()))
                .revision();
      }
      submissions.submit(copy.id(), owner, at(revision), seeded.note());
    } catch (RuntimeException refused) {
      throw new IllegalStateException(FILE + " could not be loaded", refused);
    }
  }

  private Submitted read() {
    try (var content = new ClassPathResource(FILE).getInputStream()) {
      return json.readValue(content, Submitted.class);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  /** A revision as an edit names it. */
  private static String at(long revision) {
    return "\"" + revision + "\"";
  }

  private long feature(String code) {
    return id("feature", "code", code);
  }

  /** The id of the library entry that the file names. */
  private long id(String table, String column, String named) {
    return jdbc.sql("SELECT id FROM %s WHERE %s = :named".formatted(table, column))
        .param("named", named)
        .query(Long.class)
        .single();
  }

  /**
   * The catalog as the file writes it.
   *
   * @param vehicleLine the code of its vehicle line, which with the model year names a lineage that
   *     has a seeded Approved version
   * @param note what its owner says to the reviewer
   * @param featureRows the codes of the features it adds as rows
   * @param rules the rules it adds, each of which covers every trim and every region
   */
  private record Submitted(
      String vehicleLine,
      int modelYear,
      String name,
      String note,
      List<String> featureRows,
      List<Cell> cells,
      List<Rule> rules) {}

  /**
   * A cell it sets.
   *
   * @param feature the feature's code
   * @param trim the trim's name
   * @param region the region's code
   * @param availability S for Standard, A for Available, or N for Not offered
   */
  private record Cell(String feature, String trim, String region, String availability) {}

  /** A rule it adds, with its features by their codes. */
  private record Rule(RuleKind kind, String source, List<String> targets) {}
}
