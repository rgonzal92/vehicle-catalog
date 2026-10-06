package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that a catalog's change history lists every change, newest first and a page at a time,
 * with what each one touched named by its labels. Each test starts from the seeded catalogs and a
 * working copy of Compact SUV 2026 that Ana owns.
 */
class ChangeHistoryIT extends WorkingCopyTests {
  /** Ana's working copy, at revision 0. */
  private long copy;

  @BeforeEach
  void anasWorkingCopy() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
  }

  @Test
  void aWorkingCopyTakesNoHistoryFromTheApprovedVersionItIsCopiedFrom() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    recordManualSetOn(approved);

    var created = workingCopy(ben(), "COMPACT_SUV", 2026);

    var history = changes(ben(), created, "");
    assertThat(history).hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(0);
    assertThat(history).bodyJson().extractingPath("$.items").asArray().isEmpty();
    assertThat(changes(ben(), approved, "")).bodyJson().extractingPath("$.total").isEqualTo(1);
  }

  @Test
  void changingACellAddsAnEntryThatNamesTheFeatureTheTrimTheRegionAndBothValues() {
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));

    var history = changes(ana(), copy, "");

    assertThat(history).hasStatusOk().bodyJson().extractingPath("$.total").isEqualTo(1);
    assertThat(ApplicationIT.<Map<String, Object>>read(history, "$.items[0]"))
        .containsEntry("kind", "CELL_SET")
        .containsEntry("actor", "ana")
        .containsEntry("featureCode", "TRANS_MANUAL")
        .containsEntry("featureName", "Manual Transmission")
        .containsEntry("trim", "Base")
        .containsEntry("region", "North America")
        .containsEntry("oldValue", "N")
        .containsEntry("newValue", "A")
        .containsKey("id");
    assertThat(Instant.parse(ApplicationIT.<String>read(history, "$.items[0].at")))
        .isBetween(Instant.now().minus(Duration.ofMinutes(1)), Instant.now());
  }

  @Test
  void theHistoryIsNewestFirstAndComesAPageAtATime() {
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    setCells(ana(), copy, "\"1\"", cell("TRANS_MANUAL", "Base", "NA", "S"));
    setCells(
        ana(),
        copy,
        "\"2\"",
        cell("ENGINE_15T_I4", "Base", "NA", "A"),
        cell("ENGINE_15T_I4", "Sport", "NA", "N"));

    var first = changes(ana(), copy, "?page=0&size=3");
    var second = changes(ana(), copy, "?page=1&size=3");

    assertThat(first).bodyJson().extractingPath("$.total").isEqualTo(4);
    assertThat(ApplicationIT.<List<String>>read(first, "$.items[*].trim"))
        .as("the cells of one save keep their order, and the save made last comes first")
        .containsExactly("Sport", "Base", "Base");
    assertThat(ApplicationIT.<List<String>>read(first, "$.items[*].newValue"))
        .containsExactly("N", "A", "S");
    assertThat(second).bodyJson().extractingPath("$.total").isEqualTo(4);
    assertThat(ApplicationIT.<List<String>>read(second, "$.items[*].newValue"))
        .containsExactly("A");
    assertThat(ApplicationIT.<List<Object>>read(changes(ana(), copy, ""), "$.items"))
        .as("a page holds 25 changes unless it is asked for another size")
        .hasSize(4);
    assertThat(ApplicationIT.<List<Object>>read(changes(ana(), copy, "?page=-3&size=0"), "$.items"))
        .as("a page and a size out of range are brought into it")
        .hasSize(1);
    assertThat(ApplicationIT.<List<Object>>read(changes(ana(), copy, "?page=9"), "$.items"))
        .isEmpty();
  }

  @Test
  void aRefusedEditAddsNothing() {
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));

    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "S"));
    setCells(ana(), copy, "\"1\"", cell("TRANS_MANUAL", "Luxury", "NA", "S"));
    setCells(ben(), copy, "\"1\"", cell("TRANS_MANUAL", "Base", "NA", "S"));

    assertThat(changes(ana(), copy, "")).bodyJson().extractingPath("$.total").isEqualTo(1);
  }

  @Test
  void onlySomeoneWhoMayOpenTheCatalogReadsItsHistory() {
    for (var role : Role.values()) {
      assertThat(changes(signedInAs(role, "someone-else"), copy, ""))
          .as(role.name())
          .hasStatus(404)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NOT_FOUND");
      assertThat(changes(signedInAs(role, "someone-else"), approved("COMPACT_SUV", 2026, 2), ""))
          .as("everyone opens an Approved version: %s", role)
          .hasStatusOk();
    }
    assertThat(changes(ana(), 987_654_321, "")).hasStatus(404);
    assertThat(changes(request -> request, copy, "")).hasStatus(401);
  }

  @Test
  void aWorkingCopyReadsWithTheLibrarysCurrentLabelsAndAnApprovedVersionWithItsOwn() {
    var approved = approved("COMPACT_SUV", 2026, 2);
    recordManualSetOn(approved);
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    jdbc.sql("UPDATE trim SET name = 'Entry' WHERE name = 'Base'").update();
    jdbc.sql("UPDATE region SET name = 'Americas' WHERE code = 'NA'").update();
    jdbc.sql("UPDATE feature SET name = 'Stick Shift' WHERE code = 'TRANS_MANUAL'").update();

    assertThat(ApplicationIT.<Map<String, Object>>read(changes(ana(), copy, ""), "$.items[0]"))
        .containsEntry("featureName", "Stick Shift")
        .containsEntry("trim", "Entry")
        .containsEntry("region", "Americas");
    assertThat(ApplicationIT.<Map<String, Object>>read(changes(ana(), approved, ""), "$.items[0]"))
        .as("the labels frozen at approval")
        .containsEntry("featureName", "Manual Transmission")
        .containsEntry("trim", "Base")
        .containsEntry("region", "North America");
  }

  @Test
  void aChangeOfAnyKindIsListedWithWhateverItNames() {
    jdbc.sql(
            """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            VALUES (:id, :ana, 'TRIM_REMOVED', jsonb_build_object('trimId', :trim)),
                   (:id, :ana, 'SOMETHING_NEW',
                    '{"note": "nothing the history knows", "featureId": "no number"}')
            """)
        .param("id", copy)
        .param("ana", person("ana"))
        .param("trim", trim("Luxury"))
        .update();

    var history = changes(ana(), copy, "");

    assertThat(ApplicationIT.<Map<String, Object>>read(history, "$.items[0]"))
        .containsEntry("kind", "SOMETHING_NEW")
        .containsEntry("actor", "ana")
        .containsEntry("featureName", null)
        .containsEntry("trim", null)
        .containsEntry("region", null)
        .containsEntry("oldValue", null)
        .containsEntry("newValue", null);
    assertThat(ApplicationIT.<Map<String, Object>>read(history, "$.items[1]"))
        .as("a trim the catalog no longer has is still named, from the library")
        .containsEntry("kind", "TRIM_REMOVED")
        .containsEntry("trim", "Luxury")
        .containsEntry("featureName", null);
  }

  /**
   * Gives the catalog a change entry as an edit would have written it: the manual transmission of
   * Base in North America, set from Not offered to Available.
   */
  private void recordManualSetOn(long catalog) {
    jdbc.sql(
            """
            INSERT INTO catalog_change (catalog_id, actor_id, kind, payload)
            VALUES (:id, :ana, 'CELL_SET',
                    jsonb_build_object('featureId', :feature, 'trimId', :trim, 'regionCode', 'NA',
                                       'old', 'N', 'new', 'A'))
            """)
        .param("id", catalog)
        .param("ana", person("ana"))
        .param("feature", feature("TRANS_MANUAL"))
        .param("trim", trim("Base"))
        .update();
  }

  private MvcTestResult changes(RequestPostProcessor who, long catalog, String paging) {
    return mvc.get().uri("/api/catalogs/" + catalog + "/changes" + paging).with(who).exchange();
  }
}
