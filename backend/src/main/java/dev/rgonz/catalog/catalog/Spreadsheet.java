package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Writes a catalog as a spreadsheet: its matrix on one sheet and its rules on another. It names
 * everything by the labels the catalog is given with, so an Approved version reads as it was
 * approved. It knows nothing of the database or of the web.
 */
final class Spreadsheet {
  private Spreadsheet() {}

  /**
   * The catalog as an {@code .xlsx} file.
   *
   * @param categories what each category is called, by its code and in the order categories are
   *     shown in
   */
  static byte[] of(CatalogSnapshot catalog, Map<String, String> categories) {
    var file = new ByteArrayOutputStream();
    try (var workbook = new Workbook(file, "Vehicle Catalog", "1.0")) {
      matrix(workbook.newWorksheet("Matrix"), catalog, categories);
      rules(workbook.newWorksheet("Rules"), catalog);
    } catch (IOException unwritten) {
      throw new UncheckedIOException(unwritten);
    }
    return file.toByteArray();
  }

  /**
   * A row for each feature row, by category and then by code, as the matrix has them, and a column
   * for each offering, region by region. A cell holds S, A, or a dash for Not offered.
   */
  private static void matrix(
      Worksheet sheet, CatalogSnapshot catalog, Map<String, String> categories) {
    var trims = named(catalog.trims(), Trim::id, Trim::name);
    var regions = named(catalog.regions(), Region::code, Region::name);
    var offerings = catalog.offeringsInOrder();
    var availabilities = new HashMap<List<Object>, Availability>();
    catalog
        .cells()
        .forEach(
            cell ->
                availabilities.put(
                    List.of(cell.featureId(), cell.trimId(), cell.regionCode()),
                    cell.availability()));

    var headings = new ArrayList<>(List.of("Category", "Code", "Feature"));
    offerings.forEach(
        offering ->
            headings.add(regions.get(offering.regionCode()) + ": " + trims.get(offering.trimId())));
    heading(sheet, headings);

    var places = new ArrayList<>(categories.keySet());
    var features =
        catalog.featureRows().stream()
            .sorted(
                Comparator.comparing((FeatureRow feature) -> places.indexOf(feature.categoryCode()))
                    .thenComparing(FeatureRow::code))
            .toList();
    int row = 1;
    for (FeatureRow feature : features) {
      sheet.value(row, 0, categories.getOrDefault(feature.categoryCode(), feature.categoryCode()));
      sheet.value(row, 1, feature.code());
      sheet.value(row, 2, feature.name());
      for (int column = 0; column < offerings.size(); column++) {
        var offering = offerings.get(column);
        var availability =
            availabilities.getOrDefault(
                List.of(feature.id(), offering.trimId(), offering.regionCode()), Availability.N);
        sheet.value(row, 3 + column, availability == Availability.N ? "-" : availability.name());
      }
      row++;
    }
  }

  /** A row for each rule of the catalog, with what it names and the rule in words. */
  private static void rules(Worksheet sheet, CatalogSnapshot catalog) {
    var features = named(catalog.featureRows(), FeatureRow::id, FeatureRow::name);
    var trims = named(catalog.trims(), Trim::id, Trim::name);
    var regions = named(catalog.regions(), Region::code, Region::name);
    heading(sheet, List.of("Kind", "Source", "Targets", "Trims", "Regions", "Rule"));

    // An exclusion is two rules that say the same of each other, and is written once.
    var pairs = new HashSet<String>();
    int row = 1;
    for (Rule rule : catalog.rules()) {
      if (rule.pairKey() != null && !pairs.add(rule.pairKey())) {
        continue;
      }
      var kind = rule.kind().words();
      sheet.value(row, 0, kind.substring(0, 1).toUpperCase() + kind.substring(1));
      sheet.value(row, 1, features.get(rule.sourceFeatureId()));
      sheet.value(
          row,
          2,
          rule.targetFeatureIds().stream().map(features::get).collect(Collectors.joining(", ")));
      sheet.value(
          row,
          3,
          rule.allTrims()
              ? "Every trim"
              : rule.trimIds().stream().map(trims::get).collect(Collectors.joining(", ")));
      sheet.value(
          row,
          4,
          rule.allRegions()
              ? "Every region"
              : rule.regionCodes().stream().map(regions::get).collect(Collectors.joining(", ")));
      sheet.value(row, 5, rule.inWords(features::get, trims::get, regions::get) + ".");
      row++;
    }
  }

  private static void heading(Worksheet sheet, List<String> headings) {
    for (int column = 0; column < headings.size(); column++) {
      sheet.value(0, column, headings.get(column));
    }
    sheet.range(0, 0, 0, headings.size() - 1).style().bold().set();
    sheet.freezePane(0, 1);
  }

  private static <T, K> Map<K, String> named(
      List<T> entries, Function<T, K> key, Function<T, String> name) {
    return entries.stream().collect(Collectors.toMap(key, name));
  }
}
