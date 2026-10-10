# How well the language model does what it is asked

Cases with known answers, run against the real model. They are kept in `backend/src/test/resources/evaluation/`. A third of each set is held out: those cases are not looked at while a prompt is changed, and are reported apart, so that they say how the prompt does on sentences it was not written for.

- Run on 2026-10-10 with `gpt-6-luna`.
- The run cost US$0.02724964 by the table of spending, of the dollar a day may cost.
- To run it again, with `OPENAI_API_KEY` in the environment: `./mvnw verify -Pevaluation` in `backend/`. It writes this file, and is no part of the build or of the pipeline.

## Rule suggestions

A case passes when the suggested rule equals the expected one in its kind, its source, its targets, its trims, and its regions, or when no rule is suggested where none is expected. An exclusion says the same whichever of its two features is its source. Nothing is judged by a model.

| Cases | Passed | Of |
| --- | --- | --- |
| Worked on | 22 | 22 |
| Held out | 11 | 11 |

None failed.

## Summaries

A case passes when there is a summary, when it mentions everything the case requires, and when it mentions nothing the case names as outside the changes. The application itself gives no summary that names a feature or a trim the changes do not have, so such a summary fails as one that is not there. Words are looked for whatever their case, and nothing is judged by a model.

| Cases | Passed | Of |
| --- | --- | --- |
| Worked on | 6 | 6 |
| Held out | 3 | 3 |

None failed.

## The analyst

A case passes when the tools it names were called, when the answer holds every fact it requires, and when the answer holds nothing it names as a working copy's. The working copy of such a case has a trim, Performance, that no Approved catalog has. Words are looked for whatever their case, and nothing is judged by a model.

| Cases | Passed | Of |
| --- | --- | --- |
| Worked on | 7 | 8 |
| Held out | 4 | 4 |

### Failed

- `where-a-feature-is-found` (worked on): "Is there a feature for massaging seats, and which vehicle line offers it?"
  - expected: calls search_features, feature_availability; holds Massaging Front Seats or SEAT_MASSAGE_FRONT, Sedan; does not hold anything in particular
  - came: called search_features; answered: The feature library has no match for “massaging seats,” so I can’t determine whether it is offered or which vehicle line offers it.

## Answers from documents

A case passes when the answer cites every document the case expects and none it forbids, such as the note of another model year, and when the tools it names were called. A case that expects no document passes only when nothing is cited: that is a question the chosen documents do not cover, or one about what a catalog offers, which is looked up and not read from a note. Nothing is judged by a model.

| Cases | Passed | Of |
| --- | --- | --- |
| Worked on | 10 | 10 |
| Held out | 5 | 5 |

How alike the closest passage of the chosen notes is to a question, from 0 to 1, for the cases that are worked on: 0.36 to 0.61 where the notes answer the question, and 0.18 to 0.25 where they do not. A search returns no passage that is less alike than 0.30.

None failed.
