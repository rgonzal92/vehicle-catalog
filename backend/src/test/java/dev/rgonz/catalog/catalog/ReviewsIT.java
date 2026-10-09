package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.Role;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that a reviewer approves and rejects Submitted catalogs, and what an approval does to the
 * catalog and to its lineage. Each test starts from the seeded catalogs and a working copy of
 * Compact SUV 2026 that Ana, an author, owns and has submitted. Its lineage has two Approved
 * versions.
 */
class ReviewsIT extends WorkingCopyTests {
  @Autowired Reviews reviews;

  /** Ana's working copy, Submitted, at revision 1. */
  private long copy;

  @BeforeEach
  void anasSubmission() throws Exception {
    seedLibraryAndCatalogs();
    copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(submit(ana(), copy, "\"0\"")).hasStatusOk();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  @Test
  void aManagerApprovesASubmissionWhichBecomesTheNextVersionAndTheCurrentApproved() {
    var approved = decide(mia(), "approve", copy, "\"1\"", "Good to go.");

    assertThat(approved).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(2);
    var opened = open(ben(), copy);
    assertThat(opened).as("everyone opens an Approved version").hasStatusOk();
    assertThat(opened).bodyJson().extractingPath("$.snapshot.status").isEqualTo("APPROVED");
    assertThat(opened).bodyJson().extractingPath("$.versionNumber").isEqualTo(3);
    assertThat(opened).bodyJson().extractingPath("$.approvedBy").isEqualTo("mia");
    assertThat(opened).bodyJson().extractingPath("$.approvedAt").asString().isNotBlank();
    assertThat(opened).bodyJson().extractingPath("$.stale").isEqualTo(false);
    for (var role : Role.values()) {
      var lineages = mvc.get().uri("/api/lineages").with(signedInAs(role)).exchange();
      assertThat(
              ApplicationIT.<List<Integer>>read(
                  lineages,
                  "$[?(@.vehicleLine == 'Compact SUV' && @.modelYear == 2026)].catalogId"))
          .as("the lineage's current Approved, for the %s", role)
          .containsExactly((int) copy);
    }
    var versions =
        mvc.get().uri("/api/lineages/{id}/versions", lineageOf(copy)).with(ben()).exchange();
    assertThat(ApplicationIT.<List<Integer>>read(versions, "$[*].versionNumber"))
        .containsExactly(3, 2, 1);
    assertThat(ApplicationIT.<List<Object>>read(listOfWorkingCopies(ana()), "$"))
        .as("its owner no longer has it as a working copy")
        .isEmpty();
    assertThat(setCells(ana(), copy, "\"2\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .as("nobody edits it")
        .hasStatus(409);
    assertThat(reviewsOf(copy)).containsExactly("APPROVED by mia: Good to go.");
  }

  @Test
  void aLineagesFirstApprovalGivesVersionOne() {
    var first = workingCopy(ana(), "PICKUP_TRUCK", 2027);
    submit(ana(), first, "\"0\"");

    assertThat(decide(mia(), "approve", first, "\"1\"", null)).hasStatusOk();

    assertThat(open(ana(), first)).bodyJson().extractingPath("$.versionNumber").isEqualTo(1);
    assertThat(reviewsOf(first)).containsExactly("APPROVED by mia: null");
  }

  @Test
  void nobodyDecidesOnTheirOwnCatalogAndAnAuthorDecidesOnNone() {
    var mias = workingCopy(mia(), "COMPACT_SUV", 2027);
    submit(mia(), mias, "\"0\"");

    for (var decision : List.of("approve", "reject")) {
      assertThat(decide(mia(), decision, mias, "\"1\"", "Fine."))
          .as("%s their own", decision)
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("SELF_APPROVAL");
      assertThat(decide(ben(), decision, copy, "\"1\"", "Fine."))
          .as("%s as an author", decision)
          .hasStatus(403)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("FORBIDDEN");
    }
    assertThat(open(mia(), mias))
        .bodyJson()
        .extractingPath("$.snapshot.status")
        .isEqualTo("SUBMITTED");
    assertThat(count("catalog_review")).isZero();
  }

  @Test
  void aRejectionNeedsACommentAndSendsTheCatalogBackToItsOwner() {
    assertThat(decide(mia(), "reject", copy, "\"1\"", "   "))
        .as("without saying why")
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VALIDATION");
    assertThat(decide(mia(), "reject", copy, "\"1\"", "x".repeat(1001)))
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("LIMIT_EXCEEDED");

    var rejected = decide(mia(), "reject", copy, "\"1\"", "The hybrid needs its battery cooling.");

    assertThat(rejected).hasStatusOk().bodyJson().extractingPath("$.revision").isEqualTo(2);
    assertThat(open(ana(), copy)).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
    assertThat(open(mia(), copy)).as("a Draft is its owner's alone again").hasStatus(404);
    assertThat(reviewsOf(copy))
        .containsExactly("REJECTED by mia: The hybrid needs its battery cooling.");
    assertThat(setCells(ana(), copy, "\"2\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .hasStatusOk();
    assertThat(submit(ana(), copy, "\"3\"")).as("submitting it again").hasStatusOk();
    assertThat(decide(mia(), "approve", copy, "\"4\"", null)).hasStatusOk();
    assertThat(reviewsOf(copy)).hasSize(2);
  }

  @Test
  void aCatalogIsNotApprovedWithAnErrorOfAnInactiveVehicleLineOrWhenItIsStale() {
    // A global rule made after the submit: the manual transmission, which Base has in Europe,
    // requires a removable roof, which the catalog has no row for.
    long rule =
        jdbc.sql(
                """
                INSERT INTO global_rule (kind, source_feature_id, all_regions)
                VALUES ('REQUIRES', :source, true)
                RETURNING id
                """)
            .param("source", feature("TRANS_MANUAL"))
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO global_rule_target VALUES (:rule, :target)")
        .param("rule", rule)
        .param("target", feature("ROOF_REMOVABLE"))
        .update();

    var withAnError = decide(mia(), "approve", copy, "\"1\"", null);

    assertThat(withAnError)
        .hasStatus(422)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("HAS_ERRORS");
    assertThat(ApplicationIT.<List<String>>read(withAnError, "$.issues[*].code"))
        .contains("REQUIRED_NOT_OFFERED");

    jdbc.sql("DELETE FROM global_rule WHERE id = :rule").param("rule", rule).update();
    jdbc.sql("UPDATE vehicle_line SET active = false WHERE code = 'COMPACT_SUV'").update();

    assertThat(decide(mia(), "approve", copy, "\"1\"", null))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("VEHICLE_LINE_INACTIVE");
    assertThat(open(mia(), copy))
        .as("it stays Submitted, to be rejected or withdrawn")
        .bodyJson()
        .extractingPath("$.snapshot.status")
        .isEqualTo("SUBMITTED");

    jdbc.sql("UPDATE vehicle_line SET active = true WHERE code = 'COMPACT_SUV'").update();
    // Another catalog of the lineage is approved meanwhile, which this one was not copied from.
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    jdbc.sql(
            """
            UPDATE catalog
            SET status = 'APPROVED', version_number = 3, approved_by = owner_id, approved_at = now()
            WHERE id = :id
            """)
        .param("id", bens)
        .update();
    jdbc.sql("UPDATE lineage SET current_catalog_id = :catalog WHERE id = :lineage")
        .param("catalog", bens)
        .param("lineage", lineageOf(copy))
        .update();

    assertThat(open(mia(), copy)).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(decide(mia(), "approve", copy, "\"1\"", null))
        .hasStatus(409)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo("STALE");
    assertThat(count("catalog WHERE status = 'APPROVED' AND lineage_id = %d", lineageOf(copy)))
        .isEqualTo(3);
    assertThat(count("catalog_review")).isZero();
  }

  @Test
  void aDecisionNamesTheRevisionTheReviewerSawSoNothingIsApprovedUnseen() {
    // While the review page is open, the owner withdraws the catalog, edits it, and submits again.
    edit(ana(), mvc.post().uri("/api/catalogs/{id}/withdraw", copy), null, null);
    setCells(ana(), copy, "\"2\"", cell("TRANS_MANUAL", "Base", "NA", "A"));
    submit(ana(), copy, "\"3\"");

    for (var decision : List.of("approve", "reject")) {
      var outdated = decide(mia(), decision, copy, "\"1\"", "Fine.");

      assertThat(outdated).as(decision).hasStatus(412);
      assertThat(outdated).bodyJson().extractingPath("$.code").isEqualTo("REVISION_CONFLICT");
      assertThat(outdated).bodyJson().extractingPath("$.revision").isEqualTo(4);
      assertThat(decide(mia(), decision, copy, null, "Fine."))
          .as("%s without a revision", decision)
          .hasStatus(428);
    }
    assertThat(open(mia(), copy))
        .bodyJson()
        .extractingPath("$.snapshot.status")
        .isEqualTo("SUBMITTED");
    assertThat(count("catalog_review")).isZero();
  }

  @Test
  void onlyASubmittedCatalogIsDecidedOnAndADraftIsNotThereForAReviewer() {
    var draft = workingCopy(ben(), "COMPACT_SUV", 2026);
    var approved = approved("COMPACT_SUV", 2026, 2);

    for (var decision : List.of("approve", "reject")) {
      assertThat(decide(mia(), decision, draft, "\"0\"", "Fine."))
          .as("%s a Draft", decision)
          .hasStatus(404);
      assertThat(decide(mia(), decision, approved, "\"0\"", "Fine."))
          .as("%s an Approved version", decision)
          .hasStatus(409)
          .bodyJson()
          .extractingPath("$.code")
          .isEqualTo("NOT_SUBMITTED");
      assertThat(decide(mia(), decision, 987_654_321L, "\"0\"", "Fine.")).hasStatus(404);
    }
  }

  @Test
  void approvingOneOfTwoSubmittedCatalogsOfALineageReturnsTheOtherToItsOwner() {
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    submit(ben(), bens, "\"0\"");
    var elsewhere = workingCopy(ben(), "COMPACT_SUV", 2027);
    submit(ben(), elsewhere, "\"0\"");

    assertThat(decide(mia(), "approve", copy, "\"1\"", null)).hasStatusOk();

    var returned = open(ben(), bens);
    assertThat(returned).bodyJson().extractingPath("$.snapshot.status").isEqualTo("DRAFT");
    assertThat(returned).bodyJson().extractingPath("$.snapshot.revision").isEqualTo(2);
    assertThat(returned).bodyJson().extractingPath("$.stale").isEqualTo(true);
    assertThat(reviewsOf(bens)).containsExactly("RETURNED_STALE by null: null");
    assertThat(open(ben(), elsewhere))
        .as("a Submitted catalog of another lineage")
        .bodyJson()
        .extractingPath("$.snapshot.status")
        .isEqualTo("SUBMITTED");
  }

  @Test
  void twoApprovalsInOneLineageAtTheSameMomentGiveOneNewVersionAndOneRefusal() throws Exception {
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    submit(ben(), bens, "\"0\"");
    var mia = person("mia");
    var ready = new CountDownLatch(2);
    var go = new CountDownLatch(1);

    List<Future<String>> outcomes;
    try (var both = Executors.newFixedThreadPool(2)) {
      outcomes =
          List.of(copy, bens).stream()
              .map(
                  catalog ->
                      both.submit(
                          () -> {
                            ready.countDown();
                            go.await();
                            try {
                              reviews.approve(catalog, mia, "\"1\"", null);
                              return "approved";
                            } catch (ApiException refused) {
                              return String.valueOf(refused.getBody().getProperties().get("code"));
                            }
                          }))
              .toList();
      ready.await();
      go.countDown();
    }

    var answers = List.of(outcomes.get(0).get(), outcomes.get(1).get());
    assertThat(answers).filteredOn("approved"::equals).hasSize(1);
    assertThat(answers)
        .as("the other is stale by then, or back with its owner")
        .filteredOn(answer -> !answer.equals("approved"))
        .first()
        .isIn("STALE", "NOT_FOUND");
    assertThat(count("catalog WHERE status = 'APPROVED' AND lineage_id = %d", lineageOf(copy)))
        .isEqualTo(3);
    assertThat(
            count(
                "catalog WHERE version_number = 3 AND lineage_id = %d AND id IN (%d, %d)",
                lineageOf(copy), copy, bens))
        .isEqualTo(1);
  }

  @Test
  void anApprovedVersionKeepsTheLabelsItWasApprovedWithAndItsIssuesFollowTheLibrary() {
    assertThat(decide(mia(), "approve", copy, "\"1\"", null)).hasStatusOk();
    var before = ApplicationIT.<Map<String, Object>>read(open(ana(), copy), "$.snapshot");
    jdbc.sql("UPDATE trim SET name = 'Sport Plus', sort_order = 99 WHERE name = 'Sport'").update();
    jdbc.sql("UPDATE region SET name = 'Europe and Africa' WHERE code = 'EU'").update();
    jdbc.sql(
            """
            UPDATE feature SET name = 'Glass Roof', category_code = 'INTERIOR'
            WHERE code = 'ROOF_PANORAMIC'
            """)
        .update();

    var after = open(ana(), copy);
    var later = workingCopy(ben(), "COMPACT_SUV", 2026);

    assertThat(ApplicationIT.<Map<String, Object>>read(after, "$.snapshot")).isEqualTo(before);
    assertThat(ApplicationIT.<List<String>>read(after, "$.snapshot.trims[*].name"))
        .as("its trims, in the order they had")
        .containsExactly("Base", "Sport", "Touring", "Off-Road");
    assertThat(ApplicationIT.<List<String>>read(open(ben(), later), "$.snapshot.trims[*].name"))
        .as("a working copy made from it shows the library's labels and order")
        .containsExactly("Base", "Touring", "Off-Road", "Sport Plus");
    assertThat(
            ApplicationIT.<List<String>>read(
                open(ben(), later), "$.snapshot.featureRows[?(@.code == 'ROOF_PANORAMIC')].name"))
        .containsExactly("Glass Roof");

    jdbc.sql("UPDATE feature SET status = 'RETIRED' WHERE code = 'ROOF_PANORAMIC'").update();

    assertThat(ApplicationIT.<List<String>>read(open(ana(), copy), "$.issues[*].code"))
        .as("its issues follow the library as it is today")
        .contains("FEATURE_RETIRED");
  }

  @Test
  void aRejectedCatalogIsDeletedWithTheDecisionsAboutIt() {
    decide(mia(), "reject", copy, "\"1\"", "Not yet.");

    assertThat(edit(ana(), mvc.delete().uri("/api/catalogs/{id}", copy), "\"2\"", null))
        .hasStatus(204);
    assertThat(count("catalog_review")).isZero();
  }

  private MvcTestResult submit(RequestPostProcessor who, long catalog, String revision) {
    return edit(who, mvc.post().uri("/api/catalogs/{id}/submit", catalog), revision, "{}");
  }

  /** Sends a decision that names the revision, or none when it is null, with the comment. */
  private MvcTestResult decide(
      RequestPostProcessor who, String decision, long catalog, String revision, String comment) {
    var body = comment == null ? "{}" : "{\"comment\": \"%s\"}".formatted(comment);

    return edit(
        who, mvc.post().uri("/api/catalogs/{id}/{decision}", catalog, decision), revision, body);
  }

  private MvcTestResult listOfWorkingCopies(RequestPostProcessor who) {
    return mvc.get().uri("/api/catalogs?scope=mine").with(who).exchange();
  }

  private long lineageOf(long catalog) {
    return jdbc.sql("SELECT lineage_id FROM catalog WHERE id = :id")
        .param("id", catalog)
        .query(Long.class)
        .single();
  }

  /** The decisions about the catalog, oldest first: "DECISION by reviewer: comment". */
  private List<String> reviewsOf(long catalog) {
    return jdbc.sql(
            """
            SELECT r.decision || ' by ' || COALESCE(a.username, 'null') || ': '
                   || COALESCE(r.comment, 'null')
            FROM catalog_review r
            LEFT JOIN app_user a ON a.id = r.reviewer_id
            WHERE r.catalog_id = :catalog
            ORDER BY r.id
            """)
        .param("catalog", catalog)
        .query(String.class)
        .list();
  }
}
