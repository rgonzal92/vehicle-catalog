# Exclusions are stored as paired rules

An exclusion between two features is symmetric and could be stored once. We store it as two mirrored rules, A excludes B and B excludes A, which share a pair key and are created, changed, and removed together. Keeping both directions as visible, synchronised rules is part of what this application sets out to show about catalog authoring, and it gives every rule exactly one source feature, so a feature's rules are found by source alone.

## Considered Options

- **One row per exclusion, in a fixed order.** Simpler, with no invariant to maintain, but the pairing disappears from the data and every lookup has to check both columns.
- **Multi-target exclusions.** Rejected: the mirror of "A excludes B and C" is two rules, so the pair would no longer be exact. The rule form accepts several targets and creates one pair per target.

## Consequences

An invariant must hold after every rule operation, copy, and merge: each Excludes rule has exactly one partner with the same pair key, swapped source and target, and equal scope. Copy and merge treat a pair as one unit.
