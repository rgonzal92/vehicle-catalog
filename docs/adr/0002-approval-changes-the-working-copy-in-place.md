# Approval changes the working copy in place

A working copy and an Approved version are the same kind of record. Approval changes the working copy's status, gives it the next version number in its lineage, and makes it immutable; nothing is copied at approval time. Copies are made only when a working copy is created, so the expensive operation happens once per working copy, and an Approved version keeps the change history of the editing that produced it.

## Consequences

- The owner no longer has that working copy after approval. Continuing means creating a new one from the Approved version.
- Other working copies of the lineage become stale, and submitted ones are returned.
- Because an Approved version never changes, anything derived from it, such as a cached snapshot or an export, cannot go out of date.
