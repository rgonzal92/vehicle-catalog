package dev.rgonz.catalog.core;

/**
 * Removes what a part of the app keeps outside the database, which the demo reset cannot empty with
 * the tables. It runs in each demo reset, while the reset holds the tables: nothing is added to
 * what it removes while it does. A cleanup that fails is said in the log and stops nothing.
 */
public interface ResetCleanup {
  void clean();
}
