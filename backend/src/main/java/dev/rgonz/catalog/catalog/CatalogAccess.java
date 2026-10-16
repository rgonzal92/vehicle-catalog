package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.core.ApiException;

/**
 * Whether someone may do something to a catalog, by whether it is theirs and by its status. Each
 * act checks whose the catalog is before its status, so that no refusal tells anybody anything
 * about a catalog that is not theirs: to them it is as if it did not exist. Who may open a catalog
 * at all is decided where it is read, and whether a person reviews catalogs by the role a decision
 * asks for.
 */
final class CatalogAccess {
  private CatalogAccess() {}

  /** What someone does to a catalog. */
  enum Act {
    /** Every edit of a working copy, a submit, an update from Approved, and a delete. */
    EDIT,
    /** Working out what an update from Approved would do, before it is made. */
    PREVIEW_UPDATE,
    /** Making a Submitted catalog a Draft again. */
    WITHDRAW,
    /** Approving or rejecting a Submitted catalog. */
    DECIDE
  }

  /**
   * Goes ahead when the act may be done, and is refused otherwise. An edit and the preview of an
   * update are the owner's, while the catalog is in status Draft; a withdrawal is the owner's,
   * while it is Submitted; a decision is a reviewer's and never the owner's, while it is Submitted,
   * and a Draft is as if it did not exist to a reviewer.
   *
   * @param own whether the catalog is the caller's
   */
  static void check(Act act, boolean own, Status status) {
    switch (act) {
      case EDIT -> requireTheirDraft(own, status, "edited");
      case PREVIEW_UPDATE -> requireTheirDraft(own, status, "updated from Approved");
      case WITHDRAW -> {
        if (!own) {
          throw ApiException.notFound();
        }
        if (status != Status.SUBMITTED) {
          throw ApiException.conflict(
              "NOT_SUBMITTED", "Only a catalog in status Submitted can be withdrawn.");
        }
      }
      case DECIDE -> {
        if (own) {
          throw ApiException.forbidden(
              "SELF_APPROVAL", "Nobody decides on a catalog of their own.");
        }
        if (status == Status.DRAFT) {
          throw ApiException.notFound();
        }
        if (status != Status.SUBMITTED) {
          throw ApiException.conflict(
              "NOT_SUBMITTED", "Only a catalog in status Submitted can be approved or rejected.");
        }
      }
    }
  }

  /** Refuses anybody but the owner, and a catalog not in status Draft in the words given. */
  private static void requireTheirDraft(boolean own, Status status, String doneToIt) {
    if (!own) {
      throw ApiException.notFound();
    }
    if (status != Status.DRAFT) {
      throw ApiException.conflict(
          "NOT_DRAFT", "Only a catalog in status Draft can be %s.".formatted(doneToIt));
    }
  }
}
