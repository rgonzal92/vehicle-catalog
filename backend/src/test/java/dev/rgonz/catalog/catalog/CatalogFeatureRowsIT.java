package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the owner of a working copy in status Draft adds feature rows from the library and
 * removes them. Each test starts from the seeded catalogs and a working copy of Compact SUV 2026
 * that Ana owns, which has 151 feature rows and none for the removable roof or the heavy-duty tow
 * package.
 */
class CatalogFeatureRowsIT extends WorkingCopyTests {
  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
  }

  @Test
  void theOwnerAddsFeaturesAndEachBecomesARowWithEveryCellNotOffered() {
    var roof = feature("ROOF_REMOVABLE");
    var towing = feature("PACKAGE_TOW_HEAVY_DUTY");
    var cells = count("catalog_cell WHERE catalog_id = %d", copy);

    var added = addFeatures(ana(), copy, "\"0\"", roof, towing);

    assertThat(added).hasStatusOk().headers().hasValue("ETag", "\"1\"");
    assertThat(added).bodyJson().extractingPath("$.revision").isEqualTo(1);
    var opened = open(ana(), copy);
    assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.featureRows")).hasSize(153);
    assertThat(row(opened, "ROOF_REMOVABLE"))
        .containsEntry("id", (int) roof)
        .containsEntry("name", "Removable Roof")
        .containsEntry("categoryCode", "EXTERIOR")
        .containsEntry("kind", "FEATURE");
    assertThat(row(opened, "PACKAGE_TOW_HEAVY_DUTY"))
        .containsEntry("categoryCode", "PACKAGES")
        .containsEntry("kind", "PACKAGE");
    assertThat(count("catalog_cell WHERE catalog_id = %d", copy))
        .as("a new row has no cells, which is every cell Not offered")
        .isEqualTo(cells);
  }

  @Test
  void onlyAnActiveLibraryFeatureThatIsNoRowYetCanBeAdded() {
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'PACKAGE_TOW_HEAVY_DUTY'")
        .update();
    var refused =
        Map.of(
            "a retired feature",
                addFeatures(
                    ana(),
                    copy,
                    "\"0\"",
                    feature("ROOF_REMOVABLE"),
                    feature("PACKAGE_TOW_HEAVY_DUTY")),
            "a feature the library does not have", addFeatures(ana(), copy, "\"0\"", 987_654_321L),
            "a feature that is a row already",
                addFeatures(ana(), copy, "\"0\"", feature("ROOF_PANORAMIC")),
            "a feature named twice",
                addFeatures(
                    ana(), copy, "\"0\"", feature("ROOF_REMOVABLE"), feature("ROOF_REMOVABLE")),
            "no features at all", addFeatures(ana(), copy, "\"0\""));

    refused.forEach(
        (what, answer) ->
            assertThat(answer)
                .as(what)
                .hasStatus(422)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("VALIDATION"));
    assertThat(revision(copy)).isZero();
    assertThat(count("catalog_feature WHERE catalog_id = %d", copy)).isEqualTo(151);
  }

  @Test
  void aRetiredFeatureThatIsARowAlreadyStaysUntilItIsRemoved() {
    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();

    assertThat(row(open(ana(), copy), "ROOF_PANORAMIC")).containsEntry("name", "Panoramic Roof");
    assertThat(removeFeature(ana(), copy, "\"0\"", feature("ROOF_PANORAMIC"))).hasStatusOk();
    assertThat(addFeatures(ana(), copy, "\"1\"", feature("ROOF_PANORAMIC")))
        .as("once removed, it cannot be added again")
        .hasStatus(422);
  }

  @Test
  void aCatalogHasAtMostFiveHundredFeatureRows() {
    var empty = workingCopy(ana(), "SPORTS_COUPE", 2026);
    jdbc.sql(
            """
            INSERT INTO feature (code, name, category_code, kind)
            SELECT 'EXTRA_' || n, 'Extra ' || n, 'EXTERIOR', 'FEATURE'
            FROM generate_series(1, 200) AS n
            """)
        .update();
    var library = jdbc.sql("SELECT id FROM feature ORDER BY id").query(Long.class).list();
    assertThat(library).hasSizeGreaterThan(501);

    assertThat(addFeatures(ana(), empty, "\"0\"", library.subList(0, 501).toArray(Long[]::new)))
        .as("501 at once")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(addFeatures(ana(), empty, "\"0\"", library.subList(0, 500).toArray(Long[]::new)))
        .hasStatusOk();
    assertThat(addFeatures(ana(), empty, "\"1\"", library.get(500)))
        .as("a 501st")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");
    assertThat(count("catalog_feature WHERE catalog_id = %d", empty)).isEqualTo(500);
    assertThat(revision(empty)).isEqualTo(1);
  }

  @Test
  void removingARowRemovesItsCellsAndNoOtherRows() {
    var roof = feature("ROOF_PANORAMIC");
    var cells = count("catalog_cell WHERE catalog_id = %d", copy);
    var roofCells = count("catalog_cell WHERE catalog_id = %d AND feature_id = %d", copy, roof);
    assertThat(roofCells).isPositive();

    var removed = removeFeature(ana(), copy, "\"0\"", roof);

    assertThat(removed).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(1);
    assertThat(count("catalog_feature WHERE catalog_id = %d AND feature_id = %d", copy, roof))
        .isZero();
    assertThat(count("catalog_feature WHERE catalog_id = %d", copy)).isEqualTo(150);
    assertThat(count("catalog_cell WHERE catalog_id = %d", copy)).isEqualTo(cells - roofCells);
    assertThat(removeFeature(ana(), copy, "\"1\"", roof))
        .as("it is no row any more")
        .hasStatus(404);
    assertThat(count("feature WHERE id = %d", roof)).as("the library keeps it").isEqualTo(1);
    assertThat(
            count(
                "catalog_feature WHERE catalog_id = %d AND feature_id = %d",
                approved("COMPACT_SUV", 2026, 2), roof))
        .as("and so does every other catalog")
        .isEqualTo(1);
  }

  @Test
  void twoWorkingCopiesAddTheSameLibraryFeatureAndHoldDifferentCellsForIt() {
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    addFeatures(ana(), copy, "\"0\"", feature("ROOF_REMOVABLE"));
    addFeatures(ben(), bens, "\"0\"", feature("ROOF_REMOVABLE"));

    assertThat(setCells(ana(), copy, "\"1\"", cell("ROOF_REMOVABLE", "Base", "NA", "S")))
        .hasStatusOk();
    assertThat(setCells(ben(), bens, "\"1\"", cell("ROOF_REMOVABLE", "Base", "NA", "A")))
        .hasStatusOk();

    Function<Long, String> baseRoof =
        catalog ->
            jdbc.sql(
                    """
                    SELECT availability FROM catalog_cell
                    WHERE catalog_id = :id AND feature_id = :feature
                    """)
                .param("id", catalog)
                .param("feature", feature("ROOF_REMOVABLE"))
                .query(String.class)
                .single();
    assertThat(baseRoof.apply(copy)).isEqualTo("S");
    assertThat(baseRoof.apply(bens)).isEqualTo("A");
  }

  @Test
  void theEditingRulesHoldForAddingAndRemoving() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    // The seeded catalogs belong to the demo author.
    var ownerOfApproved = signedInAs(Role.AUTHOR, "author");
    Map<String, Edit> edits =
        Map.of(
            "add features",
                (who, id, revision) -> addFeatures(who, id, revision, feature("ROOF_REMOVABLE")),
            "remove a feature",
                (who, id, revision) -> removeFeature(who, id, revision, feature("ROOF_PANORAMIC")));

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
          assertThat(edit.send(ben(), copy, "\"0\"")).as("%s by someone else", what).hasStatus(404);
          assertThat(edit.send(ownerOfApproved, approved, "\"0\""))
              .as("%s of an Approved version", what)
              .hasStatus(409)
              .bodyJson()
              .extractingPath("$.code")
              .isEqualTo("NOT_DRAFT");
        });
    assertThat(revision(copy)).isZero();
    assertThat(count("catalog_feature WHERE catalog_id = %d", copy)).isEqualTo(151);
    assertThat(count("catalog_change WHERE catalog_id = %d", copy)).isZero();
  }

  @Test
  void eachChangeAppearsInTheChangeHistoryWithItsKind() {
    addFeatures(ana(), copy, "\"0\"", feature("ROOF_REMOVABLE"), feature("PACKAGE_TOW_HEAVY_DUTY"));
    removeFeature(ana(), copy, "\"1\"", feature("ROOF_REMOVABLE"));

    var history = mvc.get().uri("/api/catalogs/{id}/changes", copy).with(ana()).exchange();

    assertThat(
            ApplicationIT.<List<Map<String, Object>>>read(history, "$.items").reversed().stream()
                .map(change -> "%s %s".formatted(change.get("kind"), change.get("featureCode"))))
        .as("oldest first")
        .containsExactly(
            "FEATURE_ADDED ROOF_REMOVABLE",
            "FEATURE_ADDED PACKAGE_TOW_HEAVY_DUTY",
            "FEATURE_REMOVED ROOF_REMOVABLE");
    assertThat(history)
        .bodyJson()
        .extractingPath("$.items[0].featureName")
        .as("a feature that is no row any more is still named, from the library")
        .isEqualTo("Removable Roof");
  }

  /** One of the edits, sent by a person to a catalog as an edit of a revision. */
  private interface Edit {
    MvcTestResult send(RequestPostProcessor who, long catalog, String revision);
  }

  private MvcTestResult addFeatures(
      RequestPostProcessor who, long catalog, String revision, Long... featureIds) {
    return edit(
        who,
        mvc.post().uri("/api/catalogs/{id}/features", catalog),
        revision,
        "{\"featureIds\": %s}".formatted(List.of(featureIds)));
  }

  private MvcTestResult removeFeature(
      RequestPostProcessor who, long catalog, String revision, long featureId) {
    return edit(
        who,
        mvc.delete().uri("/api/catalogs/{id}/features/{feature}", catalog, featureId),
        revision,
        null);
  }

  /** The feature row of the opened catalog that has the code. */
  private static Map<String, Object> row(MvcTestResult opened, String code) {
    return ApplicationIT.<List<Map<String, Object>>>read(
            opened, "$.snapshot.featureRows[?(@.code == '%s')]".formatted(code))
        .getFirst();
  }
}
