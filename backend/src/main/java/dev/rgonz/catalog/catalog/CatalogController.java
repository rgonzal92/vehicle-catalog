package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogEdits.CellChange;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.LineageSummary;
import dev.rgonz.catalog.catalog.Catalogs.VersionSummary;
import dev.rgonz.catalog.catalog.ChangeHistory.ChangePage;
import dev.rgonz.catalog.catalog.WorkingCopies.NewWorkingCopy;
import dev.rgonz.catalog.catalog.WorkingCopies.StartPoint;
import dev.rgonz.catalog.catalog.WorkingCopies.WorkingCopy;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.user.AppUsers;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows everyone with a role the lineages, their Approved versions, and a catalog's contents, and
 * lets each of them create and edit working copies of their own and read a catalog's change
 * history.
 */
@RestController
class CatalogController {
  private final Catalogs catalogs;
  private final WorkingCopies workingCopies;
  private final CatalogEdits edits;
  private final ChangeHistory history;
  private final AppUsers people;

  CatalogController(
      Catalogs catalogs,
      WorkingCopies workingCopies,
      CatalogEdits edits,
      ChangeHistory history,
      AppUsers people) {
    this.catalogs = catalogs;
    this.workingCopies = workingCopies;
    this.edits = edits;
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

  /** The caller's working copies. */
  @GetMapping(value = "/api/catalogs", params = "scope=mine")
  List<WorkingCopy> mine(Authentication caller) {
    return workingCopies.ownedBy(people.idOf(caller));
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
   * If-Match} and answers with the new one, also as the entity tag.
   */
  @PutMapping("/api/catalogs/{id}/cells")
  ResponseEntity<Edited> setCells(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody List<CellChange> cells,
      Authentication caller) {
    return saved(edits.setCells(id, people.idOf(caller), ifMatch, cells));
  }

  /** Adds library trims to the caller's working copy. */
  @PostMapping("/api/catalogs/{id}/trims")
  ResponseEntity<Edited> addTrims(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody TrimsToAdd given,
      Authentication caller) {
    return saved(edits.addTrims(id, people.idOf(caller), ifMatch, given.trimIds()));
  }

  /** Removes a trim from the caller's working copy, with its offerings and their cells. */
  @DeleteMapping("/api/catalogs/{id}/trims/{trimId}")
  ResponseEntity<Edited> removeTrim(
      @PathVariable long id,
      @PathVariable long trimId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return saved(edits.removeTrim(id, people.idOf(caller), ifMatch, trimId));
  }

  /** Adds library regions to the caller's working copy. */
  @PostMapping("/api/catalogs/{id}/regions")
  ResponseEntity<Edited> addRegions(
      @PathVariable long id,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RegionCodes given,
      Authentication caller) {
    return saved(edits.addRegions(id, people.idOf(caller), ifMatch, given.regionCodes()));
  }

  /** Removes a region from the caller's working copy, with its offerings and their cells. */
  @DeleteMapping("/api/catalogs/{id}/regions/{regionCode}")
  ResponseEntity<Edited> removeRegion(
      @PathVariable long id,
      @PathVariable String regionCode,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      Authentication caller) {
    return saved(edits.removeRegion(id, people.idOf(caller), ifMatch, regionCode));
  }

  /** Says in which of the catalog's regions a trim is sold: in exactly the ones given. */
  @PutMapping("/api/catalogs/{id}/trims/{trimId}/regions")
  ResponseEntity<Edited> sellIn(
      @PathVariable long id,
      @PathVariable long trimId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody RegionCodes given,
      Authentication caller) {
    return saved(edits.sellIn(id, people.idOf(caller), ifMatch, trimId, given.regionCodes()));
  }

  /** A saved edit answers with the catalog's new revision, which is also the entity tag. */
  private static ResponseEntity<Edited> saved(long revision) {
    return ResponseEntity.ok().eTag(String.valueOf(revision)).body(new Edited(revision));
  }

  /** The library trims to add to a catalog, by their identities and nothing else. */
  record TrimsToAdd(List<Long> trimIds) {}

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
    if (!catalogs.opensFor(id, people.idOf(caller))) {
      throw ApiException.notFound();
    }
    return history.page(id, page, size);
  }

  /** What a saved edit answers with. */
  record Edited(long revision) {}

  /** A catalog with its contents. Its revision is the entity tag, which later writes name. */
  @GetMapping("/api/catalogs/{id}")
  ResponseEntity<CatalogView> catalog(@PathVariable long id, Authentication caller) {
    var catalog = catalogs.find(id, people.idOf(caller)).orElseThrow(ApiException::notFound);

    return ResponseEntity.ok().eTag(String.valueOf(catalog.snapshot().revision())).body(catalog);
  }
}
