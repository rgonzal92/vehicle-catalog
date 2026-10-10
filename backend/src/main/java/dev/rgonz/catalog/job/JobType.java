package dev.rgonz.catalog.job;

/** The kinds of work the worker does. A job's type says which handler runs it. */
public enum JobType {
  /** What follows an approval: the catalog's owner is told. */
  AFTER_APPROVAL,
  /** What follows a change of the library: a lineage's current Approved is validated again. */
  RECHECK_APPROVED,
  /** What follows a request for a catalog as a spreadsheet: the file is built and kept. */
  EXPORT,
  /** What follows a submit: the language model writes a summary of what the catalog changes. */
  SUMMARISE_SUBMISSION
}
