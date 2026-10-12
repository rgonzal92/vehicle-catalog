package dev.rgonz.catalog.document;

import dev.rgonz.catalog.document.Documents.Kind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads the text of an uploaded file. Markdown and plain text are their own text. A PDF's is taken
 * out of it, which anyone's file may make slow or impossible, so it is given a time to be done in
 * and its pages a size to unpack to.
 */
final class DocumentText {
  /** The longest a PDF is read for, its wait for the PDF before it included. */
  static final Duration LONGEST_READ = Duration.ofSeconds(30);

  /**
   * The most text that is read of a file, in characters: what the most passages there may be can
   * hold. A PDF of two megabytes can hold many times that, packed, and none of the rest is read.
   */
  static final int MOST_TEXT = Passages.MOST * Passages.LONGEST;

  /**
   * The most that the pages of a PDF may unpack to, in bytes: sixteen times the largest file there
   * may be. A PDF with as much text as a document may have unpacks to under a megabyte. How long
   * PDFBox reads this much for depends on what it is: about a second for drawing that does nothing,
   * and nearly eight for a page that draws a small form millions of times. So the time a PDF is
   * given stays.
   */
  static final long MOST_UNPACKED = 32L * 1024 * 1024;

  static final String UNPACKS_TO_TOO_MUCH = "Its pages unpack to more than 32 MB.";

  /** Held by the one PDF that is being read, until its reading has ended. */
  private static final Semaphore ONE_AT_A_TIME = new Semaphore(1);

  private DocumentText() {}

  /** A file cannot be read as what it is. Its message says why, as a person is told it. */
  static final class Unreadable extends RuntimeException {
    Unreadable(String why) {
      super(why);
    }
  }

  /**
   * The text of a file of this kind. A PDF of pictures alone has none.
   *
   * @throws Unreadable when it cannot be read, or not in time
   */
  static String of(Kind kind, byte[] file) {
    if (kind != Kind.PDF) {
      var text = new String(file, StandardCharsets.UTF_8);
      if (text.length() > MOST_TEXT) {
        throw new Unreadable(Passages.TOO_LONG);
      }
      return text;
    }
    return within(LONGEST_READ, () -> textOfPdf(file));
  }

  /**
   * The text that a reading of a PDF gives, when it gives it within a time.
   *
   * <p>PDFBox does not stop when it is told to. So a reading whose time is over is left to end by
   * itself, on a thread of its own that nothing waits for, and the document has failed. One PDF is
   * read at a time, and a reading that was left behind is that one until it ends: the next PDF
   * waits for it within its own time, and fails when that is over. So there is never more than one
   * reading, and a worker that is started again has none.
   *
   * @throws Unreadable when the reading fails, or the time is over first
   */
  static String within(Duration longest, Callable<String> text) {
    long over = System.nanoTime() + longest.toNanos();
    try {
      if (!ONE_AT_A_TIME.tryAcquire(longest.toNanos(), TimeUnit.NANOSECONDS)) {
        throw new Unreadable("It waited too long for another PDF to be read.");
      }
      var reading = new FutureTask<>(text);
      // A thread of the system's: a reading that works on would keep one of the few threads that
      // all virtual threads run on. Its end lets the next PDF be read, however it ends.
      Thread.ofPlatform()
          .daemon()
          .name("pdf-reading")
          .start(
              () -> {
                try {
                  reading.run();
                } finally {
                  ONE_AT_A_TIME.release();
                }
              });
      try {
        return reading.get(over - System.nanoTime(), TimeUnit.NANOSECONDS);
      } catch (TimeoutException tooSlow) {
        reading.cancel(true);
        throw new Unreadable("It took too long to read.");
      } catch (ExecutionException failed) {
        throw failed.getCause() instanceof Unreadable unreadable
            ? unreadable
            : new Unreadable("It could not be read as a PDF.");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new Unreadable("It was not read to its end.");
    }
  }

  private static String textOfPdf(byte[] file) throws IOException {
    var text = new StringWriter();
    // Stops the reading once there is more text than a document may have.
    var bounded =
        new FilterWriter(text) {
          @Override
          public void write(String part, int from, int length) throws IOException {
            if (text.getBuffer().length() + length > MOST_TEXT) {
              throw new Unreadable(Passages.TOO_LONG);
            }
            super.write(part, from, length);
          }

          @Override
          public void write(char[] part, int from, int length) throws IOException {
            write(new String(part, from, length), 0, length);
          }

          @Override
          public void write(int character) throws IOException {
            write(String.valueOf((char) character), 0, 1);
          }
        };
    try (var pdf = Loader.loadPDF(file)) {
      new BoundedStripper().writeText(pdf, bounded);
      return text.toString();
    } catch (InvalidPasswordException locked) {
      throw new Unreadable("It is protected by a password.");
    }
  }

  /**
   * Takes the text out of a PDF's pages, and stops once they have unpacked to more than they may.
   *
   * <p>PDFBox unpacks the contents of a page as it reads them, when they were packed once, and
   * otherwise wholly into memory first, as it does those of a form that a page draws. Neither has a
   * limit. So each is unpacked here before PDFBox is given it, with nothing kept, and counted: what
   * PDFBox then unpacks is known to be within the limit. A page or a form that is drawn again
   * counts again, since it is read again.
   */
  private static final class BoundedStripper extends PDFTextStripper {
    private long left = MOST_UNPACKED;

    @Override
    public void processPage(PDPage page) throws IOException {
      // A page's contents are one stream or a list of them. Of a list PDFBox reads the parts that
      // are streams and passes over the others, so those are the ones that count.
      COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
      if (contents instanceof COSStream whole) {
        unpack(whole);
      } else if (contents instanceof COSArray parts) {
        for (int index = 0; index < parts.size(); index++) {
          if (parts.getObject(index) instanceof COSStream part) {
            unpack(part);
          }
        }
      }
      super.processPage(page);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
      unpack(form.getCOSObject());
      super.showForm(form);
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup group) throws IOException {
      unpack(group.getCOSObject());
      super.showTransparencyGroup(group);
    }

    /**
     * Unpacks contents as PDFBox will, through each of their filters in turn, and counts what every
     * one of them gives. Only what a filter gives to the next is kept, for that long.
     *
     * @throws Unreadable once the pages have unpacked to more than they may
     * @throws IOException when the contents cannot be unpacked
     */
    private void unpack(COSStream contents) throws IOException {
      COSBase named = contents.getFilters();
      List<? extends COSBase> filters =
          named instanceof COSArray several
              ? several.toList()
              : named == null ? List.of() : List.of(named);
      InputStream packed = contents.createRawInputStream();
      for (int index = 0; index < filters.size(); index++) {
        if (!(filters.get(index) instanceof COSName name)) {
          throw new IOException("A filter has no name.");
        }
        var kept = new ByteArrayOutputStream();
        var unpacked = index < filters.size() - 1 ? kept : OutputStream.nullOutputStream();
        try (var from = packed) {
          FilterFactory.INSTANCE.getFilter(name).decode(from, counted(unpacked), contents, index);
        }
        packed = new ByteArrayInputStream(kept.toByteArray());
      }
      // Contents that are not packed are all that is left here, and count as they are.
      try (var whole = packed) {
        whole.transferTo(counted(OutputStream.nullOutputStream()));
      }
    }

    /** Passes on what is written, as long as the pages may still unpack to that much more. */
    private OutputStream counted(OutputStream unpacked) {
      return new FilterOutputStream(unpacked) {
        @Override
        public void write(byte[] part, int from, int length) throws IOException {
          left -= length;
          if (left < 0) {
            throw new Unreadable(UNPACKS_TO_TOO_MUCH);
          }
          out.write(part, from, length);
        }

        @Override
        public void write(int one) throws IOException {
          write(new byte[] {(byte) one}, 0, 1);
        }
      };
    }
  }
}
