package dev.rgonz.catalog.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document's text into passages: pieces short enough that one is about one thing, which is
 * what makes a passage findable by what it means. A passage ends where a paragraph does when it
 * can, or else where a sentence does, and starts with how the one before it ended, so that what was
 * said across the cut is whole in one of the two.
 */
final class Passages {
  /**
   * The longest a passage is, in characters: about 400 tokens of English.
   *
   * <p>ponytail: tokens are counted as four characters each, which English comes to. A tokenizer
   * when a passage has to fit a count of tokens exactly; the embedding model takes twenty times
   * this, so no passage is near what it refuses.
   */
  static final int LONGEST = 1600;

  /** How much of a passage's end the next one may start with: about 50 tokens. */
  static final int OVERLAP = 200;

  /** The most passages a document is read as. One with more text than that is not taken. */
  static final int MOST = 300;

  /** What a document that is too long is told. */
  static final String TOO_LONG = "It is too long: a document is read as at most 300 passages.";

  private Passages() {}

  /** The passages of a text, in its order. A text with nothing in it has none. */
  static List<String> of(String text) {
    var passages = new ArrayList<String>();
    var held = new ArrayList<Piece>();
    // Whether what is held has anything that no passage has yet.
    var anythingNew = false;
    for (var piece : pieces(text)) {
      if (anythingNew && !fits(held, piece)) {
        passages.add(joined(held));
        held = endOf(held);
        if (!fits(held, piece)) {
          held.clear();
        }
      }
      held.add(piece);
      anythingNew = true;
    }
    if (anythingNew) {
      passages.add(joined(held));
    }
    return passages;
  }

  private static boolean fits(List<Piece> held, Piece piece) {
    return held.isEmpty()
        || joined(held).length() + piece.joinedBy().length() + piece.text().length() <= LONGEST;
  }

  /**
   * A piece that is never cut: a paragraph, or a sentence or a run of words of one that is too
   * long.
   *
   * @param joinedBy what stands between it and the piece before: a blank line before a paragraph, a
   *     space within one
   */
  private record Piece(String joinedBy, String text) {}

  private static List<Piece> pieces(String text) {
    var pieces = new ArrayList<Piece>();
    for (var paragraph : text.replace("\r", "").split("\\n\\s*\\n")) {
      var tidy = paragraph.replaceAll("\\s+", " ").strip();
      if (tidy.isEmpty()) {
        continue;
      }
      var joinedBy = "\n\n";
      for (var part : tidy.length() <= LONGEST ? List.of(tidy) : sentences(tidy)) {
        pieces.add(new Piece(joinedBy, part));
        joinedBy = " ";
      }
    }
    return pieces;
  }

  /** The sentences of a paragraph, one that is too long cut between words, or anywhere at last. */
  private static List<String> sentences(String paragraph) {
    var parts = new ArrayList<String>();
    for (var sentence : paragraph.split("(?<=[.!?])\\s+")) {
      var rest = sentence;
      while (rest.length() > LONGEST) {
        int cut = rest.lastIndexOf(' ', LONGEST);
        parts.add(rest.substring(0, cut > 0 ? cut : LONGEST));
        rest = rest.substring(cut > 0 ? cut + 1 : LONGEST);
      }
      parts.add(rest);
    }
    return parts;
  }

  /** The last pieces of a passage that together are short enough to be said again. */
  private static ArrayList<Piece> endOf(List<Piece> passage) {
    var end = new ArrayList<Piece>();
    for (int at = passage.size() - 1; at > 0; at--) {
      var piece = passage.get(at);
      if (joined(end).length() + piece.joinedBy().length() + piece.text().length() > OVERLAP) {
        break;
      }
      end.addFirst(piece);
    }
    return end;
  }

  private static String joined(List<Piece> pieces) {
    var text = new StringBuilder();
    for (var piece : pieces) {
      text.append(text.isEmpty() ? "" : piece.joinedBy()).append(piece.text());
    }
    return text.toString();
  }
}
