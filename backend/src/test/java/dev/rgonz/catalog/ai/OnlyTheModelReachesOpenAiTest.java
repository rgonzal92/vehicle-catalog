package dev.rgonz.catalog.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Holds the backend to one way to OpenAI: the class that reserves what a request may cost before it
 * sends it. Nothing else may so much as name the client.
 */
class OnlyTheModelReachesOpenAiTest {
  @Test
  void noClassButTheModelNamesTheClientForOpenAi() throws IOException {
    try (var sources = Files.walk(Path.of("src/main/java"))) {
      assertThat(
              sources
                  .filter(file -> file.toString().endsWith(".java"))
                  .filter(
                      file -> {
                        try {
                          var source = Files.readString(file);
                          return source.contains("org.springframework.ai")
                              || source.contains("com.openai");
                        } catch (IOException unread) {
                          throw new IllegalStateException(unread);
                        }
                      })
                  .map(file -> file.getFileName().toString()))
          .containsExactly("Model.java");
    }
  }
}
