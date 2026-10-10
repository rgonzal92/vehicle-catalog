package dev.rgonz.catalog.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Checks how a document's text is split into the passages that are searched. */
class PassagesTest {
  private static String sentences(int from, int to) {
    return IntStream.rangeClosed(from, to)
        .mapToObj(
            number -> "Sentence number %d says something about the catalog.".formatted(number))
        .collect(Collectors.joining(" "));
  }

  @Test
  void aShortTextIsOnePassageWithItsSpaceTidied() {
    assertThat(Passages.of("  Launch notes\r\n\r\n\r\nThe hybrid   follows\tin the autumn. \n"))
        .containsExactly("Launch notes\n\nThe hybrid follows in the autumn.");
  }

  @Test
  void aTextWithNothingInItHasNoPassage() {
    assertThat(Passages.of(" \n\t\n ")).isEmpty();
  }

  @Test
  void paragraphsAreKeptWholeAndAPassageStartsWithHowTheOneBeforeEnded() {
    var first = "First. " + sentences(1, 18);
    var second = "Second. " + sentences(19, 36);
    var closing = "A closing line.";

    var passages = Passages.of(first + "\n\n" + second + "\n\n" + closing);

    // Each paragraph fits a passage and two do not, so a passage ends where a paragraph does. The
    // closing line is short enough to be said again at the start of what follows it.
    assertThat(passages).containsExactly(first, second + "\n\n" + closing);
    assertThat(passages)
        .allSatisfy(one -> assertThat(one.length()).isLessThanOrEqualTo(Passages.LONGEST));
  }

  @Test
  void aParagraphLongerThanAPassageIsCutWhereASentenceEndsAndTheNextStartsWithHowItEnded() {
    var passages = Passages.of(sentences(1, 80));

    assertThat(passages).hasSizeGreaterThan(2);
    assertThat(passages)
        .allSatisfy(
            one -> {
              assertThat(one.length()).isLessThanOrEqualTo(Passages.LONGEST);
              assertThat(one).startsWith("Sentence number ").endsWith("about the catalog.");
            });
    for (int next = 1; next < passages.size(); next++) {
      var before = passages.get(next - 1);
      var lastSentence = before.substring(before.lastIndexOf("Sentence number "));
      // As many of its last sentences as are short enough together, which here is three.
      assertThat(passages.get(next).substring(0, Passages.OVERLAP))
          .as("how passage %d starts", next)
          .contains(lastSentence);
      assertThat(before).endsWith(passages.get(next).substring(0, lastSentence.length() * 3 + 2));
    }
    // Nothing is lost: every sentence is in some passage.
    for (int number = 1; number <= 80; number++) {
      assertThat(String.join(" ", passages)).contains("Sentence number %d says".formatted(number));
    }
  }

  @Test
  void aSentenceLongerThanAPassageIsCutBetweenWords() {
    var passages = Passages.of("word ".repeat(1000).strip());

    assertThat(passages).hasSizeGreaterThan(2);
    assertThat(passages)
        .allSatisfy(
            one -> {
              assertThat(one.length()).isLessThanOrEqualTo(Passages.LONGEST);
              assertThat(one).startsWith("word").endsWith("word");
            });
  }

  @Test
  void textWithoutASpaceToCutAtIsCutAnyway() {
    assertThat(Passages.of("x".repeat(Passages.LONGEST * 2 + 10)))
        .hasSize(3)
        .allSatisfy(one -> assertThat(one.length()).isLessThanOrEqualTo(Passages.LONGEST));
  }
}
