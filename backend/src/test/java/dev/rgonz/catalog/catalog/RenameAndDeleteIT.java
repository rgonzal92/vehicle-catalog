package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a working copy in status Draft renames it and deletes it, under the
 * rules every edit of a working copy follows. Each test starts from the seeded catalogs and a
 * working copy of Compact SUV 2026 that Ana owns, named "COMPACT_SUV 2026".
 */
class RenameAndDeleteIT extends WorkingCopyTests {
  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
  }

  @Test
  void theOwnerRenamesAWorkingCopy() {
    var renamed = rename(ana(), copy, "\"0\"", "  Spring update ");

    assertThat(renamed).hasStatusOk().headers().hasValue("ETag", "\"1\"");
    assertThat(renamed).bodyJson().extractingPath("$.revision").isEqualTo(1);
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.name").isEqualTo("Spring update");
    var mine = mvc.get().uri("/api/catalogs?scope=mine").with(ana()).exchange();
    assertThat(mine).bodyJson().extractingPath("$[0].name").isEqualTo("Spring update");
    assertThat(mine).bodyJson().extractingPath("$[0].revision").isEqualTo(1);
    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();
    assertThat(ApplicationIT.<Map<String, Object>>read(history, "$.items[0]"))
        .containsEntry("kind", "RENAMED")
        .containsEntry("oldValue", "COMPACT_SUV 2026")
        .containsEntry("newValue", "Spring update");
  }

  @Test
  void aNameIsOneToEightyCharactersAndNoOtherWorkingCopyOfTheOwnersHasIt() {
    anaOwnsTheApprovedVersions();
    workingCopy(ana(), "SPORTS_COUPE", 2026);
    idOf(create(ben(), "Ben's own", line("SEDAN"), 2027));

    assertThat(rename(ana(), copy, "\"0\"", "sports_coupe 2026"))
        .as("taken by another of her working copies, whatever its case")
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("NAME_TAKEN");
    for (var name : List.of("", "   ", "x".repeat(81))) {
      assertThat(rename(ana(), copy, "\"0\"", name))
          .as("[%s]", name)
          .hasStatus(422)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("VALIDATION");
    }
    assertThat(edit(ana(), mvc.patch().uri("/api/catalogs/{id}", copy), "\"0\"", "{}"))
        .as("no name at all")
        .hasStatus(422);
    assertThat(revision(copy)).isZero();
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isZero();

    assertThat(rename(ana(), copy, "\"0\"", "Ben's own"))
        .as("another owner's name is free")
        .hasStatusOk();
    assertThat(rename(ana(), copy, "\"1\"", "Hybrid and autumn update"))
        .as("so is the name of an Approved version, though it is hers")
        .hasStatusOk();
    assertThat(rename(ana(), copy, "\"2\"", "x".repeat(80))).hasStatusOk();
  }

  @Test
  void renamingToTheNameItHasChangesNothingAndChangingItsCaseDoes() {
    assertThat(rename(ana(), copy, "\"0\"", "COMPACT_SUV 2026"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(0);
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isZero();

    assertThat(rename(ana(), copy, "\"0\"", "Compact SUV 2026"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.revision")
        .isEqualTo(1);
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.name").isEqualTo("Compact SUV 2026");
  }

  @Test
  void theOwnerDeletesAWorkingCopyWithItsContentsAndItsChangeHistory() {
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    edit(
        ana(),
        mvc.post().uri("/api/catalogs/{id}/rules", copy),
        "\"1\"",
        """
        {"kind": "EXCLUDES", "sourceFeatureId": %d, "targetFeatureIds": [%d],
         "allTrims": false, "trimIds": [%d], "allRegions": false, "regionCodes": ["NA"]}
        """
            .formatted(feature("TRANS_MANUAL"), feature("SEAT_LEATHER"), trim("Base")));
    // A catalog that was rejected has a decision about it too.
    jdbc.sql(
            """
            INSERT INTO catalog_review (catalog_id, reviewer_id, decision, comment)
            VALUES (:catalog, :reviewer, 'REJECTED', 'Not yet.')
            """)
        .param("catalog", copy)
        .param("reviewer", person("ben"))
        .update();
    var approved = approved("COMPACT_SUV", 2026, 2);
    var sibling = workingCopy(ben(), "COMPACT_SUV", 2026);
    var lineages = jdbc.sql("SELECT * FROM lineage ORDER BY id").query().listOfRows();
    var approvedRows = rowsOf(approved);
    var siblingRows = rowsOf(sibling);
    assertThat(rowsOf(copy))
        .as("every table that holds something of a catalog holds something of this one")
        .containsKeys(
            "catalog", "catalog_cell", "catalog_change", "catalog_rule_trim", "catalog_review")
        .allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());

    var deleted = delete(ana(), copy, "\"2\"");

    assertThat(deleted).hasStatus(204);
    assertThat(rowsOf(copy).values()).as("nothing of it is left").allMatch(rows -> rows == 0);
    assertThat(rowsOf(approved))
        .as("the Approved version it was copied from")
        .isEqualTo(approvedRows);
    assertThat(rowsOf(sibling)).as("another working copy of the lineage").isEqualTo(siblingRows);
    assertThat(jdbc.sql("SELECT * FROM lineage ORDER BY id").query().listOfRows())
        .as("the lineages, each still pointing at its current Approved version")
        .isEqualTo(lineages);
    assertThat(open(ana(), copy)).hasStatus(404);
    assertThat(mvc.get().uri("/api/catalogs?scope=mine").with(ana()))
        .bodyJson()
        .extractingPath("$")
        .asArray()
        .isEmpty();
    assertThat(create(ana(), "COMPACT_SUV 2026", line("COMPACT_SUV"), 2026))
        .as("its name is free again")
        .hasStatus(201);
  }

  @Test
  void aDeletedWorkingCopyNoLongerCountsTowardsTheTwentyAPersonCanHave() {
    for (var number = 2; number <= 20; number++) {
      create(ana(), "Coupe " + number, line("SPORTS_COUPE"), 2026);
    }
    assertThat(create(ana(), "One too many", line("SPORTS_COUPE"), 2026)).hasStatus(422);

    assertThat(delete(ana(), copy, "\"0\"")).hasStatus(204);

    assertThat(create(ana(), "Room again", line("SPORTS_COUPE"), 2026)).hasStatus(201);
  }

  @Test
  void theEditingRulesHoldForRenamingAndDeleting() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    // The seeded catalogs belong to the demo author.
    var ownerOfApproved = signedInAs(Role.AUTHOR, "author");
    Map<String, Edit> edits =
        Map.of(
            "rename",
            (who, id, revision) -> rename(who, id, revision, "Another name"),
            "delete",
            this::delete);

    edits.forEach(
        (what, edit) -> {
          assertThat(edit.send(ana(), copy, null))
              .as("%s without a revision", what)
              .hasStatus(428)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("REVISION_REQUIRED");
          var conflict = edit.send(ana(), copy, "\"7\"");
          assertThat(conflict)
              .as("%s from another revision", what)
              .hasStatus(412)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("REVISION_CONFLICT");
          assertThat(conflict).bodyJson().extractingPath("$.revision").isEqualTo(0);
          for (var role : Role.values()) {
            assertThat(edit.send(signedInAs(role, "someone-else"), copy, "\"0\""))
                .as("%s by someone else who is %s", what, role)
                .hasStatus(404);
          }
          assertThat(edit.send(ownerOfApproved, approved, "\"0\""))
              .as("%s of an Approved version", what)
              .hasStatus(409)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("NOT_DRAFT");
          assertThat(edit.send(request -> request, copy, "\"0\""))
              .as("%s without a session", what)
              .hasStatus(401);
        });
    assertThat(revision(copy)).isZero();
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.name").isEqualTo("COMPACT_SUV 2026");
    assertThat(rowsOf(approved).get("catalog")).isEqualTo(1);
  }

  @Test
  void aWorkingCopyThatHasLeftStatusDraftIsNeitherRenamedNorDeleted() {
    jdbc.sql("UPDATE catalog SET status = 'SUBMITTED' WHERE id = :id").param("id", copy).update();

    for (var refused :
        List.of(rename(ana(), copy, "\"0\"", "Another name"), delete(ana(), copy, "\"0\""))) {
      assertThat(refused).hasStatus(409).bodyJson().extractingPath("$.code").isEqualTo("NOT_DRAFT");
    }
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.name").isEqualTo("COMPACT_SUV 2026");
  }

  /** One of the two, sent by a person to a catalog as an edit of a revision. */
  private interface Edit {
    MvcTestResult send(RequestPostProcessor who, long catalog, String revision);
  }

  private MvcTestResult rename(
      RequestPostProcessor who, long catalog, String revision, String name) {
    return edit(
        who,
        mvc.patch().uri("/api/catalogs/{id}", catalog),
        revision,
        "{\"name\": \"%s\"}".formatted(name));
  }

  private MvcTestResult delete(RequestPostProcessor who, long catalog, String revision) {
    return edit(who, mvc.delete().uri("/api/catalogs/{id}", catalog), revision, null);
  }

  /**
   * How many rows each table holds of the catalog: the catalog's own row, and the rows of every
   * table that names a catalog as theirs, whichever tables those are by now. The checks of Approved
   * versions are left out: a working copy has none, and no Approved version is deleted.
   */
  private Map<String, Long> rowsOf(long catalog) {
    var rows = new TreeMap<String, Long>();
    rows.put("catalog", count("catalog WHERE id = %d", catalog));
    jdbc.sql(
            """
            SELECT table_name FROM information_schema.columns
            WHERE table_schema = current_schema() AND column_name = 'catalog_id'
              AND table_name <> 'approved_check'
            """)
        .query(String.class)
        .list()
        .forEach(table -> rows.put(table, count("%s WHERE catalog_id = %d", table, catalog)));
    return rows;
  }
}
