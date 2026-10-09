# Vehicle Catalog

The language of authoring vehicle catalogs: stating which features each trim of a vehicle line offers in each region for a model year, the rules that relate those features, and the review that turns a working copy into an Approved version.

## Language

### Vehicles

**Vehicle type**:
A broad class of vehicle: car, SUV, truck, or van.
_Avoid_: Body style, segment

**Vehicle line**:
A product sold across model years, such as "Compact SUV". It has one vehicle type.
_Avoid_: Model, nameplate

**Model year**:
The year a vehicle line is sold as.
_Avoid_: Production year

**Lineage**:
One vehicle line in one model year, holding that line-year's chain of Approved versions. Carryover copies between lineages but never joins them.
_Avoid_: Program, model-year catalog

### Library

**Library**:
The admin-maintained definitions that every catalog shares: vehicle lines, trims, regions, features, and global rules.
_Avoid_: Master data, reference data

**Trim**:
An equipment level defined once in the library, such as Base or Sport.
_Avoid_: Trim level, grade

**Region**:
A market defined in the library, such as Europe.
_Avoid_: Market, country

**Feature**:
Anything a vehicle can be equipped with, defined once in the library and identified by a code that never changes.
_Avoid_: Option, part, component, equipment

**Package**:
A feature that brings other features with it. Packages form their own category.
_Avoid_: Bundle, option group

**Category**:
The vehicle system a feature belongs to, such as Powertrain or Interior.
_Avoid_: Group, section

**Retired**:
Said of a feature that can no longer be added to catalogs or rules. Trims, regions, and vehicle lines are **inactive** instead.
_Avoid_: Deleted, archived

### Catalogs

**Catalog**:
The full statement of what one lineage offers: its trims, regions, offerings, feature rows, cells, and catalog rules. Every catalog is either a working copy or an Approved version.

**Working copy**:
A catalog someone is editing, in status Draft or Submitted.
_Avoid_: Draft (that is a status), branch

**Approved version**:
An immutable catalog with a version number inside its lineage.
_Avoid_: Release, published catalog

**Current Approved**:
The newest Approved version of a lineage, and the source of truth for it.
_Avoid_: Latest, live catalog

**Base**:
The Approved version a working copy was copied from or last updated from. Unrelated to a trim that happens to be named "Base".
_Avoid_: Parent, source

**Stale**:
Said of a working copy whose base is no longer its lineage's current Approved.
_Avoid_: Outdated, conflicted

**Revision conflict**:
A write refused because the catalog changed after the writer last read it.
_Avoid_: Stale write

**Carryover**:
Starting a working copy from the nearest earlier model year's current Approved, when its own lineage has none. It is a one-time copy.
_Avoid_: Rollover, clone

**Update from Approved**:
Bringing the current Approved's changes into a stale working copy, keeping the owner's own changes and asking the owner to settle each conflict.
_Avoid_: Rebase, sync, refresh

### Matrix

**Matrix**:
A catalog's feature rows set against its offerings.
_Avoid_: Grid, table

**Offering**:
One trim sold in one region. Each offering is a column of the matrix.
_Avoid_: Column, market trim

**Feature row**:
A feature that a catalog has added. A catalog adds and removes trims, regions, and features; it never defines them.
_Avoid_: Selected feature, included feature

**Cell**:
The availability of one feature in one offering.

**Availability**:
What a cell states: **Standard** (S, on every vehicle of the offering), **Available** (A, can be ordered), or **Not offered** (N, shown as "-").
_Avoid_: Optional, included, status

### Rules

**Rule**:
A relationship between a source feature and one or more target features: Requires, Requires one of, Excludes, or Includes.
_Avoid_: Constraint, relationship

**Requires**:
The source needs every target.

**Requires one of**:
The source needs at least one target.

**Excludes**:
The source and the target cannot be on the same vehicle.
_Avoid_: Not allowed with, conflicts with

**Includes**:
A package brings every target with it.

**Paired rule**:
Either of the two mirrored rules kept for one exclusion: A excludes B, and B excludes A. The two form a **pair**, which is created, changed, and removed as one.
_Avoid_: Mirror rule, reverse rule

**Origin**:
Whether a rule is a **global rule**, defined in the library and applied to every catalog, or a **catalog rule**, belonging to one catalog.
_Avoid_: Scope, level

**Scope**:
The trims and regions a rule applies to; each is either all or a list. A global rule has a region scope only.
_Avoid_: Origin, applicability

### Validation

**Issue**:
One validation finding about a catalog. An **Error** blocks submit and approve; a **Warning** never blocks.
_Avoid_: Violation, problem. Never use it for tracker items, which are tickets.

**Select**:
What a buyer does with an Available feature when ordering a vehicle. A catalog never selects; it adds.

**Standard set**:
Every Standard feature of an offering, plus everything those features require or include.

**Selection set**:
The standard set plus one Available feature and everything it requires or includes: what a buyer gets by selecting that feature.

### Review

**Owner**:
The person who created a working copy, and the only one who can edit, submit, or withdraw it.
_Avoid_: Creator, author (that is a role)

**Reviewer**:
A manager or admin deciding on a submitted catalog they do not own.

**Returned**:
Sent back to Draft by the system because another catalog of the same lineage was approved first.
_Avoid_: Rejected (a reviewer's decision, which needs a comment)

### Jobs

**Job**:
Work that follows a change and is done afterwards, such as telling the owner of a catalog that it was approved. A job is written with its change and done once.
_Avoid_: Task, background task

**Worker**:
The process that does the jobs. It is the backend's own build, run beside the API.
_Avoid_: Consumer, job runner

**Notification**:
Something a person is told in the app about what happened to their work.
_Avoid_: Alert, message (a message is what tells the worker of a job)

### People

**Author**:
The role that creates and edits working copies. Every other role includes it.
_Avoid_: User (any signed-in person, not a role)

**Manager**:
An author who can also approve or reject other people's submissions.

**Admin**:
A manager who also maintains the library and other people's roles.

### Demo

**Demo account**:
A shared, published login, one per role.

**Sandbox account**:
An account that visitors to the public demo may see and manage. Every demo account is one.

**Protected account**:
An account whose role cannot be changed from the app: the demo accounts and the operator account.

**Operator account**:
The site operator's own login.
_Avoid_: Owner account

**Demo reset**:
The daily restore of seeded data, which deletes all visitor work.
