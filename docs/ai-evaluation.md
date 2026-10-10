# How well the language model does what it is asked

Cases with known answers, run against the real model. They are kept in `backend/src/test/resources/evaluation/`. A third of each set is held out: those cases are not looked at while a prompt is changed, and are reported apart, so that they say how the prompt does on sentences it was not written for.

- Run on 2026-10-10 with `gpt-6-luna`.
- The run cost US$0.03295946 by the table of spending, of the dollar a day may cost.
- To run it again, with `OPENAI_API_KEY` in the environment: `./mvnw verify -Pevaluation` in `backend/`. It writes this file, and is no part of the build or of the pipeline.
- One asking does not show a failure that comes and goes. To ask chosen cases several times each, add `-Dask=` with their ids and `-Dtimes=` with how often, which is ten times when it is left out. Commas go between ids, and `rule-suggestions`, `analyst`, or `documents` in the place of an id asks every case of that set that is worked on. Such a run asks no held-out case, leaves this file as it is, and writes `backend/target/ai-evaluation-asked-often.md`.

## Rule suggestions

A case passes when the suggested rule equals the expected one in its kind, its source, its targets, its trims, and its regions, or when no rule is suggested where none is expected and the person is told the reason the case names. An exclusion says the same whichever of its two features is its source. Nothing is judged by a model.

| Cases | Passed | Of |
| --- | --- | --- |
| Worked on | 28 | 28 |
| Held out | 14 | 14 |

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
| Worked on | 8 | 8 |
| Held out | 4 | 4 |

None failed.

## Answers from documents

A case passes when the answer cites every document the case expects and none it forbids, such as the note of another model year, and when the tools it names were called. A case that expects no document passes only when nothing is cited: that is a question the chosen documents do not cover, or one about what a catalog offers, which is looked up and not read from a note. Nothing is judged by a model.

| Cases | Passed | Of |
| --- | --- | --- |
| Worked on | 10 | 10 |
| Held out | 5 | 5 |

How alike the closest passage of the chosen notes is to a question, from 0 to 1, for the cases that are worked on: 0.36 to 0.61 where the notes answer the question, and 0.18 to 0.25 where they do not. A search returns no passage that is less alike than 0.30.

None failed.
