package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import dev.rgonz.catalog.catalog.Revisions.Change;
import dev.rgonz.catalog.catalog.Revisions.Edit;
import dev.rgonz.catalog.core.ApiException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Checks the one place every change of a working copy goes through: the order of its checks, that
 * an edit that makes no change leaves the revision and the history as they were, and that one that
 * makes changes records them in their order and raises the revision by exactly one. Each test
 * starts from the seeded catalogs and a working copy of Compact SUV 2026 that Ana owns, at revision
 * 0.
 */
class RevisionsIT extends WorkingCopyTests {
  @Autowired Revisions revisions;
  @Autowired ChangeHistory history;

  private long copy;
  private long ana;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    ana = person("ana");
  }

  @Test
  void anEditIsRefusedForWhoseItIsThenForItsStatusThenForItsRevisionAndIsNeverMade() {
    var made = new AtomicInteger();
    Edit edit =
        () -> {
          made.incrementAndGet();
          return List.of(Change.renamed("Compact SUV 2026", "Renamed"));
        };

    assertThat(refusalOf(() -> revisions.edit(copy, person("ben"), "\"7\"", edit)))
        .as("someone else's, Submitted, from another revision")
        .isEqualTo("NOT_FOUND");
    status("SUBMITTED");
    assertThat(refusalOf(() -> revisions.edit(copy, ana, "\"7\"", edit)))
        .as("hers, Submitted, from another revision")
        .isEqualTo("NOT_DRAFT");
    status("DRAFT");
    assertThat(refusalOf(() -> revisions.edit(copy, ana, "\"7\"", edit)))
        .as("hers, a Draft, from another revision")
        .isEqualTo("REVISION_CONFLICT");
    assertThat(refusalOf(() -> revisions.edit(copy, ana, null, edit)))
        .as("hers, a Draft, naming no revision")
        .isEqualTo("REVISION_REQUIRED");

    assertThat(made).as("edits made").hasValue(0);
    assertThat(revision(copy)).isZero();
    assertThat(history.page(copy, 0, 50).total()).isZero();
  }

  @Test
  void anEditThatMakesNoChangeLeavesTheRevisionAndTheHistory() {
    var outcome = revisions.edit(copy, ana, "\"0\"", List::of);

    assertThat(outcome.revision()).isZero();
    assertThat(revision(copy)).isZero();
    assertThat(history.page(copy, 0, 50).total()).isZero();
  }

  @Test
  void anEditThatMakesChangesRecordsThemInTheirOrderAndRaisesTheRevisionByOne() {
    var outcome =
        revisions.edit(
            copy,
            ana,
            "\"0\"",
            () ->
                List.of(
                    Change.renamed("Compact SUV 2026", "Renamed"),
                    Change.cellSet(feature("TRANS_MANUAL"), trim("Base"), "NA", "N", "A"),
                    Change.ruleRemoved("Leather Seats requires Premium Audio")));

    assertThat(outcome.revision()).isOne();
    assertThat(revision(copy)).isOne();
    assertThat(history.page(copy, 0, 50).items().reversed())
        .as("oldest first")
        .extracting(
            ChangeHistory.Change::kind,
            ChangeHistory.Change::featureCode,
            ChangeHistory.Change::trim,
            ChangeHistory.Change::region,
            ChangeHistory.Change::oldValue,
            ChangeHistory.Change::newValue)
        .containsExactly(
            tuple("RENAMED", null, null, null, "Compact SUV 2026", "Renamed"),
            tuple("CELL_SET", "TRANS_MANUAL", "Base", "North America", "N", "A"),
            tuple("RULE_REMOVED", null, null, null, "Leather Seats requires Premium Audio", null));
  }

  private void status(String status) {
    jdbc.sql("UPDATE catalog SET status = :status WHERE id = :id")
        .param("status", status)
        .param("id", copy)
        .update();
  }

  private static Object refusalOf(ThrowingCallable write) {
    return catchThrowableOfType(ApiException.class, write).getBody().getProperties().get("code");
  }
}
