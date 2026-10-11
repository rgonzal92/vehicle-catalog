# Labels freeze at approval; validation stays live

Approving a catalog records the names and order of its trims and of its regions, and the names and categories of its features, as they were at that moment. The trims and regions each of its rules lists are read in that order too. An Approved version always displays, compares, and exports with those frozen labels, so history reads as it did when it was approved. Validation deliberately does the opposite: an Approved version is checked against today's global rules and today's library status, so a later rule change shows up as issues on catalogs that were valid when approved.

## Consequences

The same Approved version can show a feature under its old name while reporting an issue caused by a rule added last week. That is intended: labels are history, validity is current.
