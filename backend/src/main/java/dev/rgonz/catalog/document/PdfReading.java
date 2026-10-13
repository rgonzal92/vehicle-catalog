package dev.rgonz.catalog.document;

import ch.qos.logback.classic.Level;
import dev.rgonz.catalog.document.DocumentText.Unreadable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one PDF in a process of its own, which the worker starts for it. What PDFBox makes of a
 * file in memory has no limit that can be set, and a file of a kilobyte can fill a heap as large as
 * the worker's. So the heap that is filled is that process's own, which is small, and Java ends
 * that process and not the worker.
 *
 * <p>The worker writes the file to the process's input and takes what the process writes to its
 * output, and goes by how the process ended for what that is. {@link #of} is the worker's side and
 * {@link #main} the process's.
 */
final class PdfReading {
  private static final Logger log = LoggerFactory.getLogger(PdfReading.class);

  /**
   * What Java is told as the process starts: the most its heap may be, to end the process when the
   * heap is full, and to take less time and memory to start than it does by itself. They are all it
   * is told, since the process is given none of the worker's environment.
   */
  static final List<String> OPTIONS =
      List.of(
          "-Xmx64m", "-XX:+ExitOnOutOfMemoryError", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC");

  static final String TAKES_TOO_MUCH_MEMORY = "It takes more memory to read than a PDF may.";

  private static final String NO_PDF = "It could not be read as a PDF.";

  /** How Java ends a process whose heap is full, when it is told to end it then. */
  private static final int HEAP_FULL = 3;

  /**
   * How the process ends when what it wrote is why the PDF cannot be read. Java itself ends a
   * process with 1 when it cannot start it or its main class fails, with 3 for a full heap, and
   * with 128 and a signal's number when a signal ended it.
   */
  private static final int GAVE_THE_REASON = 65;

  /**
   * Spring Boot's launcher, which is what finds the classes inside the one jar the image holds. It
   * is on the class path there and nowhere else, so no test of the backend starts the process
   * through it. The browser test "an admin uploads a PDF, which the worker reads from the image's
   * jar, and deletes it" in {@code e2e/tests/documents.spec.ts} does, since its worker runs from
   * the image. With a name here that is not the launcher's, every PDF fails in the image as one
   * that could not be read, and that test with it.
   */
  private static final String LAUNCHER =
      "org.springframework.boot.loader.launch.PropertiesLauncher";

  /**
   * The most the worker takes of what a process writes, in bytes: the most text a document may
   * have, at the three bytes that a character takes at most.
   */
  private static final int MOST_WRITTEN = 3 * DocumentText.MOST_TEXT;

  /** The most the worker passes on to its log of what a process wrote to its errors, in bytes. */
  private static final int MOST_PASSED_ON = 8 * 1024;

  private PdfReading() {}

  /**
   * The text of a PDF, read by a process that is started for it and has ended when this returns,
   * however it returns. A thread that is interrupted while it waits here ends the process.
   *
   * @throws Unreadable when the PDF cannot be read, with the reason
   * @throws IllegalStateException when the process cannot be started, which no file brings about
   * @throws InterruptedException when the thread is told to stop before the process has ended
   */
  static String of(byte[] file) throws InterruptedException {
    return read(file, command());
  }

  /**
   * How the process is started: with the Java the worker runs on, the worker's class path, and the
   * options above. From class directories the class is named. From the image's jar the launcher is,
   * and told which class to run.
   */
  static List<String> command() {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(OPTIONS);
    boolean fromTheJar =
        ClassLoader.getSystemResource(LAUNCHER.replace('.', '/') + ".class") != null;
    if (fromTheJar) {
      command.add("-Dloader.main=" + PdfReading.class.getName());
    }
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(fromTheJar ? LAUNCHER : PdfReading.class.getName());
    return command;
  }

  /**
   * The text that a process started so gives for a file. A test names a process of its own.
   *
   * <p>The process is given an empty environment: the worker's holds what Java is told as the
   * worker starts, and the passwords and keys the worker has, and the process reads a file that
   * anyone may have made. Its input, its output, and its errors are each written or read on a
   * thread of their own, so that neither side waits for the other to take what it writes.
   */
  static String read(byte[] file, List<String> command) throws InterruptedException {
    var starting = new ProcessBuilder(command);
    starting.environment().clear();
    Process process;
    try {
      process = starting.start();
    } catch (IOException notStarted) {
      throw new IllegalStateException("The process that reads a PDF was not started", notStarted);
    }
    try {
      beside(
          "pdf-given",
          () -> {
            try (var input = process.getOutputStream()) {
              input.write(file);
            }
            return null;
          });
      var written =
          beside(
              "pdf-written",
              () -> {
                byte[] all = process.getInputStream().readNBytes(MOST_WRITTEN + 1);
                if (all.length > MOST_WRITTEN) {
                  // More than any text may be, so no more of it is read, and the process may be
                  // waiting to write it. It is ended through its handle: ending it as a process
                  // closes its errors under the thread that still reads them.
                  process.toHandle().destroyForcibly();
                }
                return all;
              });
      var said = beside("pdf-said", () -> passedOn(process.getErrorStream()));

      int ended = process.waitFor();
      said.get();
      byte[] all = written.get();
      if (all.length > MOST_WRITTEN) {
        // Whatever the process ended with: it may have written the last of it and ended by itself.
        log.warn("The process that read a PDF wrote more than a text may be");
        throw new Unreadable(NO_PDF);
      }
      var text = new String(all, StandardCharsets.UTF_8);
      if (ended == 0) {
        return text;
      }
      if (ended == GAVE_THE_REASON) {
        throw new Unreadable(text);
      }
      if (ended == HEAP_FULL) {
        throw new Unreadable(TAKES_TOO_MUCH_MEMORY);
      }
      log.warn("The process that read a PDF ended with {}", ended);
      throw new Unreadable(NO_PDF);
    } catch (ExecutionException unread) {
      throw new IllegalStateException(
          "What the process that read a PDF wrote was not read", unread.getCause());
    } finally {
      // Ends it if it has not ended, and waits until it is gone, which being interrupted does not
      // cut short: the next PDF's process is never beside this one.
      process.destroyForcibly().onExit().join();
    }
  }

  /** Does something beside the wait for the process, on a thread that ends when the process has. */
  private static <T> FutureTask<T> beside(String name, Callable<T> something) {
    var task = new FutureTask<>(something);
    Thread.ofPlatform().daemon().name(name).start(task);
    return task;
  }

  /**
   * Passes on to the worker's log the start of what a process wrote to its errors, as one warning,
   * and reads the rest without keeping it, so that the process never waits to write more.
   */
  private static Void passedOn(InputStream errors) throws IOException {
    var said = new String(errors.readNBytes(MOST_PASSED_ON), StandardCharsets.UTF_8).strip();
    long more = errors.transferTo(OutputStream.nullOutputStream());
    if (!said.isEmpty()) {
      log.warn(
          "The process that read a PDF said{}:\n{}",
          more > 0 ? ", and %,d bytes more that are not kept".formatted(more) : "",
          said);
    }
    return null;
  }

  /**
   * The process: reads a PDF from its input and writes its text to its output, and ends with 0. For
   * a PDF that cannot be read it writes why and ends with a code of its own. Whatever else is
   * printed or logged goes to its errors, and of what PDFBox logs, the warnings and errors.
   *
   * <p>Java ends it when its heap is full. An error that is not the file's doing, such as a class
   * that is not there, is left to end it as Java ends a process whose main class fails, with what
   * went wrong in its errors.
   */
  public static void main(String[] none) throws IOException {
    var output = System.out;
    System.setOut(System.err);
    ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
        .setLevel(Level.WARN);

    String written;
    int ended;
    try {
      written = DocumentText.textOfPdf(System.in.readAllBytes());
      ended = 0;
    } catch (Unreadable why) {
      written = why.getMessage();
      ended = GAVE_THE_REASON;
    } catch (Exception | StackOverflowError unread) {
      // PDFBox could not read it, or went deeper into it than a thread can.
      written = NO_PDF;
      ended = GAVE_THE_REASON;
    }
    output.write(written.getBytes(StandardCharsets.UTF_8));
    output.flush();
    System.exit(ended);
  }
}
