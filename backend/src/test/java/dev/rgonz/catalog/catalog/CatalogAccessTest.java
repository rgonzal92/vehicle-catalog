package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.catalog.CatalogAccess.Act;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks, with no database, whom each act on a catalog goes ahead for, by whether the catalog is
 * theirs and its status, and what everyone else is refused with.
 */
class CatalogAccessTest {
  private static final String ABSENT = "404 NOT_FOUND There is nothing at this address.";
  private static final String NOT_DRAFT = "409 NOT_DRAFT Only a catalog in status Draft can be ";
  private static final String NOT_SUBMITTED =
      "409 NOT_SUBMITTED Only a catalog in status Submitted can be ";

  @Test
  void eachActGoesAheadForWhomItMayAndRefusesAnybodyElseWithoutSayingWhatIsNotTheirs() {
    var found = new ArrayList<String>();
    for (var act : Act.values()) {
      for (var own : List.of(true, false)) {
        for (var status : Status.values()) {
          found.add(
              "%s, %s %s: %s"
                  .formatted(
                      act, own ? "their own" : "another's", status, outcome(act, own, status)));
        }
      }
    }

    assertThat(found)
        .containsExactly(
            "EDIT, their own DRAFT: goes ahead",
            "EDIT, their own SUBMITTED: " + NOT_DRAFT + "edited.",
            "EDIT, their own APPROVED: " + NOT_DRAFT + "edited.",
            "EDIT, another's DRAFT: " + ABSENT,
            "EDIT, another's SUBMITTED: " + ABSENT,
            "EDIT, another's APPROVED: " + ABSENT,
            "PREVIEW_UPDATE, their own DRAFT: goes ahead",
            "PREVIEW_UPDATE, their own SUBMITTED: " + NOT_DRAFT + "updated from Approved.",
            "PREVIEW_UPDATE, their own APPROVED: " + NOT_DRAFT + "updated from Approved.",
            "PREVIEW_UPDATE, another's DRAFT: " + ABSENT,
            "PREVIEW_UPDATE, another's SUBMITTED: " + ABSENT,
            "PREVIEW_UPDATE, another's APPROVED: " + ABSENT,
            "WITHDRAW, their own DRAFT: " + NOT_SUBMITTED + "withdrawn.",
            "WITHDRAW, their own SUBMITTED: goes ahead",
            "WITHDRAW, their own APPROVED: " + NOT_SUBMITTED + "withdrawn.",
            "WITHDRAW, another's DRAFT: " + ABSENT,
            "WITHDRAW, another's SUBMITTED: " + ABSENT,
            "WITHDRAW, another's APPROVED: " + ABSENT,
            "DECIDE, their own DRAFT: 403 SELF_APPROVAL Nobody decides on a catalog of their own.",
            "DECIDE, their own SUBMITTED: 403 SELF_APPROVAL Nobody decides on a catalog of their own.",
            "DECIDE, their own APPROVED: 403 SELF_APPROVAL Nobody decides on a catalog of their own.",
            "DECIDE, another's DRAFT: " + ABSENT,
            "DECIDE, another's SUBMITTED: goes ahead",
            "DECIDE, another's APPROVED: " + NOT_SUBMITTED + "approved or rejected.");
  }

  /** "goes ahead", or the refusal's status, code, and reason. */
  private static String outcome(Act act, boolean own, Status status) {
    try {
      CatalogAccess.check(act, own, status);
      return "goes ahead";
    } catch (ApiException refused) {
      var problem = refused.getBody();
      return "%d %s %s"
          .formatted(
              refused.getStatusCode().value(),
              problem.getProperties().get("code"),
              problem.getDetail());
    }
  }
}
