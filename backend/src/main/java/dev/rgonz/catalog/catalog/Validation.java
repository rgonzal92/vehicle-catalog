package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.catalog.Issue.Code;
import dev.rgonz.catalog.catalog.Issue.RuleReference;
import dev.rgonz.catalog.catalog.Issue.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out a catalog's issues from its contents and from the library's state today. It works every
 * issue out afresh each time, and knows nothing of the database or of the web.
 *
 * <p>Besides what a catalog must have, it checks each offering against the rules in effect for it.
 * A feature that is not a feature row of the catalog counts as Not offered in every offering, which
 * is how a global rule reaches a catalog that says nothing of its target.
 */
final class Validation {
  private Validation() {}

  /**
   * What validation needs of the library, as it is today and not as an Approved version recorded
   * it: which of the catalog's features are retired, which of its trims and regions are inactive,
   * and the rules to check the catalog against.
   *
   * @param featureNames the name of each feature a rule names, since a rule may name a feature the
   *     catalog has no row for
   */
  record Library(
      Set<Long> retiredFeatureIds,
      Set<Long> inactiveTrimIds,
      Set<String> inactiveRegionCodes,
      List<Rule> rules,
      Map<Long, String> featureNames) {

    /** A library with no rules. */
    Library(
        Set<Long> retiredFeatureIds, Set<Long> inactiveTrimIds, Set<String> inactiveRegionCodes) {
      this(retiredFeatureIds, inactiveTrimIds, inactiveRegionCodes, List.of(), Map.of());
    }
  }

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

    new RuleChecks(catalog, library, found).run();

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

  /**
   * The checks of each offering against the rules in effect for it. Each check reads the cells of
   * one offering: S is Standard, A is Available, and N is Not offered.
   */
  private static final class RuleChecks {
    private final CatalogSnapshot catalog;
    private final Library library;
    private final List<Issue> found;
    private final Map<Long, String> names = new HashMap<>();
    private final Map<String, Availability> cells = new HashMap<>();

    RuleChecks(CatalogSnapshot catalog, Library library, List<Issue> found) {
      this.catalog = catalog;
      this.library = library;
      this.found = found;
      names.putAll(library.featureNames());
      // A feature row is called what the catalog calls it, which an Approved version recorded.
      catalog.featureRows().forEach(feature -> names.put(feature.id(), feature.name()));
      catalog
          .cells()
          .forEach(
              cell ->
                  cells.put(
                      key(cell.featureId(), cell.trimId(), cell.regionCode()),
                      cell.availability()));
    }

    void run() {
      if (library.rules().isEmpty()) {
        return;
      }
      var offerings = Set.copyOf(catalog.offerings());
      for (Trim trim : catalog.trims()) {
        for (Region region : catalog.regions()) {
          var offering = new Offering(trim.id(), region.code());
          if (offerings.contains(offering)) {
            check(offering, "%s in %s".formatted(trim.name(), region.name()));
          }
        }
      }
    }

    /**
     * Checks one offering against every rule in effect for it.
     *
     * @param where the offering in words, such as "Sport in Europe"
     */
    private void check(Offering offering, String where) {
      var pairsChecked = new HashSet<String>();
      for (Rule rule : library.rules()) {
        if (!rule.covers(offering)) {
          continue;
        }
        var source = value(rule.sourceFeatureId(), offering);
        switch (rule.kind()) {
          case REQUIRES -> requires(rule, source, offering, where);
          case INCLUDES -> includes(rule, source, offering, where);
          case REQUIRES_ONE_OF -> requiresOneOf(rule, source, offering, where);
          case EXCLUDES -> {
            // An exclusion is two rules that say the same, and is checked once.
            if (pairsChecked.add(rule.pairKey())) {
              excludes(rule, source, offering, where);
            }
          }
        }
      }
    }

    private void requires(Rule rule, Availability source, Offering offering, String where) {
      for (long target : rule.targetFeatureIds()) {
        var targeted = value(target, offering);
        if (source == Availability.S && targeted == Availability.A) {
          add(
              Code.REQUIRED_NOT_STANDARD,
              rule,
              offering,
              rule.sourceFeatureId(),
              List.of(target),
              "%s is Standard on %s and requires %s, which is only Available there."
                  .formatted(name(rule.sourceFeatureId()), where, name(target)));
        } else if (source != Availability.N && targeted == Availability.N) {
          add(
              Code.REQUIRED_NOT_OFFERED,
              rule,
              offering,
              rule.sourceFeatureId(),
              List.of(target),
              "%s requires %s, which is not offered on %s."
                  .formatted(name(rule.sourceFeatureId()), name(target), where));
        }
      }
    }

    private void includes(Rule rule, Availability pack, Offering offering, String where) {
      for (long target : rule.targetFeatureIds()) {
        var targeted = value(target, offering);
        if (pack == Availability.N) {
          continue;
        }
        if (targeted == Availability.N) {
          add(
              Code.INCLUDED_NOT_OFFERED,
              rule,
              offering,
              rule.sourceFeatureId(),
              List.of(target),
              "%s includes %s, which is not offered on %s."
                  .formatted(name(rule.sourceFeatureId()), name(target), where));
        } else if (pack == Availability.S && targeted == Availability.A) {
          add(
              Code.INCLUDED_NOT_STANDARD,
              rule,
              offering,
              rule.sourceFeatureId(),
              List.of(target),
              "%s is Standard on %s and includes %s, which is only Available there."
                  .formatted(name(rule.sourceFeatureId()), where, name(target)));
        } else if (pack == Availability.A && targeted == Availability.S) {
          add(
              Code.INCLUDED_ALREADY_STANDARD,
              rule,
              offering,
              rule.sourceFeatureId(),
              List.of(target),
              "%s adds nothing for %s on %s, where it is already Standard."
                  .formatted(name(rule.sourceFeatureId()), name(target), where));
        }
      }
    }

    private void requiresOneOf(Rule rule, Availability source, Offering offering, String where) {
      if (source != Availability.N
          && rule.targetFeatureIds().stream()
              .allMatch(target -> value(target, offering) == Availability.N)) {
        add(
            Code.ONE_OF_NONE_OFFERED,
            rule,
            offering,
            rule.sourceFeatureId(),
            rule.targetFeatureIds(),
            "%s requires one of %s, and none of them is offered on %s."
                .formatted(
                    name(rule.sourceFeatureId()),
                    rule.targetFeatureIds().stream()
                        .map(this::name)
                        .collect(Collectors.joining(", ")),
                    where));
      }
    }

    private void excludes(Rule rule, Availability one, Offering offering, String where) {
      long first = rule.sourceFeatureId();
      long second = rule.targetFeatureIds().getFirst();
      var other = value(second, offering);
      if (one == Availability.S && other == Availability.S) {
        add(
            Code.EXCLUDED_BOTH_STANDARD,
            rule,
            offering,
            first,
            List.of(second),
            "%s and %s exclude each other, and both are Standard on %s."
                .formatted(name(first), name(second), where));
      } else if ((one == Availability.S && other == Availability.A)
          || (one == Availability.A && other == Availability.S)) {
        long available = one == Availability.A ? first : second;
        long standard = one == Availability.A ? second : first;
        add(
            Code.EXCLUDED_BY_STANDARD,
            rule,
            offering,
            available,
            List.of(standard),
            "%s can never be ordered on %s: it excludes %s, which is Standard there."
                .formatted(name(available), where, name(standard)));
      }
    }

    /** The availability of a feature in an offering. A feature without a row is Not offered. */
    private Availability value(long featureId, Offering offering) {
      return cells.getOrDefault(
          key(featureId, offering.trimId(), offering.regionCode()), Availability.N);
    }

    private String name(long featureId) {
      return names.getOrDefault(featureId, "A feature that is no longer in the library");
    }

    private void add(
        Code code,
        Rule rule,
        Offering offering,
        long featureId,
        List<Long> related,
        String message) {
      found.add(
          new Issue(
              code,
              code == Code.INCLUDED_ALREADY_STANDARD ? Severity.WARNING : Severity.ERROR,
              offering.trimId(),
              offering.regionCode(),
              featureId,
              List.copyOf(related),
              new RuleReference(rule.origin().name(), rule.key()),
              message));
    }

    private static String key(long featureId, long trimId, String regionCode) {
      return featureId + ":" + trimId + ":" + regionCode;
    }
  }
}
