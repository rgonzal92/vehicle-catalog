# How well the language model does what it is asked

Cases with known answers, run against the real model. They are kept in `backend/src/test/resources/evaluation/`. A third of each set is held out: those cases are not looked at while a prompt is changed, and are reported apart, so that they say how the prompt does on sentences it was not written for.

- Run on 2026-10-10 with `gpt-6-luna`.
- The run cost US$0.0183068 by the table of spending, of the dollar a day may cost.
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
| Worked on | 8 | 8 |
| Held out | 4 | 4 |

None failed.
