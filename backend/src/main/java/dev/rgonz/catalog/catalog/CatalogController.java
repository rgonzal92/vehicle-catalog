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
import dev.rgonz.catalog.catalog.WorkingCopies.NewWorkingCopy;
import dev.rgonz.catalog.catalog.WorkingCopies.StartPoint;
import dev.rgonz.catalog.catalog.WorkingCopies.Submitted;
import dev.rgonz.catalog.catalog.WorkingCopies.WorkingCopy;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.AppUsers;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
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
 * managers and admins the catalogs that are waiting for review, and lets each of them create, edit,
 * rename, and delete working copies of their own, keep their rules, submit them for review and
 * withdraw them, and read a catalog's change history.
 */
@RestController
class CatalogController {
  private final Catalogs catalogs;
  private final WorkingCopies workingCopies;
  private final CatalogEdits edits;
  private final Submissions submissions;
  private final ChangeHistory history;
  private final AppUsers people;
  private final RoleHierarchy roles;

  CatalogController(
      Catalogs catalogs,
      WorkingCopies workingCopies,
      CatalogEdits edits,
      Submissions submissions,
      ChangeHistory history,
      AppUsers people,
      RoleHierarchy roles) {
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

    // ponytail: each working copy is read whole and validated, ten queries apiece for the 20 an
    // owner can have at most. Keep the counts with the catalog if this list gets slow.
    return workingCopies.ownedBy(owner).stream()
        .map(
            copy -> {
              var issues =
                  catalogs.find(copy.id(), owner).map(CatalogView::issues).orElseGet(List::of);
              var errors =
                  issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count();

              return new ListedWorkingCopy(
                  copy.id(),
                  copy.name(),
                  copy.vehicleLine(),
                  copy.modelYear(),
                  copy.status(),
                  copy.revision(),
                  copy.updatedAt(),
                  errors,
                  issues.size() - errors);
            })
        .toList();
  }

  /**
   * A working copy as its owner's list shows it.
   *
   * @param revision what an edit made from the list, such as deleting it, names
   * @param errors how many Errors validation finds in it against the library as it is today
   * @param warnings how many Warnings
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
      long warnings) {}

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
    return saved(id, caller, edits.setCells(id, people.idOf(caller), ifMatch, cells));
  }

  /** Adds library trims to the caller's working copy. */
  @PostMapping("/api/catalogs/{id}/trims")
  ResponseEntity<Edited> addTrims(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody TrimsToAdd given,
      Authentication caller) {
    return saved(id, caller, edits.addTrims(id, people.idOf(caller), ifMatch, given.trimIds()));
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
    var removed = edits.removeTrim(id, people.idOf(caller), ifMatch, trimId);

    return saved(id, caller, removed.revision(), removed.rulesDeleted());
  }

  /** Adds library regions to the caller's working copy. */
  @PostMapping("/api/catalogs/{id}/regions")
  ResponseEntity<Edited> addRegions(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RegionCodes given,
      Authentication caller) {
    return saved(
        id, caller, edits.addRegions(id, people.idOf(caller), ifMatch, given.regionCodes()));
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
    var removed = edits.removeRegion(id, people.idOf(caller), ifMatch, regionCode);

    return saved(id, caller, removed.revision(), removed.rulesDeleted());
  }

  /** Says in which of the catalog's regions a trim is sold: in exactly the ones given. */
  @PutMapping("/api/catalogs/{id}/trims/{trimId}/regions")
  ResponseEntity<Edited> sellIn(
      @PathVariable long id,
      @PathVariable long trimId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RegionCodes given,
      Authentication caller) {
    return saved(
        id, caller, edits.sellIn(id, people.idOf(caller), ifMatch, trimId, given.regionCodes()));
  }

  /** Renames the caller's working copy. */
  @PatchMapping("/api/catalogs/{id}")
  ResponseEntity<Edited> rename(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody NewName given,
      Authentication caller) {
    return saved(id, caller, edits.rename(id, people.idOf(caller), ifMatch, given.name()));
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
    return saved(
        id, caller, edits.addFeatures(id, people.idOf(caller), ifMatch, given.featureIds()));
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
    var removed = edits.removeFeature(id, people.idOf(caller), ifMatch, featureId, removeRules);

    return saved(id, caller, removed.revision(), removed.rulesDeleted());
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
    return saved(id, caller, edits.addRule(id, people.idOf(caller), ifMatch, given));
  }

  /** Changes a rule of the caller's working copy, and its pair with it. */
  @PutMapping("/api/catalogs/{id}/rules/{ruleKey}")
  ResponseEntity<Edited> changeRule(
      @PathVariable long id,
      @PathVariable UUID ruleKey,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RuleContent given,
      Authentication caller) {
    return saved(id, caller, edits.changeRule(id, people.idOf(caller), ifMatch, ruleKey, given));
  }

  /** Deletes a rule of the caller's working copy, and its pair with it. */
  @DeleteMapping("/api/catalogs/{id}/rules/{ruleKey}")
  ResponseEntity<Edited> deleteRule(
      @PathVariable long id,
      @PathVariable UUID ruleKey,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return saved(id, caller, edits.deleteRule(id, people.idOf(caller), ifMatch, ruleKey));
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

    return saved(id, caller, submissions.submit(id, people.idOf(caller), ifMatch, note));
  }

  /** Makes the caller's Submitted catalog a Draft again. */
  @PostMapping("/api/catalogs/{id}/withdraw")
  ResponseEntity<Edited> withdraw(@PathVariable long id, Authentication caller) {
    return saved(id, caller, submissions.withdraw(id, people.idOf(caller)));
  }

  /** What an owner says when they submit a catalog: a note, or nothing. */
  record Submission(String note) {}

  private ResponseEntity<Edited> saved(long id, Authentication caller, long revision) {
    return saved(id, caller, revision, List.of());
  }

  /**
   * An edit answers with the revision the catalog is at afterwards, which is also the entity tag,
   * with every issue the catalog then has, and with the rules it deleted along the way. The
   * revision is the one the edit was made from when the edit changed nothing.
   */
  private ResponseEntity<Edited> saved(
      long id, Authentication caller, long revision, List<String> rulesDeleted) {
    var issues =
        catalogs.find(id, people.idOf(caller)).map(CatalogView::issues).orElseGet(List::of);

    return ResponseEntity.ok()
        .eTag(String.valueOf(revision))
        .body(new Edited(revision, issues, rulesDeleted));
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
   * What a saved edit answers with.
   *
   * @param rulesDeleted the rules that went with a feature row, a trim, or a region the edit
   *     removed, in words
   */
  record Edited(long revision, List<Issue> issues, List<String> rulesDeleted) {}

  /** A catalog with its contents. Its revision is the entity tag, which later writes name. */
  @GetMapping("/api/catalogs/{id}")
  ResponseEntity<CatalogView> catalog(@PathVariable long id, Authentication caller) {
    var catalog =
        catalogs.find(id, people.idOf(caller), reviews(caller)).orElseThrow(ApiException::notFound);

    return ResponseEntity.ok().eTag(String.valueOf(catalog.snapshot().revision())).body(catalog);
  }
}
