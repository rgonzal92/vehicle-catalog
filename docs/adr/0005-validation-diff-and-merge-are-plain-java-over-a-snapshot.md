# Validation, diff, and merge are plain Java over a snapshot

A catalog is loaded into one in-memory snapshot, and validation, diff, and three-way merge are plain Java classes that take snapshots and return results, with no framework or database imports. Every edit re-validates the whole catalog instead of only the part that changed. We chose this over SQL-side or incremental logic because these operations are mostly decisions, and as pure functions every row of their rule tables can be tested without a database.

## Consequences

- Edit latency grows with catalog size. It is measured at the largest supported catalog; the upgrade path is to re-validate only the offerings an edit touches.
- SQL stays in the application for set-shaped reads and writes. The one stored function is the catalog copy, which is several statements with no decision between them.
