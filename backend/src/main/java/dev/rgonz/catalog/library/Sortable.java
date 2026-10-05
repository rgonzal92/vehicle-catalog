package dev.rgonz.catalog.library;

/** A library entry with a sort order: its place in a list an admin keeps in order. */
interface Sortable {
  void setSortOrder(int sortOrder);
}
