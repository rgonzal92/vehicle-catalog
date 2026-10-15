package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks every write to a working copy through its address: one that changes something raises the
 * catalog's revision by exactly one and adds to its history, one that changes nothing leaves both
 * as they were, and each answers with the issues the catalog has at the revision the answer names,
 * and with whether it is stale then, as the writer may see the catalog. A delete is the one write
 * that leaves no catalog to number. Each test starts from the seeded catalogs and a working copy of
 * Compact SUV 2026 that Ana owns, with its rules taken away.
 */
class EveryWriteIT extends WorkingCopyTests {
  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    jdbc.sql("DELETE FROM catalog_rule WHERE catalog_id = :copy").param("copy", copy).update();
  }

  @Test
  void everyEditThatChangesSomethingRaisesTheRevisionByOneAndAnswersAsTheCatalogThenIs() {
    var edits = new LinkedHashMap<String, Write>();
    edits.put(
        "setting a cell", at -> setCells(ana(), copy, at, cell("TRANS_MANUAL", "Base", "NA", "A")));
    edits.put(
        "adding a trim", at -> post("/trims", at, "{\"trimIds\": [%d]}".formatted(trim("Luxury"))));
    edits.put("selling a trim in a region", at -> sellLuxuryIn(at, "NA"));
    edits.put("adding a region", at -> post("/regions", at, "{\"regionCodes\": [\"SA\"]}"));
    edits.put("removing a region", at -> delete("/regions/SA", at));
    edits.put("removing a trim", at -> delete("/trims/" + trim("Luxury"), at));
    edits.put(
        "adding a feature row",
        at -> post("/features", at, "{\"featureIds\": [%d]}".formatted(feature("ROOF_REMOVABLE"))));
    edits.put("removing a feature row", at -> delete("/features/" + feature("ROOF_REMOVABLE"), at));
    edits.put("renaming", at -> rename(at, "Renamed"));
    edits.put("adding a rule", at -> post("/rules", at, rule("AUDIO_PREMIUM")));
    edits.put("changing a rule", at -> put("/rules/" + ruleKey(), at, rule("ROOF_PANORAMIC")));
    edits.put("deleting a rule", at -> delete("/rules/" + ruleKey(), at));

    edits.forEach(
        (what, write) -> {
          var before = revision(copy);
          var entries = historyEntries(copy);

          var answer = write.send("\"%d\"".formatted(before));

          assertThat(answer).as(what).hasStatusOk();
          assertThat(revision(copy)).as("%s: the revision", what).isEqualTo(before + 1);
          assertThat(historyEntries(copy)).as("%s: the history", what).isGreaterThan(entries);
          assertAnswersAsTheCatalogIs(what, answer);
        });
  }

  @Test
  void everyEditThatChangesNothingLeavesTheRevisionAndTheHistoryAndAnswersAsTheCatalogIs() {
    post("/rules", "\"0\"", rule("AUDIO_PREMIUM"));
    post("/trims", "\"1\"", "{\"trimIds\": [%d]}".formatted(trim("Luxury")));
    sellLuxuryIn("\"2\"", "NA");
    setCells(ana(), copy, "\"3\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    var edits = new LinkedHashMap<String, Write>();
    edits.put(
        "setting a cell to what it is",
        at -> setCells(ana(), copy, at, cell("TRANS_MANUAL", "Base", "NA", "A")));
    edits.put("selling a trim where it is sold", at -> sellLuxuryIn(at, "NA"));
    edits.put("renaming to its name", at -> rename(at, "COMPACT_SUV 2026"));
    edits.put(
        "changing a rule to what it says",
        at -> put("/rules/" + ruleKey(), at, rule("AUDIO_PREMIUM")));

    edits.forEach(
        (what, write) -> {
          var before = revision(copy);
          var entries = historyEntries(copy);

          var answer = write.send("\"%d\"".formatted(before));

          assertThat(answer).as(what).hasStatusOk();
          assertThat(answer)
              .as(what)
              .bodyJson()
              .extractingPath("$.revision")
              .isEqualTo((int) before);
          assertThat(revision(copy)).as("%s: the revision", what).isEqualTo(before);
          assertThat(historyEntries(copy)).as("%s: the history", what).isEqualTo(entries);
          assertAnswersAsTheCatalogIs(what, answer);
        });
  }

  @Test
  void theOwnersOtherMovesEachRaiseTheRevisionByOneAndADeleteLeavesNoCatalog() {
    var moves = new LinkedHashMap<String, Write>();
    moves.put("submitting", at -> post("/submit", at, "{\"note\": \"Ready.\"}"));
    moves.put("withdrawing", at -> post("/withdraw", null, null));
    moves.put(
        "updating from Approved",
        at -> {
          approveAnotherVersion();
          return post("/merge", at, "{\"approvedCatalogId\": %d}".formatted(approvedSince));
        });

    moves.forEach(
        (what, write) -> {
          var before = revision(copy);
          var entries = historyEntries(copy);

          var answer = write.send("\"%d\"".formatted(before));

          assertThat(answer).as(what).hasStatusOk();
          assertThat(revision(copy)).as("%s: the revision", what).isEqualTo(before + 1);
          assertThat(historyEntries(copy)).as("%s: the history", what).isEqualTo(entries + 1);
          assertAnswersAsTheCatalogIs(what, answer);
        });

    assertThat(delete("", "\"%d\"".formatted(revision(copy)))).hasStatus(204);
    assertThat(open(ana(), copy)).as("deleted").hasStatus(404);
  }

  @Test
  void aDecisionRaisesTheRevisionByOneAndAnApprovalSendsTheOthersBackEachRaisedByOne() {
    var mia = signedInAs(Role.MANAGER, "mia");
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    assertThat(edit(ben(), mvc.post().uri("/api/catalogs/{id}/submit", bens), "\"0\"", "{}"))
        .hasStatusOk();
    assertThat(post("/submit", "\"0\"", "{}")).hasStatusOk();

    var before = revision(copy);
    var rejected =
        edit(
            mia,
            mvc.post().uri("/api/catalogs/{id}/reject", copy),
            "\"%d\"".formatted(before),
            "{\"comment\": \"Not yet.\"}");

    assertThat(rejected).hasStatusOk();
    assertThat(revision(copy)).as("rejected: the revision").isEqualTo(before + 1);
    assertThat(historyKinds(ana(), copy)).first().isEqualTo("REJECTED");
    assertThat(open(mia, copy)).as("a Draft, to the reviewer").hasStatus(404);
    assertThat(rejected)
        .as("a rejection tells the reviewer nothing of the Draft it made")
        .bodyJson()
        .isEqualTo(
            "{\"revision\": %d, \"issues\": [], \"rulesDeleted\": [], \"stale\": false}"
                .formatted(before + 1));

    assertThat(post("/submit", "\"%d\"".formatted(revision(copy)), "{}")).hasStatusOk();
    before = revision(copy);
    var bensBefore = revision(bens);
    var approved =
        edit(
            mia,
            mvc.post().uri("/api/catalogs/{id}/approve", copy),
            "\"%d\"".formatted(before),
            "{}");

    assertThat(approved).hasStatusOk();
    assertThat(revision(copy)).as("approved: the revision").isEqualTo(before + 1);
    assertThat(historyKinds(ana(), copy)).first().isEqualTo("APPROVED");
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(approved, "$.issues"))
        .as("an approval answers with the Approved version's issues")
        .isEqualTo(ApplicationIT.<List<Map<String, Object>>>read(open(mia, copy), "$.issues"));
    assertThat(revision(bens)).as("sent back: the revision").isEqualTo(bensBefore + 1);
    assertThat(historyKinds(ben(), bens)).first().isEqualTo("RETURNED_STALE");
    assertThat(open(ben(), bens)).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
  }

  /** The Approved version that Ben's working copy became. */
  private long approvedSince;

  /** Approves a working copy of Ben's, which leaves Ana's copy stale. */
  private void approveAnotherVersion() {
    approvedSince = workingCopy(ben(), "COMPACT_SUV", 2026);
    assertThat(
            edit(ben(), mvc.post().uri("/api/catalogs/{id}/submit", approvedSince), "\"0\"", "{}"))
        .hasStatusOk();
    assertThat(
            edit(
                signedInAs(Role.MANAGER, "mia"),
                mvc.post().uri("/api/catalogs/{id}/approve", approvedSince),
                "\"1\"",
                "{}"))
        .hasStatusOk();
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.stale").isEqualTo(true);
  }

  /** The answer names the catalog's revision and has its issues and staleness, as a read has. */
  private void assertAnswersAsTheCatalogIs(String what, MvcTestResult answer) {
    var opened = open(ana(), copy);
    assertThat(ApplicationIT.<Integer>read(answer, "$.revision").longValue())
        .as("%s: the revision answered", what)
        .isEqualTo(revision(copy));
    assertThat(ApplicationIT.<List<Map<String, Object>>>read(answer, "$.issues"))
        .as("%s: the issues answered", what)
        .isEqualTo(ApplicationIT.<List<Map<String, Object>>>read(opened, "$.issues"));
    assertThat(ApplicationIT.<Boolean>read(answer, "$.stale"))
        .as("%s: whether it is stale", what)
        .isEqualTo(ApplicationIT.<Boolean>read(opened, "$.stale"));
  }

  private MvcTestResult sellLuxuryIn(String revision, String region) {
    return put(
        "/trims/%d/regions".formatted(trim("Luxury")),
        revision,
        "{\"regionCodes\": [\"%s\"]}".formatted(region));
  }

  private MvcTestResult rename(String revision, String name) {
    return edit(
        ana(),
        mvc.patch().uri("/api/catalogs/{id}", copy),
        revision,
        "{\"name\": \"%s\"}".formatted(name));
  }

  private MvcTestResult post(String part, String revision, String body) {
    return edit(ana(), mvc.post().uri("/api/catalogs/{id}" + part, copy), revision, body);
  }

  private MvcTestResult put(String part, String revision, String body) {
    return edit(ana(), mvc.put().uri("/api/catalogs/{id}" + part, copy), revision, body);
  }

  private MvcTestResult delete(String part, String revision) {
    return edit(ana(), mvc.delete().uri("/api/catalogs/{id}" + part, copy), revision, null);
  }

  /** Leather Seats requires the target, on every trim and in every region. */
  private String rule(String target) {
    return """
        {"kind": "REQUIRES", "sourceFeatureId": %d, "targetFeatureIds": [%d],
         "allTrims": true, "trimIds": [], "allRegions": true, "regionCodes": []}
        """
        .formatted(feature("SEAT_LEATHER"), feature(target));
  }

  /** The key of the copy's one rule. */
  private String ruleKey() {
    return jdbc.sql("SELECT rule_key::text FROM catalog_rule WHERE catalog_id = :copy")
        .param("copy", copy)
        .query(String.class)
        .single();
  }

  /** A write of the copy, made from the revision given as {@code If-Match} carries it. */
  private interface Write {
    MvcTestResult send(String revision);
  }
}
