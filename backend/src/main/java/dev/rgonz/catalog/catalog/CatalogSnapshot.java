package dev.rgonz.catalog.catalog;

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
