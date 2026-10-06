package dev.rgonz.catalog.catalog;

import java.util.List;

/**
 * A catalog's contents as one in-memory value: its trims, regions, offerings, feature rows, and
 * cells, with the labels to show for them. An Approved version carries the labels it had when it
 * was approved; a working copy carries the library's current ones.
 *
 * @param regions in the library's order
 * @param cells only Standard and Available; a missing cell is Not offered
 */
public record CatalogSnapshot(
    long catalogId,
    long lineageId,
    Status status,
    long revision,
    List<Trim> trims,
    List<Region> regions,
    List<Offering> offerings,
    List<FeatureRow> featureRows,
    List<Cell> cells) {

  /** Whether a catalog is a working copy, in Draft or Submitted, or an Approved version. */
  public enum Status {
    DRAFT,
    SUBMITTED,
    APPROVED
  }

  /** What a cell states: Standard, Available, or Not offered, which is never stored. */
  public enum Availability {
    S,
    A,
    N
  }

  /** A trim the catalog has added. */
  public record Trim(long id, String name, int sortOrder) {}

  /** A region the catalog has added. */
  public record Region(String code, String name) {}

  /** One trim sold in one region. */
  public record Offering(long trimId, String regionCode) {}

  /** A feature the catalog has added. Its code never changes, so it is no label. */
  public record FeatureRow(long id, String code, String name, String categoryCode) {}

  /** The availability of one feature in one offering. */
  public record Cell(long featureId, long trimId, String regionCode, Availability availability) {}
}
