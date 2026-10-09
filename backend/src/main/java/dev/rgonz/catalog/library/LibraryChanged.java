package dev.rgonz.catalog.library;

/**
 * Says that the library has changed in a way that can break a catalog, and which revision that left
 * it at. It is said inside the transaction of the change, so whoever hears it can write what
 * follows from the change with the change itself.
 */
public record LibraryChanged(long revision) {}
