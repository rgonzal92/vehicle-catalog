# Each edit writes the content tables it changes, with no store between

A catalog's contents are held in nine tables: `catalog_trim`, `catalog_region`, `catalog_trim_region`, `catalog_feature`, `catalog_cell`, `catalog_rule`, `catalog_rule_target`, `catalog_rule_trim`, and `catalog_rule_region`. The class that makes a change writes the statements for it, and no module stands between the classes and the tables.

The maintainer accepted this decision on 2026-10-15.

On that day, 85 statements in the backend's main code named those tables:

| Class | Statements | What they do |
|---|---|---|
| `CatalogRules` | 38 | Add, change, and remove a catalog rule and its pair |
| `CatalogEdits` | 26 | Set a cell, add or remove a feature row, a trim, or a region, and delete a working copy |
| `Catalogs` | 12 | Read a whole catalog |
| `Updates` | 6 | Replace a working copy's contents with a merged snapshot |
| `Reviews` | 3 | Freeze an Approved version's names and order |

The seeds and the stored function that copies a catalog name them too.

A module that alone knows the tables was weighed. Narrowed to what is done to a whole catalog, it would read, replace, delete, and freeze, and take the 21 statements of `Catalogs`, `Updates`, and `Reviews`. Each of those four operations would have one caller, and the 64 statements of single edits would stay where they are. The statements would be in a new place without being in fewer places. A table added to a catalog's contents would still be a change to the edits that write it, to the read, to the copy, and to the seeds.

## Considered Options

- **One module for every statement, the edits' included.** Each edit of `CatalogEdits` and `CatalogRules` would call a method of the module made for it alone, which is the edit itself under another name. The module's interface would be as wide as its implementation.
- **One module for what is done to a whole catalog.** As above: four operations, one caller each.
- **One rule for the names an Approved version shows.** The names seemed to be read three ways, by `Catalogs`, `ChangeHistory`, and `CatalogRules`. They are one rule: an Approved version shows the names it was approved with, which only an Approved version has, and every other catalog shows the library's of today. `Catalogs` asks for them when it reads a version as approved. `ChangeHistory` takes them when there are any. `CatalogRules` writes the history of working copies, which have none. The update from Approved reads an Approved version by today's names on purpose, to set it beside a working copy. There was nothing to put in one place.

## Consequences

- A table added to a catalog's contents is written in each class that changes it, in the read of a catalog, in `R__copy_catalog.sql`, and in the seeds. The test that counts what is left in every table with a `catalog_id` column after a delete (`RenameAndDeleteIT`) finds a table a delete has missed.
- The order the tables are deleted in is written twice, in `CatalogEdits.delete` and `Updates.write`, each with a comment that says why the rules go first.
- SQL stays where sets are read and written, and validation, diff, and merge stay plain Java over a snapshot, as [the record of that decision](0005-validation-diff-and-merge-are-plain-java-over-a-snapshot.md) says.
- The question is worth asking again when one change has to reach a table in many classes at once, such as a migration that splits a table, or when a class that writes a table and the read of it are found to disagree.
