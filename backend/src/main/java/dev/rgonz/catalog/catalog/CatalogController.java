package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogEdits.CellChange;
import dev.rgonz.catalog.catalog.CatalogRules.RuleContent;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.LineageSummary;
import dev.rgonz.catalog.catalog.Catalogs.VersionSummary;
import dev.rgonz.catalog.catalog.ChangeHistory.ChangePage;
import dev.rgonz.catalog.catalog.Diff.Changes;
import dev.rgonz.catalog.catalog.Issue.Severity;
import dev.rgonz.catalog.catalog.Revisions.Outcome;
import dev.rgonz.catalog.catalog.Updates.Preview;
import dev.rgonz.catalog.catalog.WorkingCopies.Counts;
import dev.rgonz.catalog.catalog.WorkingCopies.NewWorkingCopy;
import dev.rgonz.catalog.catalog.WorkingCopies.StartPoint;
import dev.rgonz.catalog.catalog.WorkingCopies.Submitted;
import dev.rgonz.catalog.catalog.WorkingCopies.WorkingCopy;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.AppUsers;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows everyone with a role the lineages, their Approved versions, and a catalog's contents, shows
 * managers and admins the catalogs that are waiting for review and lets them approve or reject one,
 * and lets each of them create, edit, rename, and delete working copies of their own, keep their
 * rules, submit them for review and withdraw them, update a stale one from Approved after seeing
 * what that brings, and read a catalog's change history.
 */
@RestController
class CatalogController {
  private final Catalogs catalogs;
  private final WorkingCopies workingCopies;
  private final CatalogEdits edits;
  private final Submissions submissions;
  private final Reviews reviews;
  private final Updates updates;
  private final ChangeHistory history;
  private final RuleSuggestions suggestions;
  private final SubmissionSummaries summaries;
  private final AppUsers people;
  private final RoleHierarchy roles;

  CatalogController(
      Catalogs catalogs,
      WorkingCopies workingCopies,
      CatalogEdits edits,
      Submissions submissions,
      Reviews reviews,
      Updates updates,
      ChangeHistory history,
      RuleSuggestions suggestions,
      SubmissionSummaries summaries,
      AppUsers people,
      RoleHierarchy roles) {
    this.suggestions = suggestions;
    this.summaries = summaries;
    this.reviews = reviews;
    this.updates = updates;
    this.roles = roles;
    this.catalogs = catalogs;
    this.workingCopies = workingCopies;
    this.edits = edits;
    this.submissions = submissions;
    this.history = history;
    this.people = people;
  }

  /** The lineages that have an Approved version, each with its current one. */
  @GetMapping("/api/lineages")
  List<LineageSummary> lineages() {
    return catalogs.lineages();
  }

  @GetMapping("/api/lineages/{id}/versions")
  List<VersionSummary> versions(@PathVariable long id) {
    if (!catalogs.hasLineage(id)) {
      throw ApiException.notFound();
    }
    return catalogs.versions(id);
  }

  /** The caller's working copies, each with how many Errors and Warnings it has today. */
  @GetMapping(value = "/api/catalogs", params = "scope=mine")
  List<ListedWorkingCopy> mine(Authentication caller) {
    var owner = people.idOf(caller);

    // Each working copy's counts are kept with the revisions of the copy and of the library they
    // were worked out at. A copy is read whole and validated, twelve queries with the keeping, only
    // when it or the library has changed since it was last listed: twenty listed from their kept
    // counts take two queries in all, the caller's lookup and the list's own.
    return workingCopies.ownedBy(owner).stream()
        .map(
            listed -> {
              var copy = listed.copy();
              var counts = listed.counts() != null ? listed.counts() : counted(copy.id(), owner);

              return new ListedWorkingCopy(
                  copy.id(),
                  copy.name(),
                  copy.vehicleLine(),
                  copy.modelYear(),
                  copy.status(),
                  copy.revision(),
                  copy.updatedAt(),
                  counts.errors(),
                  counts.warnings(),
                  listed.stale());
            })
        .toList();
  }

  /**
   * Reads the owner's working copy whole and validates it, and keeps what that finds for the next
   * time it is listed. A working copy that is gone counts nothing.
   */
  private Counts counted(long id, long owner) {
    return catalogs
        .findWithLibraryRevision(id, owner)
        .map(
            read -> {
              var issues = read.catalog().issues();
              var errors =
                  issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();
              var counts = new Counts(errors, issues.size() - errors);
              workingCopies.keepCounts(
                  id, read.catalog().snapshot().revision(), read.libraryRevision(), counts);
              return counts;
            })
        .orElse(new Counts(0, 0));
  }

  /**
   * A working copy as its owner's list shows it.
   *
   * @param revision what an edit made from the list, such as deleting it, names
   * @param errors how many Errors validation finds in it against the library as it is today
   * @param warnings how many Warnings
   * @param stale whether its base is no longer its lineage's current Approved
   */
  record ListedWorkingCopy(
      long id,
      String name,
      String vehicleLine,
      int modelYear,
      Status status,
      long revision,
      Instant updatedAt,
      long errors,
      long warnings,
      boolean stale) {}

  /**
   * The catalogs that are waiting for review, for a manager or an admin. The caller's own are among
   * them, marked as theirs.
   */
  @GetMapping(value = "/api/catalogs", params = "scope=review")
  List<Submitted> toReview(Authentication caller) {
    // The owner's list has the same address, so the role is asked for here and not by the address.
    if (!reviews(caller)) {
      throw ApiException.forbidden("FORBIDDEN", "Only a manager or an admin reviews catalogs.");
    }
    return workingCopies.submitted(people.idOf(caller));
  }

  /** Whether the caller reviews catalogs, as a manager and an admin do. */
  private boolean reviews(Authentication caller) {
    return Role.heldBy(caller.getAuthorities(), roles).contains(Role.MANAGER);
  }

  /** What a new working copy for the vehicle line and model year would start from. */
  @GetMapping("/api/catalogs/start-point")
  StartPoint startPoint(@RequestParam long vehicleLineId, @RequestParam int modelYear) {
    return workingCopies.startPoint(vehicleLineId, modelYear);
  }

  /** Creates a working copy that the caller owns. */
  @PostMapping("/api/catalogs")
  @ResponseStatus(HttpStatus.CREATED)
  WorkingCopy create(@Valid @RequestBody NewWorkingCopy given, Authentication caller) {
    return workingCopies.create(people.idOf(caller), given);
  }

  /**
   * Sets cells of the caller's working copy. The save names the revision it was made from in {@code
   * If-Match} and answers with the one the catalog is at afterwards, also as the entity tag.
   */
  @PutMapping("/api/catalogs/{id}/cells")
  ResponseEntity<Edited> setCells(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody List<CellChange> cells,
      Authentication caller) {
    return answered(edits.setCells(id, people.idOf(caller), ifMatch, cells));
  }

  /** Adds library trims to the caller's working copy. */
  @PostMapping("/api/catalogs/{id}/trims")
  ResponseEntity<Edited> addTrims(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody TrimsToAdd given,
      Authentication caller) {
    return answered(edits.addTrims(id, people.idOf(caller), ifMatch, given.trimIds()));
  }

  /**
   * Removes a trim from the caller's working copy, with its offerings and their cells, and with the
   * rules that covered no other trim.
   */
  @DeleteMapping("/api/catalogs/{id}/trims/{trimId}")
  ResponseEntity<Edited> removeTrim(
      @PathVariable long id,
      @PathVariable long trimId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return answered(edits.removeTrim(id, people.idOf(caller), ifMatch, trimId));
  }

  /** Adds library regions to the caller's working copy. */
  @PostMapping("/api/catalogs/{id}/regions")
  ResponseEntity<Edited> addRegions(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RegionCodes given,
      Authentication caller) {
    return answered(edits.addRegions(id, people.idOf(caller), ifMatch, given.regionCodes()));
  }

  /**
   * Removes a region from the caller's working copy, with its offerings and their cells, and with
   * the rules that covered no other region.
   */
  @DeleteMapping("/api/catalogs/{id}/regions/{regionCode}")
  ResponseEntity<Edited> removeRegion(
      @PathVariable long id,
      @PathVariable String regionCode,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return answered(edits.removeRegion(id, people.idOf(caller), ifMatch, regionCode));
  }

  /** Says in which of the catalog's regions a trim is sold: in exactly the ones given. */
  @PutMapping("/api/catalogs/{id}/trims/{trimId}/regions")
  ResponseEntity<Edited> sellIn(
      @PathVariable long id,
      @PathVariable long trimId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RegionCodes given,
      Authentication caller) {
    return answered(edits.sellIn(id, people.idOf(caller), ifMatch, trimId, given.regionCodes()));
  }

  /** Renames the caller's working copy. */
  @PatchMapping("/api/catalogs/{id}")
  ResponseEntity<Edited> rename(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody NewName given,
      Authentication caller) {
    return answered(edits.rename(id, people.idOf(caller), ifMatch, given.name()));
  }

  /** Deletes the caller's working copy, with its contents and its change history. */
  @DeleteMapping("/api/catalogs/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    edits.delete(id, people.idOf(caller), ifMatch);
  }

  /** Adds library features to the caller's working copy as feature rows. */
  @PostMapping("/api/catalogs/{id}/features")
  ResponseEntity<Edited> addFeatures(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody FeaturesToAdd given,
      Authentication caller) {
    return answered(edits.addFeatures(id, people.idOf(caller), ifMatch, given.featureIds()));
  }

  /**
   * Removes a feature row from the caller's working copy, with its cells. A row that a rule of the
   * catalog names is removed only together with those rules, which the caller asks for.
   */
  @DeleteMapping("/api/catalogs/{id}/features/{featureId}")
  ResponseEntity<Edited> removeFeature(
      @PathVariable long id,
      @PathVariable long featureId,
      @RequestParam(defaultValue = "false") boolean removeRules,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return answered(edits.removeFeature(id, people.idOf(caller), ifMatch, featureId, removeRules));
  }

  /**
   * Adds a rule to the caller's working copy. An Excludes rule is added as a pair for each of its
   * targets.
   */
  @PostMapping("/api/catalogs/{id}/rules")
  ResponseEntity<Edited> addRule(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RuleContent given,
      Authentication caller) {
    return answered(edits.addRule(id, people.idOf(caller), ifMatch, given));
  }

  /**
   * Suggests a rule for the caller's working copy from a sentence. Nothing is saved: the rule is
   * the caller's to check and to add.
   */
  @PostMapping("/api/catalogs/{id}/rule-suggestions")
  RuleSuggestions.Answer suggestRule(
      @PathVariable long id, @RequestBody Described given, Authentication caller) {
    return suggestions.suggest(id, people.idOf(caller), given.sentence());
  }

  /** A rule as a person describes it. */
  record Described(String sentence) {}

  /** Changes a rule of the caller's working copy, and its pair with it. */
  @PutMapping("/api/catalogs/{id}/rules/{ruleKey}")
  ResponseEntity<Edited> changeRule(
      @PathVariable long id,
      @PathVariable UUID ruleKey,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RuleContent given,
      Authentication caller) {
    return answered(edits.changeRule(id, people.idOf(caller), ifMatch, ruleKey, given));
  }

  /** Deletes a rule of the caller's working copy, and its pair with it. */
  @DeleteMapping("/api/catalogs/{id}/rules/{ruleKey}")
  ResponseEntity<Edited> deleteRule(
      @PathVariable long id,
      @PathVariable UUID ruleKey,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return answered(edits.deleteRule(id, people.idOf(caller), ifMatch, ruleKey));
  }

  /**
   * Submits the caller's working copy for review, with a note if they give one. It names the
   * revision it was made from, as an edit does.
   */
  @PostMapping("/api/catalogs/{id}/submit")
  ResponseEntity<Edited> submit(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) Submission given,
      Authentication caller) {
    var note = given == null ? null : given.note();

    return answered(submissions.submit(id, people.idOf(caller), ifMatch, note));
  }

  /** Makes the caller's Submitted catalog a Draft again. */
  @PostMapping("/api/catalogs/{id}/withdraw")
  ResponseEntity<Edited> withdraw(@PathVariable long id, Authentication caller) {
    return answered(submissions.withdraw(id, people.idOf(caller)));
  }

  /**
   * Approves a Submitted catalog that is not the caller's own, which makes it the next Approved
   * version of its lineage. It names the revision the reviewer saw.
   */
  @PostMapping("/api/catalogs/{id}/approve")
  @RequiresRole(Role.MANAGER)
  ResponseEntity<Edited> approve(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) Decision given,
      Authentication caller) {
    var comment = given == null ? null : given.comment();

    return answered(reviews.approve(id, people.idOf(caller), ifMatch, comment));
  }

  /**
   * Rejects a Submitted catalog that is not the caller's own, with the reason, which sends it back
   * to its owner as a Draft.
   */
  @PostMapping("/api/catalogs/{id}/reject")
  @RequiresRole(Role.MANAGER)
  ResponseEntity<Edited> reject(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) Decision given,
      Authentication caller) {
    var comment = given == null ? null : given.comment();

    return answered(reviews.reject(id, people.idOf(caller), ifMatch, comment));
  }

  /** What a reviewer says with a decision. A rejection needs it. */
  record Decision(String comment) {}

  /** What an owner says when they submit a catalog: a note, or nothing. */
  record Submission(String note) {}

  /**
   * A write answers with the revision the catalog is at afterwards, which is also the entity tag,
   * with the issues the catalog then has and whether it is stale, as the caller may see it, and
   * with the rules it deleted along the way.
   */
  private static ResponseEntity<Edited> answered(Outcome outcome) {
    return ResponseEntity.ok()
        .eTag(String.valueOf(outcome.revision()))
        .body(
            new Edited(
                outcome.revision(), outcome.issues(), outcome.rulesDeleted(), outcome.stale()));
  }

  /** The name a working copy is to have. */
  record NewName(String name) {}

  /** The library trims to add to a catalog, by their identities and nothing else. */
  record TrimsToAdd(List<Long> trimIds) {}

  /** The library features to add to a catalog as feature rows, by their identities. */
  record FeaturesToAdd(List<Long> featureIds) {}

  /** Regions, by their codes and nothing else. */
  record RegionCodes(List<String> regionCodes) {}

  /**
   * The catalog's change history, newest first and a page at a time, for anyone who may open the
   * catalog.
   */
  @GetMapping("/api/catalogs/{id}/changes")
  ChangePage changes(
      @PathVariable long id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      Authentication caller) {
    if (!catalogs.opensFor(id, people.idOf(caller), reviews(caller))) {
      throw ApiException.notFound();
    }
    return history.page(id, page, size);
  }

  /**
   * What changed from another catalog to this one, for anyone who may open both. The other is named
   * by its id, or as {@code base}: the catalog this one was copied from, or an empty catalog when
   * it started empty.
   */
  /**
   * The summary the language model wrote of what a Submitted catalog changes, for whoever may open
   * its review: pending while it is being written, ready, or unavailable with the reason.
   */
  @GetMapping("/api/catalogs/{id}/summary")
  SubmissionSummaries.Summary summary(@PathVariable long id, Authentication caller) {
    var catalog =
        catalogs
            .find(id, people.idOf(caller), reviews(caller))
            .orElseThrow(ApiException::notFound)
            .snapshot();

    return summaries.find(id, catalog.revision()).orElseThrow(ApiException::notFound);
  }

  @GetMapping("/api/catalogs/{id}/diff")
  Changes diff(@PathVariable long id, @RequestParam String against, Authentication caller) {
    var viewer = people.idOf(caller);
    var catalog = catalogs.find(id, viewer, reviews(caller)).orElseThrow(ApiException::notFound);
    Long other;
    if (against.equals("base")) {
      other = catalog.base() == null ? null : catalog.base().catalogId();
    } else if (against.matches("\\d{1,18}")) {
      other = Long.valueOf(against);
    } else {
      throw ApiException.badRequest("Name the catalog to compare with by its id, or as base.");
    }
    var before =
        other == null
            ? CatalogSnapshot.empty()
            : catalogs.contents(other, viewer, reviews(caller)).orElseThrow(ApiException::notFound);

    return Diff.between(before, catalog.snapshot());
  }

  /**
   * What an update from its lineage's current Approved would do to the caller's stale Draft: what
   * it brings in without asking, and the conflicts the owner would have to settle. It saves
   * nothing.
   */
  @PostMapping("/api/catalogs/{id}/merge-preview")
  Preview mergePreview(@PathVariable long id, Authentication caller) {
    return updates.preview(id, people.idOf(caller));
  }

  /**
   * Updates the caller's stale Draft from its lineage's current Approved, with each conflict
   * settled for the side the caller chose. It names the Approved version the caller saw the update
   * worked out against, and is refused when another is the current one by now.
   */
  @PostMapping("/api/catalogs/{id}/merge")
  ResponseEntity<Edited> merge(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody Update given,
      Authentication caller) {
    return answered(
        updates.apply(
            id, people.idOf(caller), ifMatch, given.approvedCatalogId(), given.resolutions()));
  }

  /**
   * An update from Approved as its owner asks for it.
   *
   * @param approvedCatalogId the Approved version the update was worked out against
   * @param resolutions for each conflict, by its id, whose version is taken
   */
  record Update(Long approvedCatalogId, Map<String, Merge.Side> resolutions) {}

  /**
   * What a saved edit answers with.
   *
   * @param rulesDeleted the rules that went with a feature row, a trim, or a region the edit
   *     removed, in words
   * @param stale whether the catalog is stale by now: another catalog of its lineage may have been
   *     approved while its owner was editing
   */
  record Edited(long revision, List<Issue> issues, List<String> rulesDeleted, boolean stale) {}

  /** A catalog with its contents. Its revision is the entity tag, which later writes name. */
  @GetMapping("/api/catalogs/{id}")
  ResponseEntity<CatalogView> catalog(@PathVariable long id, Authentication caller) {
    var catalog =
        catalogs.find(id, people.idOf(caller), reviews(caller)).orElseThrow(ApiException::notFound);

    return ResponseEntity.ok().eTag(String.valueOf(catalog.snapshot().revision())).body(catalog);
  }
}
