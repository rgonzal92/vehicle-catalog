package dev.rgonz.catalog.document;

import dev.rgonz.catalog.document.Documents.Kind;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads the text of an uploaded file. Markdown and plain text are their own text. A PDF's is taken
 * out of it, which anyone's file may make slow or impossible, so it is given a time to be done in.
 */
final class DocumentText {
  /** The longest a file is read for. */
  static final Duration LONGEST_READ = Duration.ofSeconds(30);

  /**
   * The most text that is read of a file, in characters: what the most passages there may be can
   * hold. A PDF of two megabytes can hold many times that, packed, and none of the rest is read.
   */
  static final int MOST_TEXT = Passages.MOST * Passages.LONGEST;

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
    try (var apart = Executors.newVirtualThreadPerTaskExecutor()) {
      var reading = apart.submit(() -> textOfPdf(file));
      try {
        return reading.get(LONGEST_READ.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException tooSlow) {
        reading.cancel(true);
        throw new Unreadable("It took too long to read.");
      } catch (ExecutionException failed) {
        throw failed.getCause() instanceof Unreadable unreadable
            ? unreadable
            : new Unreadable("It could not be read as a PDF.");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new Unreadable("It was not read to its end.");
      }
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
      new PDFTextStripper().writeText(pdf, bounded);
      return text.toString();
    } catch (InvalidPasswordException locked) {
      throw new Unreadable("It is protected by a password.");
    }
  }
}
