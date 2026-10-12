package dev.rgonz.catalog.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.document.Documents.Kind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Checks that the text of an uploaded file is read, and what becomes of a file that cannot be. */
class DocumentTextTest {
  @Test
  void aTextFileIsReadAsItIs() {
    var note = "# Launch notes\n\nThe hybrid follows in the autumn: Zürich first.\n";

    assertThat(DocumentText.of(Kind.MD, note.getBytes(StandardCharsets.UTF_8))).isEqualTo(note);
    assertThat(DocumentText.of(Kind.TXT, note.getBytes(StandardCharsets.UTF_8))).isEqualTo(note);
  }

  @Test
  void aPdfsTextIsReadPageAfterPage() {
    var text =
        DocumentText.of(
            Kind.PDF,
            Pdfs.of(
                List.of("Launch notes", "The hybrid follows in the autumn."),
                List.of("It comes to Europe first.")));

    assertThat(text.lines().map(String::strip).filter(line -> !line.isEmpty()))
        .containsExactly(
            "Launch notes", "The hybrid follows in the autumn.", "It comes to Europe first.");
  }

  @Test
  void aPdfWhoseContentsHaveAPartThatIsNothingIsReadAsPdfBoxReadsIt() {
    assertThat(
            DocumentText.of(
                Kind.PDF, Pdfs.ofContentsWithAPartThatIsNothing(List.of("Launch notes"))))
        .contains("Launch notes");
  }

  @Test
  void aPdfOfPicturesAloneHasNoText() {
    assertThat(DocumentText.of(Kind.PDF, Pdfs.of(List.of(), List.of()))).isBlank();
  }

  @Test
  void aFileThatIsNoPdfWhateverItsStartSaysCannotBeRead() {
    var broken = "%PDF-1.7\nthis is where a PDF would go on\n".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> DocumentText.of(Kind.PDF, broken))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage("It could not be read as a PDF.");
  }

  /** Pages of text, forty-eight lines to a page. */
  @SuppressWarnings("unchecked")
  private static List<String>[] pagesOfText(int pages) {
    var all = new ArrayList<List<String>>();
    for (int page = 1; page <= pages; page++) {
      var lines = new ArrayList<String>();
      for (int line = 1; line <= 48; line++) {
        lines.add(
            "Page %d, line %d: the catalog offers what its version says it offers."
                .formatted(page, line));
      }
      all.add(lines);
    }
    return all.toArray(List[]::new);
  }

  @Test
  void aPdfWithMoreTextThanADocumentMayHaveIsNotReadToItsEnd() {
    // Six hundred pages come to a fifth of a megabyte as a PDF, and to two million characters.
    var pdf = Pdfs.of(pagesOfText(600));

    assertThat(pdf.length).isLessThan(2 * 1024 * 1024);
    assertThatThrownBy(() -> DocumentText.of(Kind.PDF, pdf))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage("It is too long: a document is read as at most 300 passages.");
    assertThatThrownBy(
            () -> DocumentText.of(Kind.TXT, "x".repeat(DocumentText.MOST_TEXT + 1).getBytes()))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage("It is too long: a document is read as at most 300 passages.");
  }

  @Test
  void aPdfOfTwoMegabytesWithAsMuchTextAsADocumentMayHaveIsRead() {
    // A hundred and forty pages of text, and a picture that brings the file to two megabytes.
    var pdf = Pdfs.withAPicture(1_500_000, pagesOfText(140));
    var runtime = Runtime.getRuntime();
    System.gc();
    long before = runtime.totalMemory() - runtime.freeMemory();

    var text = DocumentText.of(Kind.PDF, pdf);

    long after = runtime.totalMemory() - runtime.freeMemory();
    System.out.printf(
        "A PDF OF %,d BYTES: %,d CHARACTERS OF TEXT, %d MB OF HEAP MORE IN USE BEFORE ANY IS"
            + " FREED, OF %d MB AT MOST%n",
        pdf.length,
        text.length(),
        Math.max(0, after - before) / (1024 * 1024),
        runtime.maxMemory() / (1024 * 1024));
    assertThat(pdf.length).isBetween(1_500_000, 2 * 1024 * 1024);
    assertThat(text.length()).isBetween(400_000, DocumentText.MOST_TEXT);
    assertThat(text).contains("Page 140, line 48:");
  }

  /** Whether a thread is reading a PDF. */
  private static boolean aPdfIsBeingRead() {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(thread -> thread.getName().equals("pdf-reading"));
  }

  /**
   * Checks that a PDF whose drawing unpacks to far more than its pages may fails soon, that its
   * reading has ended by then, and that the PDF after it is read at once.
   */
  private static void failsSoonAndLeavesNoReadingBehind(byte[] pdf) {
    failsSoonAndLeavesNoReadingBehind(pdf, "Its pages unpack to more than 32 MB.");
  }

  /** The same of a PDF that unpacks to far more than it may, with the reason it is to fail with. */
  private static void failsSoonAndLeavesNoReadingBehind(byte[] pdf, String reason) {
    assertThat(pdf.length).isLessThan(2 * 1024 * 1024);
    long start = System.nanoTime();

    assertThatThrownBy(() -> DocumentText.of(Kind.PDF, pdf))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage(reason);

    long failed = System.nanoTime();
    await().atMost(Duration.ofSeconds(1)).until(() -> !aPdfIsBeingRead());
    assertThat(DocumentText.of(Kind.PDF, Pdfs.of(List.of("Launch notes"))))
        .contains("Launch notes");
    long next = System.nanoTime();
    System.out.printf(
        "A PDF OF %,d BYTES THAT UNPACKS TO FAR MORE: FAILED AFTER %d MS, THE NEXT READ IN %d MS%n",
        pdf.length, (failed - start) / 1_000_000, (next - failed) / 1_000_000);
    assertThat(Duration.ofNanos(failed - start)).isLessThan(Duration.ofSeconds(5));
    assertThat(Duration.ofNanos(next - failed)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void aPdfWhosePageUnpacksToTwoGigabytesFailsSoonAndLeavesNoReadingBehind() {
    failsSoonAndLeavesNoReadingBehind(Pdfs.ofDrawingThatUnpacksTo(2_000_000_000L, false));
  }

  @Test
  void aPdfWhoseFormUnpacksToTwoGigabytesFailsBeforeItFillsMemoryAndLeavesNoReadingBehind() {
    failsSoonAndLeavesNoReadingBehind(Pdfs.ofDrawingThatUnpacksTo(2_000_000_000L, true));
  }

  @Test
  void aPdfWhoseFontMapUnpacksToTwoGigabytesFailsSoonAndLeavesNoReadingBehind() {
    failsSoonAndLeavesNoReadingBehind(
        Pdfs.ofAFontMapThatUnpacksTo(2_000_000_000L, List.of("Launch notes")),
        "It unpacks to more than 32 MB.");
  }

  @Test
  void aPdfWhoseObjectStreamUnpacksToTwoGigabytesFailsSoonAndLeavesNoReadingBehind() {
    failsSoonAndLeavesNoReadingBehind(
        Pdfs.ofAnObjectStreamThatUnpacksTo(2_000_000_000L), "It unpacks to more than 32 MB.");
  }

  @Test
  void aPdfWhoseFontMapAndObjectStreamUnpackToLittleIsRead() {
    assertThat(
            DocumentText.of(
                Kind.PDF, Pdfs.ofAFontMapThatUnpacksTo(100_000, List.of("Launch notes"))))
        .contains("Launch notes");
    assertThat(DocumentText.of(Kind.PDF, Pdfs.ofAnObjectStreamThatUnpacksTo(100_000))).isBlank();
  }

  @Test
  void aPdfInAFontOfItsOwnIsRead() {
    assertThat(DocumentText.of(Kind.PDF, Pdfs.inAFontOfItsOwn("It comes to Zürich first.")))
        .contains("It comes to Zürich first.");
  }

  @Test
  void aPdfWhoseFontFileIsPackedAsAPictureIsReadWithoutTheFile() {
    assertThat(DocumentText.of(Kind.PDF, Pdfs.ofAFontFilePackedAsAPicture(List.of("Launch notes"))))
        .contains("Launch notes");
  }

  @Test
  void aPdfWhosePageIsPackedInRowsOfAQuarterOfAGigabyteIsNotUnpacked() {
    assertThat(
            DocumentText.of(Kind.PDF, Pdfs.ofAPagePackedInRowsOf(1_000, List.of("Launch notes"))))
        .contains("Launch notes");

    assertThatThrownBy(
            () ->
                DocumentText.of(
                    Kind.PDF, Pdfs.ofAPagePackedInRowsOf(268_435_455, List.of("Launch notes"))))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage("It could not be read as a PDF.");
  }

  /** A reading that goes on until its test is over, whatever it is told. */
  static String goesOnUntil(CountDownLatch over) {
    while (true) {
      try {
        over.await();
        return "";
      } catch (InterruptedException toldToStop) {
        // It does not stop.
      }
    }
  }

  /**
   * What asking for a text within a time comes to: the text, or why there is none. It is asked
   * apart and given two seconds, so that a reading that is waited for fails its test.
   */
  private static String asked(Duration longest, Callable<String> text) throws Exception {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return DocumentText.within(longest, text);
              } catch (DocumentText.Unreadable why) {
                return "Unreadable: " + why.getMessage();
              }
            })
        .get(2, TimeUnit.SECONDS);
  }

  @Test
  void aReadingThatNeverEndsFailsOnceItsTimeIsOverAndIsNotWaitedFor() throws Exception {
    var over = new CountDownLatch(1);
    try {
      assertThat(asked(Duration.ofSeconds(1), () -> goesOnUntil(over)))
          .isEqualTo("Unreadable: It took too long to read.");
    } finally {
      over.countDown();
    }
  }

  @Test
  void aPdfWaitsForAReadingThatWasLeftBehindAndIsReadOnceThatHasEnded() throws Exception {
    var over = new CountDownLatch(1);
    try {
      assertThat(asked(Duration.ofMillis(200), () -> goesOnUntil(over)))
          .isEqualTo("Unreadable: It took too long to read.");

      assertThat(asked(Duration.ofMillis(200), () -> "Launch notes"))
          .isEqualTo("Unreadable: It waited too long for another PDF to be read.");
    } finally {
      over.countDown();
    }
    assertThat(asked(Duration.ofSeconds(1), () -> "Launch notes")).isEqualTo("Launch notes");
  }
}
