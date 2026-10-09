package dev.rgonz.catalog.catalog;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * A catalog's contents as one in-memory value: its trims, regions, offerings, feature rows, cells,
 * and rules, with the labels to show for them. An Approved version carries the labels it had when
 * it was approved; a working copy carries the library's current ones.
 *
 * @param regions in the library's order
 * @param cells only Standard and Available; a missing cell is Not offered
 * @param rules the rules that belong to the catalog, by the code of their source
 */
record CatalogSnapshot(
    long catalogId,
    long lineageId,
    Status status,
    long revision,
    List<Trim> trims,
    List<Region> regions,
    List<Offering> offerings,
    List<FeatureRow> featureRows,
    List<Cell> cells,
    List<Rule> rules) {

  /** A catalog with nothing in it, which is what a catalog that started empty is compared with. */
  static CatalogSnapshot empty() {
    return new CatalogSnapshot(
        0, 0, Status.DRAFT, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /** The catalog's offerings as its matrix has them: region by region, and trims in order. */
  List<Offering> offeringsInOrder() {
    var regionPlaces = new HashMap<String, Integer>();
    regions.forEach(region -> regionPlaces.put(region.code(), regionPlaces.size()));
    var trimPlaces = new HashMap<Long, Integer>();
    trims.forEach(trim -> trimPlaces.put(trim.id(), trim.sortOrder()));
    return offerings.stream()
        .sorted(
            Comparator.comparing((Offering offering) -> regionPlaces.get(offering.regionCode()))
                .thenComparing(offering -> trimPlaces.get(offering.trimId()))
                .thenComparing(Offering::trimId))
        .toList();
  }

  /** Whether a catalog is a working copy, in Draft or Submitted, or an Approved version. */
  enum Status {
    DRAFT,
    SUBMITTED,
    APPROVED
  }

  /** What a cell states: Standard, Available, or Not offered, which is never stored. */
  enum Availability {
    S,
    A,
    N
  }

  /** A trim the catalog has added. */
  record Trim(long id, String name, int sortOrder) {}

  /** A region the catalog has added. */
  record Region(String code, String name) {}

  /** One trim sold in one region. */
  record Offering(long trimId, String regionCode) {}

  /** Whether a feature stands alone or is a package, which brings other features with it. */
  enum Kind {
    FEATURE,
    PACKAGE
  }

  /** A feature the catalog has added. Its code and its kind never change, so they are no labels. */
  record FeatureRow(long id, String code, Kind kind, String name, String categoryCode) {}

  /** The availability of one feature in one offering. */
  record Cell(long featureId, long trimId, String regionCode, Availability availability) {}
}
