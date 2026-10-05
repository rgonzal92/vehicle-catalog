package dev.rgonz.catalog.library;

import java.util.ArrayList;
import java.util.List;

/** Keeps an ordered list of library entries numbered from 1 with no gaps. */
final class Positions {
  private Positions() {}

  /**
   * Puts the entry at the position and numbers every entry again, so the others close up or make
   * room. A position past either end means that end.
   */
  static <T extends Positioned> void move(List<T> inOrder, T entry, int position) {
    var reordered = new ArrayList<>(inOrder);
    reordered.remove(entry);
    reordered.add(Math.clamp(position, 1, reordered.size() + 1) - 1, entry);

    for (int index = 0; index < reordered.size(); index++) {
      reordered.get(index).setSortOrder(index + 1);
    }
  }
}
