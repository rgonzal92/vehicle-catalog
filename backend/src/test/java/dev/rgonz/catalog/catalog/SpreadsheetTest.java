package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Availability;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Cell;
import dev.rgonz.catalog.catalog.CatalogSnapshot.FeatureRow;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Kind;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Offering;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Region;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.library.RuleKind;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Checks the spreadsheet a catalog is written as, by reading the file back: an {@code .xlsx} file
 * is a ZIP archive of XML documents, one for each sheet and one for the text the sheets share.
 */
class SpreadsheetTest {
  /** A catalog that sells Base and Sport in North America and Base in Europe. */
  private static CatalogSnapshot catalog() {
    return new CatalogSnapshot(
        41,
        3,
        Status.APPROVED,
        0,
        List.of(new Trim(1, "Base", 1), new Trim(2, "Sport", 2)),
        List.of(new Region("NA", "North America"), new Region("EU", "Europe")),
        // Not in the order of the matrix, which the file has to put them in.
        List.of(new Offering(1, "EU"), new Offering(2, "NA"), new Offering(1, "NA")),
        List.of(
            new FeatureRow(10, "COOLING", Kind.FEATURE, "Heavy-Duty Cooling", "THERMAL"),
            new FeatureRow(9, "HITCH", Kind.FEATURE, "Trailer Hitch Receiver", "CHASSIS"),
            new FeatureRow(8, "PACKAGE_TOW", Kind.PACKAGE, "Tow Package", "PACKAGES")),
        List.of(
            new Cell(8, 2, "NA", Availability.A),
            new Cell(9, 2, "NA", Availability.S),
            new Cell(10, 1, "EU", Availability.S)),
        List.of(
            new Rule(
                Rule.Origin.CATALOG,
                "needs-hitch",
                RuleKind.REQUIRES,
                8,
                List.of(9L),
                false,
                Set.of(2L),
                true,
                Set.of(),
                null),
            new Rule(
                Rule.Origin.CATALOG,
                "forward",
                RuleKind.EXCLUDES,
                9,
                List.of(10L),
                true,
                Set.of(),
                false,
                Set.of("EU"),
                "a-pair"),
            new Rule(
                Rule.Origin.CATALOG,
                "mirrored",
                RuleKind.EXCLUDES,
                10,
                List.of(9L),
                true,
                Set.of(),
                false,
                Set.of("EU"),
                "a-pair")));
  }

  /** The categories, in the order they are shown in. */
  private static Map<String, String> categories() {
    var categories = new LinkedHashMap<String, String>();
    categories.put("CHASSIS", "Chassis");
    categories.put("THERMAL", "Thermal");
    categories.put("PACKAGES", "Packages");
    return categories;
  }

  @Test
  void theFileHasASheetForTheMatrixAndOneForTheRules() throws Exception {
    var file = Spreadsheet.of(catalog(), categories());

    var sheets = document(file, "xl/workbook.xml").getElementsByTagName("sheet");

    assertThat(List.of(name(sheets.item(0)), name(sheets.item(1))))
        .containsExactly("Matrix", "Rules");
    assertThat(sheets.getLength()).isEqualTo(2);
  }

  @Test
  void theMatrixHasARowForEachFeatureByCategoryAndAColumnForEachOfferingByRegion()
      throws Exception {
    var file = Spreadsheet.of(catalog(), categories());

    assertThat(rows(file, 1))
        .containsExactly(
            List.of(
                "Category",
                "Code",
                "Feature",
                "North America: Base",
                "North America: Sport",
                "Europe: Base"),
            List.of("Chassis", "HITCH", "Trailer Hitch Receiver", "-", "S", "-"),
            List.of("Thermal", "COOLING", "Heavy-Duty Cooling", "-", "-", "S"),
            List.of("Packages", "PACKAGE_TOW", "Tow Package", "-", "A", "-"));
  }

  @Test
  void theRulesAreEachARowInWordsAndAnExclusionIsOneRowForItsPair() throws Exception {
    var file = Spreadsheet.of(catalog(), categories());

    assertThat(rows(file, 2))
        .containsExactly(
            List.of("Kind", "Source", "Targets", "Trims", "Regions", "Rule"),
            List.of(
                "Requires",
                "Tow Package",
                "Trailer Hitch Receiver",
                "Sport",
                "Every region",
                "Tow Package requires Trailer Hitch Receiver (on Sport)."),
            List.of(
                "Excludes",
                "Trailer Hitch Receiver",
                "Heavy-Duty Cooling",
                "Every trim",
                "Europe",
                "Trailer Hitch Receiver excludes Heavy-Duty Cooling (in Europe)."));
  }

  @Test
  void aCatalogWithNothingInItIsTwoSheetsOfHeadings() throws Exception {
    var file = Spreadsheet.of(CatalogSnapshot.empty(), categories());

    assertThat(rows(file, 1)).containsExactly(List.of("Category", "Code", "Feature"));
    assertThat(rows(file, 2)).hasSize(1);
  }

  private static String name(org.w3c.dom.Node sheet) {
    return ((Element) sheet).getAttribute("name");
  }

  /** The text of every cell of a sheet, row by row. */
  static List<List<String>> rows(byte[] file, int sheet) throws Exception {
    var shared = document(file, "xl/sharedStrings.xml").getElementsByTagName("si");
    var found = document(file, "xl/worksheets/sheet%d.xml".formatted(sheet));
    var rows = new ArrayList<List<String>>();
    var written = found.getElementsByTagName("row");
    for (int row = 0; row < written.getLength(); row++) {
      var cells = ((Element) written.item(row)).getElementsByTagName("c");
      var texts = new ArrayList<String>();
      for (int cell = 0; cell < cells.getLength(); cell++) {
        var value = ((Element) cells.item(cell)).getElementsByTagName("v").item(0);
        texts.add(shared.item(Integer.parseInt(value.getTextContent())).getTextContent());
      }
      rows.add(texts);
    }
    return rows;
  }

  /**
   * One of the XML documents the file is an archive of. The archive is read from its directory, as
   * a spreadsheet program reads it, which takes a file on disk.
   */
  private static Document document(byte[] file, String name) throws Exception {
    var onDisk = Files.createTempFile("a-catalog", ".xlsx");
    try {
      Files.write(onDisk, file);
      try (var archive = new ZipFile(onDisk.toFile())) {
        var entry = archive.getEntry(name);
        assertThat(entry).as("%s in the archive", name).isNotNull();
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(archive.getInputStream(entry));
      }
    } finally {
      Files.delete(onDisk);
    }
  }
}
