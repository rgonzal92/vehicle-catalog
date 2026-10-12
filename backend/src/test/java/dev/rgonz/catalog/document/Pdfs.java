package dev.rgonz.catalog.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** Makes the PDFs the tests upload and read. */
public final class Pdfs {
  private Pdfs() {}

  /** A PDF with these lines of text, a page for each list. A page without lines has no text. */
  @SafeVarargs
  public static byte[] of(List<String>... pages) {
    return withAPicture(0, pages);
  }

  /**
   * A PDF with these pages of text and, when it is to have one, a page with a picture of noise,
   * which is as large in the file as it is in memory.
   *
   * @param pictureBytes about how many bytes the picture adds to the file, or none
   */
  @SafeVarargs
  public static byte[] withAPicture(int pictureBytes, List<String>... pages) {
    try (var document = new PDDocument()) {
      if (pictureBytes > 0) {
        int side = (int) Math.sqrt(pictureBytes / 3.0);
        var noise = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        var random = new Random(7);
        for (int x = 0; x < side; x++) {
          for (int y = 0; y < side; y++) {
            noise.setRGB(x, y, random.nextInt(0x1000000));
          }
        }
        var page = new PDPage();
        document.addPage(page);
        try (var content = new PDPageContentStream(document, page)) {
          content.drawImage(LosslessFactory.createFromImage(document, noise), 56, 300, 300, 300);
        }
      }
      for (var lines : pages) {
        var page = new PDPage();
        document.addPage(page);
        try (var content = new PDPageContentStream(document, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
          content.newLineAtOffset(56, 740);
          for (var line : lines) {
            content.showText(line);
            content.newLineAtOffset(0, -14);
          }
          content.endText();
        }
      }
      var file = new ByteArrayOutputStream();
      document.save(file);
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * A PDF of one page of drawing that shows nothing: the state is kept and put back, over and over.
   * Packed, it is about a thousandth of what it unpacks to.
   *
   * @param bytes how many bytes the drawing unpacks to
   * @param inAForm whether the page draws a form that holds the drawing, which PDFBox unpacks into
   *     memory before it reads any of it, or holds the drawing itself, which is unpacked as it is
   *     read
   */
  public static byte[] ofDrawingThatUnpacksTo(long bytes, boolean inAForm) {
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var drawing = new PDStream(document);
      pack("", "q Q\n", bytes, drawing.getCOSObject().createRawOutputStream());
      drawing.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
      if (inAForm) {
        var form = new PDFormXObject(drawing);
        form.setResources(new PDResources());
        form.setBBox(PDRectangle.LETTER);
        try (var content = new PDPageContentStream(document, page)) {
          content.drawForm(form);
        }
      } else {
        page.setContents(drawing);
      }
      var file = new ByteArrayOutputStream();
      document.save(file);
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * Packs a start and then a part over and over, until they come to these many bytes, and closes
   * what it packs into. Packed here and not by PDFBox, which holds all that it is given until it
   * packs it.
   */
  private static void pack(String start, String part, long bytes, OutputStream packed)
      throws IOException {
    var parts = part.repeat(65536 / part.length()).getBytes(StandardCharsets.US_ASCII);
    try (var deflated = new DeflaterOutputStream(packed, new Deflater(Deflater.BEST_COMPRESSION))) {
      deflated.write(start.getBytes(StandardCharsets.US_ASCII));
      for (long written = start.length(); written < bytes; written += parts.length) {
        deflated.write(parts);
      }
    }
  }

  /**
   * A PDF of one page with these lines of text, in a font whose map from codes to characters is
   * empty lines. PDFBox unpacks the map into memory when the page first writes in the font.
   *
   * @param bytes how many bytes the map unpacks to
   */
  public static byte[] ofAFontMapThatUnpacksTo(long bytes, List<String> lines) {
    try (var document = Loader.loadPDF(of(lines))) {
      var fonts = document.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
      var map = new PDStream(document);
      pack("", "\n", bytes, map.getCOSObject().createRawOutputStream());
      map.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
      fonts.getCOSDictionary(fonts.keySet().iterator().next()).setItem(COSName.TO_UNICODE, map);
      var file = new ByteArrayOutputStream();
      document.save(file);
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * A PDF of one page with this line of text, in a font whose file and maps the PDF holds, as most
   * PDFs do. The font is the one that PDFBox brings along.
   */
  public static byte[] inAFontOfItsOwn(String line) {
    try (var document = new PDDocument();
        var font =
            PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      var page = new PDPage();
      document.addPage(page);
      try (var content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(PDType0Font.load(document, font), 11);
        content.newLineAtOffset(56, 740);
        content.showText(line);
        content.endText();
      }
      var file = new ByteArrayOutputStream();
      document.save(file);
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * A PDF of one page with these lines of text, in a font whose file is said to be packed as a fax
   * picture of 250 MB, and is a hundred bytes of nothing. PDFBox takes memory for the size such a
   * picture is said to have before it unpacks any of it.
   */
  public static byte[] ofAFontFilePackedAsAPicture(List<String> lines) {
    try (var document = Loader.loadPDF(of(lines))) {
      var fonts = document.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
      var fontFile = new PDStream(document);
      try (var raw = fontFile.getCOSObject().createRawOutputStream()) {
        raw.write(new byte[100]);
      }
      var size = new COSDictionary();
      size.setInt(COSName.COLUMNS, 50_000);
      size.setInt(COSName.ROWS, 40_000);
      fontFile.getCOSObject().setItem(COSName.FILTER, COSName.CCITTFAX_DECODE);
      fontFile.getCOSObject().setItem(COSName.DECODE_PARMS, size);
      var descriptor = new COSDictionary();
      descriptor.setItem(COSName.TYPE, COSName.FONT_DESC);
      descriptor.setItem(COSName.FONT_FILE, fontFile);
      fonts
          .getCOSDictionary(fonts.keySet().iterator().next())
          .setItem(COSName.FONT_DESC, descriptor);
      var file = new ByteArrayOutputStream();
      document.save(file);
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * A PDF of one page without contents, whose catalog, pages, and page are held in an object
   * stream, followed by spaces. PDFBox unpacks the stream into memory as it opens the file, to find
   * the catalog. The file is written here: PDFBox packs the object streams it writes itself.
   *
   * @param bytes how many bytes the object stream unpacks to
   */
  public static byte[] ofAnObjectStreamThatUnpacksTo(long bytes) {
    var held =
        List.of(
            "<< /Type /Catalog /Pages 3 0 R >>",
            "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] >>");
    // The stream starts with the number of each object it holds and where in it the object is.
    var where = new StringBuilder();
    var objects = new StringBuilder();
    for (int index = 0; index < held.size(); index++) {
      where.append(index + 2).append(' ').append(objects.length()).append(' ');
      objects.append(held.get(index)).append('\n');
    }
    try {
      var stream = new ByteArrayOutputStream();
      pack(where.toString() + objects, " ", bytes, stream);
      var file = new ByteArrayOutputStream();
      file.write("%PDF-1.5\n".getBytes(StandardCharsets.US_ASCII));
      int streamAt = file.size();
      file.write(
          "1 0 obj\n<< /Type /ObjStm /N %d /First %d /Filter /FlateDecode /Length %d >>\nstream\n"
              .formatted(held.size(), where.length(), stream.size())
              .getBytes(StandardCharsets.US_ASCII));
      stream.writeTo(file);
      file.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
      int tableAt = file.size();
      // The table of where the objects are, six bytes for each: object 0 is none, 1 is the object
      // stream, 2 to 4 are held in it, and 5 is the table.
      var table = ByteBuffer.allocate(36);
      table.put((byte) 0).putInt(0).put((byte) 0);
      table.put((byte) 1).putInt(streamAt).put((byte) 0);
      for (int index = 0; index < held.size(); index++) {
        table.put((byte) 2).putInt(1).put((byte) index);
      }
      table.put((byte) 1).putInt(tableAt).put((byte) 0);
      file.write(
          "5 0 obj\n<< /Type /XRef /Size 6 /W [1 4 1] /Root 2 0 R /Length 36 >>\nstream\n"
              .getBytes(StandardCharsets.US_ASCII));
      file.write(table.array());
      file.write(
          "\nendstream\nendobj\nstartxref\n%d\n%%%%EOF\n"
              .formatted(tableAt)
              .getBytes(StandardCharsets.US_ASCII));
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }

  /**
   * A PDF of one page with these lines of text, whose contents are a list of two parts of which the
   * second is nothing. PDFBox reads the parts that are contents and passes over the others.
   */
  public static byte[] ofContentsWithAPartThatIsNothing(List<String> lines) {
    try (var document = Loader.loadPDF(of(lines))) {
      var page = document.getPage(0).getCOSObject();
      var parts = new COSArray();
      parts.add(page.getDictionaryObject(COSName.CONTENTS));
      parts.add(COSNull.NULL);
      page.setItem(COSName.CONTENTS, parts);
      var file = new ByteArrayOutputStream();
      document.save(file);
      return file.toByteArray();
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
  }
}
