package dev.rgonz.catalog.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
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
      var part = "q Q\n".repeat(16384).getBytes(StandardCharsets.US_ASCII);
      // Packed here and not by PDFBox, which holds all that it is given until it packs it.
      try (var packed =
          new DeflaterOutputStream(
              drawing.getCOSObject().createRawOutputStream(),
              new Deflater(Deflater.BEST_COMPRESSION))) {
        for (long written = 0; written < bytes; written += part.length) {
          packed.write(part);
        }
      }
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
