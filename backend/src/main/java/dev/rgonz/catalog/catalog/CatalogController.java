package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogEdits.CellChange;
import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.LineageSummary;
import dev.rgonz.catalog.catalog.Catalogs.VersionSummary;
import dev.rgonz.catalog.catalog.WorkingCopies.NewWorkingCopy;
import dev.rgonz.catalog.catalog.WorkingCopies.StartPoint;
import dev.rgonz.catalog.catalog.WorkingCopies.WorkingCopy;
import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.user.AppUsers;
import jakarta.validation.Valid;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
 * lets each of them create and edit working copies of their own.
 */
@RestController
class CatalogController {
  private final Catalogs catalogs;
  private final WorkingCopies workingCopies;
  private final CatalogEdits edits;
  private final AppUsers people;

  CatalogController(
      Catalogs catalogs, WorkingCopies workingCopies, CatalogEdits edits, AppUsers people) {
    this.catalogs = catalogs;
    this.workingCopies = workingCopies;
    this.edits = edits;
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
    var revision = edits.setCells(id, people.idOf(caller), expectedRevision(ifMatch), cells);

    return ResponseEntity.ok().eTag(String.valueOf(revision)).body(new Edited(revision));
  }

  /**
   * The revision an edit says it was made from: the entity tag the catalog was last read or saved
   * with, sent back as it was given.
   */
  private static long expectedRevision(String ifMatch) {
    if (ifMatch == null) {
      throw ApiException.revisionRequired();
    }
    var revision = REVISION.matcher(ifMatch.strip());
    if (!revision.matches()) {
      throw ApiException.badRequest(
          "If-Match takes the catalog's revision in quotes, as in \"42\".");
    }
    return Long.parseLong(revision.group(1));
  }

  private static final Pattern REVISION = Pattern.compile("\"(\\d{1,18})\"");

  /** What a saved edit answers with. */
  record Edited(long revision) {}

  /** A catalog with its contents. Its revision is the entity tag, which later writes name. */
  @GetMapping("/api/catalogs/{id}")
  ResponseEntity<CatalogView> catalog(@PathVariable long id, Authentication caller) {
    var catalog = catalogs.find(id, people.idOf(caller)).orElseThrow(ApiException::notFound);

    return ResponseEntity.ok().eTag(String.valueOf(catalog.snapshot().revision())).body(catalog);
  }
}
