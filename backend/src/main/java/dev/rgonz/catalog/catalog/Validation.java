package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.catalog.Issue.Code;
import dev.rgonz.catalog.catalog.Issue.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out a catalog's issues from its contents and from the library's state today. It works every
 * issue out afresh each time, and knows nothing of the database or of the web.
 */
final class Validation {
  private Validation() {}

  /**
   * What validation needs of the library, as it is today and not as an Approved version recorded
   * it: which of the catalog's features are retired, and which of its trims and regions are
   * inactive.
   */
  record Library(
      Set<Long> retiredFeatureIds, Set<Long> inactiveTrimIds, Set<String> inactiveRegionCodes) {}

  /** The catalog's issues, Errors before Warnings. */
  static List<Issue> issues(CatalogSnapshot catalog, Library library) {
    var found = new ArrayList<Issue>();
    Map<String, Region> regions =
        catalog.regions().stream().collect(Collectors.toMap(Region::code, Function.identity()));
    var trimsSold = catalog.offerings().stream().map(Offering::trimId).collect(Collectors.toSet());
    var regionsSoldIn =
        catalog.offerings().stream().map(Offering::regionCode).collect(Collectors.toSet());
    var offeringsWithContent =
        catalog.cells().stream()
            .map(cell -> new Offering(cell.trimId(), cell.regionCode()))
            .collect(Collectors.toSet());
    var featuresOffered = catalog.cells().stream().map(Cell::featureId).collect(Collectors.toSet());

    if (catalog.trims().isEmpty()) {
      found.add(ofCatalog(Code.NO_TRIMS, "The catalog has no trims."));
    }
    if (catalog.regions().isEmpty()) {
      found.add(ofCatalog(Code.NO_REGIONS, "The catalog has no regions."));
    }
    if (catalog.featureRows().isEmpty()) {
      found.add(ofCatalog(Code.NO_FEATURES, "The catalog has no feature rows."));
    }
    for (Trim trim : catalog.trims()) {
      if (!trimsSold.contains(trim.id())) {
        found.add(ofTrim(Code.TRIM_NOT_SOLD, trim, "%s is sold in no region."));
      }
      if (library.inactiveTrimIds().contains(trim.id())) {
        found.add(ofTrim(Code.TRIM_INACTIVE, trim, "%s is inactive."));
      }
    }
    for (Region region : catalog.regions()) {
      if (!regionsSoldIn.contains(region.code())) {
        found.add(ofRegion(Code.REGION_WITHOUT_TRIMS, region, "No trim is sold in %s."));
      }
      if (library.inactiveRegionCodes().contains(region.code())) {
        found.add(ofRegion(Code.REGION_INACTIVE, region, "%s is inactive."));
      }
    }
    for (Trim trim : catalog.trims()) {
      for (Region region : catalog.regions()) {
        var offering = new Offering(trim.id(), region.code());
        if (catalog.offerings().contains(offering) && !offeringsWithContent.contains(offering)) {
          found.add(
              new Issue(
                  Code.OFFERING_EMPTY,
                  Severity.ERROR,
                  trim.id(),
                  region.code(),
                  null,
                  List.of(),
                  null,
                  "%s in %s has no Standard or Available feature."
                      .formatted(trim.name(), regions.get(region.code()).name())));
        }
      }
    }
    for (FeatureRow feature : catalog.featureRows()) {
      if (library.retiredFeatureIds().contains(feature.id())) {
        found.add(ofFeature(Code.FEATURE_RETIRED, Severity.ERROR, feature, "%s is retired."));
      }
      // A catalog with no offering says so by its trims and regions, and not once more for each
      // row.
      if (!catalog.offerings().isEmpty() && !featuresOffered.contains(feature.id())) {
        found.add(
            ofFeature(
                Code.FEATURE_NEVER_OFFERED,
                Severity.WARNING,
                feature,
                "%s is not offered in any offering."));
      }
    }

    // The sort is stable, so within a severity the issues keep the order they were found in.
    found.sort(Comparator.comparing(Issue::severity));
    return List.copyOf(found);
  }

  private static Issue ofCatalog(Code code, String message) {
    return new Issue(code, Severity.ERROR, null, null, null, List.of(), null, message);
  }

  private static Issue ofTrim(Code code, Trim trim, String message) {
    return new Issue(
        code,
        Severity.ERROR,
        trim.id(),
        null,
        null,
        List.of(),
        null,
        message.formatted(trim.name()));
  }

  private static Issue ofRegion(Code code, Region region, String message) {
    return new Issue(
        code,
        Severity.ERROR,
        null,
        region.code(),
        null,
        List.of(),
        null,
        message.formatted(region.name()));
  }

  private static Issue ofFeature(Code code, Severity severity, FeatureRow feature, String message) {
    return new Issue(
        code,
        severity,
        null,
        null,
        feature.id(),
        List.of(),
        null,
        message.formatted(feature.name()));
  }
}
