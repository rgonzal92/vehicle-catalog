package dev.rgonz.catalog.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Random;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
}
