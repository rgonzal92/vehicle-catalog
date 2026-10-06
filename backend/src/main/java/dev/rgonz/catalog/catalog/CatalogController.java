package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Catalogs.CatalogView;
import dev.rgonz.catalog.catalog.Catalogs.LineageSummary;
import dev.rgonz.catalog.catalog.Catalogs.VersionSummary;
import dev.rgonz.catalog.core.ApiException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Shows everyone with a role the lineages, their Approved versions, and a catalog's contents. */
@RestController
class CatalogController {
  private final Catalogs catalogs;

  CatalogController(Catalogs catalogs) {
    this.catalogs = catalogs;
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

  /** A catalog with its contents. Its revision is the entity tag, which later writes name. */
  @GetMapping("/api/catalogs/{id}")
  ResponseEntity<CatalogView> catalog(@PathVariable long id) {
    var catalog = catalogs.find(id).orElseThrow(ApiException::notFound);

    return ResponseEntity.ok().eTag("\"" + catalog.snapshot().revision() + "\"").body(catalog);
  }
}
