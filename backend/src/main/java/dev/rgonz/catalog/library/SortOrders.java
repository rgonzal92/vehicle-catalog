package dev.rgonz.catalog.library;

import java.util.ArrayList;
import java.util.List;

/** Keeps the sort orders of a list of library entries running from 1 with no gaps. */
final class SortOrders {
  private SortOrders() {}

  /**
   * Gives the entry the sort order and renumbers every entry, so the others close up or make room.
   * A sort order past either end means that end.
   */
  static <T extends Sortable> void move(List<T> inOrder, T entry, int sortOrder) {
    var reordered = new ArrayList<>(inOrder);
    reordered.remove(entry);
    reordered.add(Math.clamp(sortOrder, 1, reordered.size() + 1) - 1, entry);

    for (int index = 0; index < reordered.size(); index++) {
      reordered.get(index).setSortOrder(index + 1);
    }
  }
}
