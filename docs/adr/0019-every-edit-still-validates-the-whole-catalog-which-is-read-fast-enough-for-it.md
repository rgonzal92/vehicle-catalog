# Every edit still validates the whole catalog, which is read fast enough for it

An edit of a working copy is answered as before: with the revision the catalog is at, every issue the catalog then has, the rules the edit deleted on its way, and whether the catalog is stale. To work the issues out the API reads the whole catalog again and [validates all of it](0005-validation-diff-and-merge-are-plain-java-over-a-snapshot.md). The page puts the answer's list of issues in the place of its own, unless it has already heard an answer of a higher revision.

At the largest size a catalog can have, 500 feature rows by 96 offerings, that answer took half a second, and [the measurements](../largest-catalog-measurements.md) put nearly all of it down to reading 24,000 stored cells back from the database. Measured part by part, neither the database nor the number of cells is what takes the time. The API made a record of each cell by handing Spring the record's class, and that took 13 microseconds a cell. So the cells are read by a mapper written for them, and that is the whole change.

| Median of 30 runs, in milliseconds | By the record's class | By a mapper written for the cells |
|---|---|---|
| Reading the 24,000 cells | 315 | 6.3 |
| Reading the whole catalog | 388 | 18 |
| Reading and validating it, which is what an edit's answer does | 417 | 45 |
| A save of one cell with its answer, through the test's web layer | 746 | 54 |

PostgreSQL answers the query for the cells in 3 to 4 ms, and the rows are in the application in 5. The figures are of a test's own process on 2026-10-11, with the catalog the measurements describe; the 746 is that document's, from 2026-10-09. Through the web server, where a save took a median of 512 ms, it takes a median of 57 ms with the mapper, measured on 2026-10-12. What inside Spring's mapping takes the time was not traced.

## Considered Options

- **Leaving it as it was.** The demo has no catalog near this size. The seeded sedan is 172 feature rows by 10 offerings, a save of one of its cells is answered in a median of 70 ms, and no visitor waits half a second. That is reason enough to leave what an edit answers with alone. It is no reason to keep a way of reading that every read of a whole catalog pays for.
- **Validating only the offerings an edit touches, and reading only those.** It is the upgrade path that the record of validating the whole names. What it would be:
  - A save of cells touches the offerings of its cells, which is one offering as the page sends it, and one thing more: whether each feature row it set is still offered in any offering, which is a Warning of the feature row and of no offering. Renaming touches nothing. Every other edit can touch every offering, or issues that are of no offering, and would be validated whole, as now: a rule added, changed, or deleted, a feature row, a trim, or a region added or removed, where a trim is sold, and an update from Approved.
  - The answer would hold the issues of what the edit touched, and name the offerings and the feature rows they are of. The page would take the issues of those out of its list and put the answer's in.
  - For one offering the API would read the catalog's own row, the names of the offering's trim and region, the feature rows for their names, the offering's cells, which are 500 at most, every rule of the catalog, the global rules, and whether the feature rows that were set have a cell left. The larger of those parts and the validation of one offering, each measured by itself and never run together, come to between 9 and 15 ms, where reading and validating the whole takes 45.
  - Three things that come with the whole list would each have to be settled. The list is in the order validation finds the issues in, and a list put together from parts has that order only if the page sorts as validation does. An answer also brings what a change of the library has done to the whole catalog since the answer before it, and a part would bring that for one offering. And a page that was loaded before the release would take a part for the whole, so the page would have to ask for the part.

  It would save some 30 of the 54 ms that are left at the largest size. For that it changes validation, the reading, what an edit answers with, and how the page keeps its issues. It stays the path to take when catalogs outgrow what is here.
- **Keeping a catalog's issues in the database, offering by offering.** An edit would work out one offering's again and answer with the whole list as kept, and the page would not change. But issues are worked out against the library as it is today, so a retired feature, a deactivated trim or region, and a changed global rule would each have to find the kept issues they make wrong. [The cache of Approved versions](0016-the-contents-of-approved-versions-are-read-from-a-cache-that-is-never-needed.md) keeps nothing that can go wrong, for the same reason.

## Consequences

- Nothing changes in what the API answers with or in the page. The issues, the marks, and the counts after any edit are those of a fresh opening of the catalog, because both come from one validation of the whole.
- Every read of a whole catalog gains, and not the answer to an edit alone: opening a catalog, the list of a person's working copies, which reads and validates each one whose kept counts of Errors and Warnings are of another revision of the copy or of the library, submitting, approving, the changes against another catalog, an update from Approved, an export, and a read of an Approved version that the cache does not hold.
- The editor sends one save at a time. At half a second an answer, someone who set more than two cells a second left a queue of saves behind them. At 54 ms an answer it takes eighteen a second.
- The answer to an edit still grows with the catalog. At the largest size about 23 ms of it are validation and 18 the reading. The catalog measured has half of its cells stored. One with every cell stored has twice as many to read, and was not measured.
- Only the cells have a mapper of their own. The other lists are short: the 500 feature rows are read by their record's class in 3 ms.
