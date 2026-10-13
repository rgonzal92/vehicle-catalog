package dev.rgonz.catalog.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.document.Documents.Kind;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the process that reads a PDF from the worker's side: that it is ended when its time is
 * over, what the worker makes of a process that cannot be started or did not end by itself, and
 * that the process is given nothing of the environment.
 */
class PdfReadingTest {
  private static final byte[] A_PDF = Pdfs.of(List.of("Launch notes"));

  /** Whether a process that this one started is still there. */
  private static boolean aProcessIsLeft() {
    return ProcessHandle.current().children().anyMatch(ProcessHandle::isAlive);
  }

  @Test
  void aReadingWhoseTimeIsOverEndsItsProcessAndThePdfAfterItIsRead() {
    // Drawing of 31 MB that shows nothing: within what a PDF may unpack to, and read for far longer
    // than a tenth of a second.
    var slow = Pdfs.ofDrawingThatUnpacksTo(31_000_000, false);

    assertThatThrownBy(() -> DocumentText.within(Duration.ofMillis(100), () -> PdfReading.of(slow)))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage("It took too long to read.");

    await().atMost(Duration.ofSeconds(1)).until(() -> !aProcessIsLeft());
    long start = System.nanoTime();
    assertThat(DocumentText.of(Kind.PDF, A_PDF)).contains("Launch notes");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void aProcessThatCannotBeStartedIsNoFailureOfTheFile() {
    assertThatThrownBy(
            () ->
                DocumentText.within(
                    Duration.ofSeconds(5),
                    () -> PdfReading.read(A_PDF, List.of("/there/is/no/such/java"))))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(DocumentText.Unreadable.class);
  }

  @Test
  void aProcessThatASignalEndedFailsItsFileAsOneThatCouldNotBeRead() {
    assertThatThrownBy(() -> PdfReading.read(A_PDF, List.of("sh", "-c", "kill -9 $$")))
        .isInstanceOf(DocumentText.Unreadable.class)
        .hasMessage("It could not be read as a PDF.");
    assertThat(aProcessIsLeft()).isFalse();
  }

  @Test
  void aProcessThatWritesMoreThanATextMayBeFailsItsFileAsOneThatCouldNotBeRead() {
    // The most text a document may have comes to 1,440,000 bytes. One byte more may all be written
    // before the process is ended, and it then ends by itself. Of two million it waits to write
    // the rest.
    for (var bytes : List.of("1440001", "2000000")) {
      assertThatThrownBy(() -> PdfReading.read(A_PDF, List.of("head", "-c", bytes, "/dev/zero")))
          .isInstanceOf(DocumentText.Unreadable.class)
          .hasMessage("It could not be read as a PDF.");
      assertThat(aProcessIsLeft()).isFalse();
    }
  }

  @Test
  void theProcessIsGivenNothingOfTheEnvironment() throws Exception {
    assertThat(System.getenv("HOME")).isNotEmpty();

    // A shell that was started so says whether it was handed a home, and never what it is.
    var said = PdfReading.read(A_PDF, List.of("sh", "-c", "printf %s \"${HOME:+it has a home}\""));

    assertThat(said).isEmpty();
  }
}
