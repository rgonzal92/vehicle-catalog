package dev.rgonz.catalog.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.document.Documents.Kind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
}
