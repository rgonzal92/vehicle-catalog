package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the backend to one place where a catalog's revision is raised and its history written: the
 * module every change of a working copy and every decision about it goes through. The library
 * numbers itself with a revision of its own, which is not a catalog's.
 */
class OnlyRevisionsNumbersAndRecordsAChangeTest {
  private static final List<Pattern> WRITES =
      List.of(
          Pattern.compile("revision\\s*=\\s*revision\\s*\\+\\s*1"),
          Pattern.compile("INSERT\\s+INTO\\s+catalog_change\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("INSERT\\s+INTO\\s+catalog_review\\b", Pattern.CASE_INSENSITIVE));

  @Test
  void noClassButRevisionsRaisesACatalogsRevisionOrWritesItsHistory() throws IOException {
    try (var sources = Files.walk(Path.of("src/main/java"))) {
      assertThat(
              sources
                  .filter(file -> file.toString().endsWith(".java"))
                  .filter(file -> !file.toString().contains("/library/"))
                  .filter(
                      file -> WRITES.stream().anyMatch(write -> write.matcher(read(file)).find()))
                  .map(file -> file.getFileName().toString()))
          .containsExactly("Revisions.java");
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException unread) {
      throw new IllegalStateException(unread);
    }
  }
}
