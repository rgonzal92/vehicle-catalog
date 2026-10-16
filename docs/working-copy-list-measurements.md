# How long a person's list of working copies takes

How long the API takes to answer a person's list of twenty working copies, `GET /api/catalogs?scope=mine`, which the dashboard asks for each time it is opened and after a working copy is deleted or withdrawn, and where that time goes. These are the figures before and after the change that keeps those counts: one run on the deployed app before it and one after its release, and one run on one machine before it, each of twenty working copies of the demo's largest catalog, asked for by one person. [The decision](adr/0022-a-working-copys-counts-of-errors-and-warnings-are-kept-with-the-revisions-they-were-worked-out-against.md) that rests on them is to keep each working copy's counts of Errors and Warnings with the revisions they were worked out against, and the maintainer accepted it on 2026-10-14.

Where the time goes was known before any trace was read. The code marks the place as a shortcut taken on purpose, in `CatalogController.java`: "each working copy is read whole and validated, ten queries apiece for the 20 an owner can have at most. Keep the counts with the catalog if this list gets slow". The trace of the run's median ask confirmed it and measured it: 388 of the request's 417.6 ms go with the twenty reads, as [the trace](#the-trace-of-the-median-ask) below has it. After the change the median ask's trace holds the request's span alone, of 10.9 ms: the sixty steps are gone, as [the trace after the change](#the-trace-after-the-change) has it.

Five things limit what the figures say:

- **The catalogs are the demo's.** Every working copy is a copy of Sedan 2027 v1, the largest catalog the demo starts with: 172 feature rows by 10 offerings, with 1,070 stored cells and 6 rules. Twenty of the largest catalog there can be, 500 feature rows by 96 offerings, were not listed.
- **One person asked, one after another, and nobody else was made to.** The deployed app is open to anyone, and nothing kept others away during either run. The API's log for each run's minute holds no list but the run's and the command's own, so nobody else asked for a list then, and whether anyone asked for anything else was not looked at.
- **The API had just started.** The run began 1 minute 55 seconds after the API's start that the release before it caused, so the first asks were among the first requests the API answered. The API's own times for the thirty drift down through the run, from about 450 ms to about 380, so a warmer API may answer somewhat faster than the median says, and still well over the bar. The run after the change began 2 minutes 57 seconds after the API's start that the change's release caused.
- **The bar is held on asks that follow five others.** The first ask after the copies were made is written down by itself, and four more are not timed, before the thirty that are.
- **It is one run on the host before the change and one after it, and one on one machine before it.** The machine's run is of the command before a change to how it deletes, and a run after that change gave the same median. The machine was not run again after the counts were kept.

## Results

Times are in milliseconds. "As sent" is from sending the request until its whole answer had arrived, where it was sent from. "The API's own" is what the API's log says the request took, from the line `GET /api/catalogs 200 in … ms`. The two differ by what lies between, which on the host is the way to the site and back and the web server, and on the machine the web server alone.

### The times

| | Before: the host, as sent | Before: the host, the API's own | Before: one machine, as sent | After: the host, as sent | After: the host, the API's own |
|---|---|---|---|---|---|
| The first ask after the copies were made | 857 | 804 | 221 | 990 | 938 |
| Thirty one after another, median | 478 | 416 | 96 | 61 | 9 |
| Thirty one after another, 95th percentile | 572 | 458 | 125 | 129 | 15 |
| Thirty one after another, longest | 627 | 507 | 162 | 136 | 16 |

The columns before are of 2026-10-14, the host's from 03:58:54 UTC and the machine's from 03:52:26 UTC. The columns after are of the host on the same day, from 16:13:59 UTC, after the release of the change that keeps the counts.

The thirty on the host, in order, as sent: 493, 513, 487, 504, 508, 543, 505, 504, 572, 462, 477, 470, 497, 494, 627, 438, 438, 439, 478, 443, 447, 456, 488, 439, 428, 503, 465, 431, 493, 439. As the API's log has them: 440, 458, 432, 452, 454, 425, 452, 449, 456, 409, 426, 416, 432, 443, 507, 387, 389, 388, 428, 392, 396, 403, 435, 388, 378, 382, 408, 381, 377, 388. The four asks between the first and the thirty took the API 604, 526, 479, and 516. The median as the API's log has it is the 15th of the thirty sorted, 416, as the command counts; the 16th is 425. Sending adds about 60 ms to each: 478 against 416 at the median.

After the change, the thirty on the host, in order, as sent: 61, 66, 60, 129, 59, 59, 60, 128, 63, 67, 68, 58, 59, 58, 61, 63, 57, 65, 67, 68, 136, 60, 64, 68, 61, 59, 59, 55, 128, 59. As the API's log has them: 10, 13, 10, 9, 9, 7, 7, 9, 11, 10, 13, 9, 9, 7, 11, 9, 7, 7, 12, 15, 16, 8, 11, 15, 7, 8, 6, 7, 10, 8. The four asks between the first and the thirty took the API 12, 9, 10, and 13. The median as the API's log has it is the 15th of the thirty sorted, 9, and the 16th is 9 too. Sending adds about 50 ms: 61 against 9 at the median.

The first ask after the copies were made is not spared by the change. It finds no kept counts, so it reads all twenty whole as every ask did before, and keeps what it found. It took the API 938 ms, against 804 in the run before the change; [its trace](#the-trace-after-the-change) puts the writes of the counts between one copy's steps and the next's.

The thirty on the machine, as sent: 109, 106, 116, 125, 95, 107, 100, 96, 95, 112, 94, 98, 162, 99, 98, 95, 84, 109, 99, 95, 95, 91, 94, 95, 104, 89, 88, 91, 85, 100. An earlier run on the same machine, of the command before its deleting was changed, had the first ask at 214 and the thirty at a median of 96, a 95th percentile of 116, and a longest of 138.

### Statements PostgreSQL is sent for a list of twenty

| | Before | After |
|---|---|---|
| A list of twenty whose working copies were all listed before | 202 | 2 |
| The first ask after twenty working copies were made | 202 | 242 |

Before the change, one looks the caller up, one is the list itself, and ten are sent for each working copy: one for the catalog's own row, six for its contents, and three for the library as it is today, which the test of reading one catalog holds at ten. The 202 were first counted from the code, and `WorkingCopyListIT`, the test that came with the change, counted 202 when it was run once on the code before it. It counts the statements Spring's `JdbcTemplate` logs on the test's thread, as the test of reading one catalog does, and it holds both figures after the change. After it, a list whose working copies were all listed before sends the caller's lookup and the list's own statement, which brings each copy's kept counts with it. An ask that reads a copy whole sends two statements more for it than before: the library's revision, read in the same view as the copy's contents, and the write of its counts. That is twelve a copy, and 242 for the first ask after twenty were made. Beginning and ending each transaction, and the request's own session, are not among any of these.

### The trace of the median ask

The median ask is the one the API's log timed at 416 ms, at 03:59:09.818 UTC. Its trace holds 61 spans: the request's, `/api`, of 417.6 ms, and sixty steps, all directly under it, three for each of the twenty working copies. No other span: a working copy is not read from the cache. Of a span's record this gives the name, the parent's name, the start, the length, and the catalog's id, and nothing else.

| Step | How many | Together, ms | Each, ms |
|---|---|---|---|
| `catalog.read`, the catalog's own row and its contents | 20 | 196.6 | 9.0 to 16.0 |
| `catalog.library`, what validation needs of the library | 20 | 152.7 | 7.1 to 7.6, and the first 15.2 |
| `catalog.issues`, working out the issues | 20 | 15.8 | 0.76 to 0.88 |
| All steps | 60 | 365.2 | |
| The request | | 417.6 | |
| What the steps leave over | | 52.5 | |

The 52.5 ms the steps leave over, split by the steps' starts and lengths:

| | ms |
|---|---|
| Between one working copy's steps and the next's, where one transaction ends and the next begins: 19 gaps | 23.2, of 1.1 to 1.6 each |
| Within one working copy's three steps: 40 gaps | 2.3, of 0.05 to 0.1 each |
| Before the first step, which the request does once | 21.8 |
| After the last step, which the request does once | 5.1 |

So 388 ms of the 417.6 go with the twenty reads, which are the three steps and the gaps between copies, and about 27 ms is what the request does once: finding the session, looking the caller up, the list's own statement, and writing the answer out, none of which a step covers. The copies are listed newest first, and the trace reads them in that order. Each copy, with its steps' first start and last end counted from the request's start, and what each step took:

| Working copy | From | Until | `catalog.read` | `catalog.library` | `catalog.issues` |
|---|---|---|---|---|---|
| 1st | 21.8 | 48.0 | 10.0 | 15.2 | 0.9 |
| 2nd | 49.3 | 73.5 | 16.0 | 7.2 | 0.8 |
| 3rd | 74.7 | 93.2 | 10.2 | 7.4 | 0.8 |
| 4th | 94.3 | 112.2 | 9.8 | 7.2 | 0.8 |
| 5th | 113.3 | 131.5 | 9.8 | 7.4 | 0.8 |
| 6th | 132.6 | 150.2 | 9.3 | 7.3 | 0.9 |
| 7th | 151.5 | 169.5 | 9.9 | 7.3 | 0.8 |
| 8th | 171.1 | 188.5 | 9.2 | 7.2 | 0.8 |
| 9th | 189.7 | 207.2 | 9.4 | 7.3 | 0.8 |
| 10th | 208.5 | 225.8 | 9.3 | 7.1 | 0.8 |
| 11th | 227.0 | 244.3 | 9.2 | 7.3 | 0.8 |
| 12th | 245.5 | 263.8 | 9.7 | 7.6 | 0.8 |
| 13th | 265.1 | 282.4 | 9.3 | 7.2 | 0.8 |
| 14th | 283.6 | 301.0 | 9.3 | 7.2 | 0.8 |
| 15th | 302.2 | 320.0 | 9.8 | 7.1 | 0.8 |
| 16th | 321.0 | 338.3 | 9.2 | 7.2 | 0.8 |
| 17th | 339.5 | 357.1 | 9.5 | 7.2 | 0.8 |
| 18th | 358.3 | 375.4 | 9.1 | 7.1 | 0.8 |
| 19th | 376.5 | 393.5 | 9.0 | 7.1 | 0.8 |
| 20th | 394.7 | 412.5 | 9.7 | 7.2 | 0.8 |

### The trace after the change

The median ask after the change is the first of the thirty timed asks that the API's log timed at 9 ms, at 16:14:07.817 UTC. Its trace holds one span, the request's, `/api`, of 10.9 ms, and no step under it. Of a span's record this gives the same as above.

| | Before: the median ask, 416 ms in the log | After: the median ask, 9 ms in the log |
|---|---|---|
| `catalog.read` | 20 steps, 196.6 ms | none |
| `catalog.library` | 20 steps, 152.7 ms | none |
| `catalog.issues` | 20 steps, 15.8 ms | none |
| Between one working copy's steps and the next's | 23.2 ms | none |
| Within one working copy's three steps | 2.3 ms | none |
| Before the first step and after the last, which the request does once | 26.9 ms | |
| The request | 417.6 ms | 10.9 ms |

So what the change spared is every step: the sixty steps and the gaps between them, 390.6 ms of the 417.6. The 10.9 ms left are what the request does once, now with the list's own statement bringing the kept counts, and no step covers any of it.

The first ask after the copies were made still reads each whole. Its trace, at 16:14:07.227 UTC, 938 ms in the log, holds 61 spans: the request's, of 939.6 ms, and sixty steps under it.

| Step | How many | Together, ms | Each, ms |
|---|---|---|---|
| `catalog.read` | 20 | 563.1 | 13.0 to 70.8 |
| `catalog.library` | 20 | 139.6 | 1.8 to 17.9 |
| `catalog.issues` | 20 | 93.8 | 1.6 to 36.3 |
| All steps | 60 | 796.5 | |
| The request | | 939.6 | |
| What the steps leave over | | 143.1 | |

Of the 143.1 ms the steps leave over, 117.8 lie between one working copy's steps and the next's, in 19 gaps of 3.4 to 15.3 ms each, where the run before the change had 1.1 to 1.6: that is where the write of each copy's counts lies, which no step covers. 7.6 lie within one copy's three steps, 10.2 before the first step, and 7.5 after the last. The first ask of the run before the change was not traced, so its steps cannot be set beside these.

### The dashboard's chart

The chart "How long a request that succeeds takes", drawn by command from the dashboard's own definition of it for the ten minutes around the run, so it has no console around it:

![How long a request that succeeds takes, in milliseconds, from 03:54 to 04:04 UTC on 2026-10-14: one minute with points, 03:59, where 99 in 100 requests were within about 680 ms and half of them near the axis; the other nine minutes show nothing](working-copy-list-request-time-before.png)

It shows that something was slow at 03:59 and not what: the chart merges every route, since the API tells its requests apart by their outcome alone, and that minute holds the run's twenty creations and twenty deletions beside its thirty-five asks. The nine other minutes drew nothing, the release at 03:57 among them. What its "half of them" point stands at was not read from the metric.

The same chart for the ten minutes around the run after the change:

![How long a request that succeeds takes, in milliseconds, from 16:09 to 16:19 UTC on 2026-10-14: points at 16:14 and 16:15 only, joined by a line; 99 in 100 requests were within about 30 ms at 16:14 and within about 930 ms at 16:15, and half of them near the axis at both; the other eight minutes show nothing](working-copy-list-request-time-after.png)

The run's requests, from 16:13:54 to 16:14:12, show as two points where the run before had one. Which of its requests each point holds was not read from the metric, and the chart shows no more than the one before: the 99-in-100 point near 930 ms is as high as the first ask's 938, and the chart cannot say it is that ask. The release of the change, from 16:09 to 16:11, drew nothing.

### The maintainer's screenshot

The trace of the median ask before the change, `ce6f6f045daebad2208fec70a34111c5`, as the CloudWatch console draws it under Application Signals, Transaction Search, taken by the maintainer on 2026-10-16:

![The span timeline waterfall of the trace: 61 spans, the request /api of 418 ms on top as one long bar, and under it sixty short bars in a staircase from left to right, catalog.read, catalog.library, and catalog.issues for each of the twenty working copies in turn, reads of 9 to 16 ms, library reads of 7 to 15 ms, and issues of 1 ms or less](working-copy-list-trace-before.png)

The staircase is the twenty working copies read one after another, each with its read, its library, and its issues, and nothing of the request running beside them. The console gives each step's length in whole milliseconds, so its figures are the table's, rounded.

The panel scrolls, so it was taken as two pictures and joined where they overlap: the steps 21 to 36 are in both, the same to the pixel, and are kept once. The crop is the panel "Spans" alone: the console's bar and menu, the browser's address line, and everything of the console above the panel are outside it, and no span's details are open. It was looked at for everything [the dashboard's record](dashboard-and-alarms.md#how-a-picture-is-made-safe-to-publish) lists and shows none of them.

## What they say

**After the change the list of twenty is far under the bar.** The API answers it in a median of 9 ms on the host, where it took 416 before and a quarter of a second was asked for, with a 95th percentile of 15 and a longest of 16. The trace of the median ask has none of the sixty steps it had: no working copy is read, and the request's 10.9 ms are what it does once. A list of twenty whose copies were listed before sends PostgreSQL 2 statements where it sent 202.

**The first ask after a working copy is made or changed is not spared.** It reads that copy whole and keeps its counts, so after twenty were made it reads all twenty, with 242 statements, and took the API 938 ms, against 804 before the change. Its trace puts 117.8 ms between one copy's steps and the next's, where the write of each count lies. Every ask after it, until a copy or the library changes, lists from the kept counts.

**Before the change the list of twenty was over the bar, and the place was the one the code names.** The API answered it in a median of 416 ms on the host, and 388 of the median ask's 417.6 ms were the twenty reads with the transactions between them. What the request did once was about 27 ms.

**Reading the library costs nearly as much as reading the catalog, and validation almost nothing.** For each working copy the three statements that read what validation needs of the library take 7.1 to 7.6 ms, against 9 to 16 for the catalog's own row and its six tables of contents, and 0.8 for working out the issues. The library's three statements bring back a handful of rows, so their time is the round trips to the database, and not the rows. Reading the global rules once for the whole list would spare two of those three statements for each copy and no more: the request's time less all twenty readings of the library is 264.9 ms, over the bar before anything is spared. That is why the decision keeps the counts instead.

**The first ask after the copies were made took about twice the median,** 804 ms against 416, and the four untimed asks after it 604, 526, 479, and 516. The copies had just been written, and the API had answered few requests since its start.

**Sending adds about 60 ms on the host,** by the median as sent against the API's own, 478 against 416. The machine's API's own times were not read, so what sending adds there is not known.

**On one machine the same list takes 96 ms.** The machine has twelve cores and no network between the browser tests' stack's containers, where the host has two of another kind and a database in a container beside the API.

## How it was measured

One command, run in `e2e/`, with the site, the account, and the password given from outside, and the local stack when it is given no site:

```sh
npm run list:measure                                       # the browser tests' stack, as visitor
SITE=https://<the site> ACCOUNT=visitor PASSWORD=… npm run list:measure
npm run list:measure -- look                               # the role and the count, nothing made
npm run list:measure -- delete <stamp>                     # the twenty of one run, by their names
```

It runs `e2e/working-copy-list.mjs`, which does all of this and prints the figures of the host's and the machine's "as sent" columns:

1. It signs in once in a browser, headless, as the account, and from then on sends every request with the session's cookies. On the deployed app it got through the sign-in page Amazon Cognito draws in about three seconds, with the same fields as the local login server's. If the page cannot be driven, it opens a browser window for a person to sign in and goes on with that session. It prints no cookie, token, or password.
2. It prints the account's role and how many working copies its list holds. If the list holds any, it makes nothing and stops: the twenty of a run are all its own.
3. It makes twenty working copies, each from the current Approved of Sedan 2027, named `List of twenty <stamp> 01` to `20` with the time to the second, and keeps the ids the API answers with. It prints the version they were made from.
4. It asks for the list once and prints that time by itself, asks four times more untimed, and then thirty times one after another, each timed from sending until the whole answer had arrived. Every answer is checked to hold the twenty it made and no other; one that does not stops the run.
5. It prints from when until when the list was full, in UTC, and the median, 95th percentile, and longest of the thirty.
6. It deletes the twenty it made, by their ids, also when it stopped early, withdrawing one that someone submitted meanwhile, and signs out.

Before the run on the deployed app, `aws sts get-caller-identity` answered; `gh run list --branch main` showed no release under way, and `gh pr list` which pull requests were open; the command was run in `look` mode; and these were written down:

- The commit released: `gh run list --workflow Deploy --branch main --limit 1 --json headSha,conclusion,updatedAt`.
- The API's start time: the newest line `Started VehicleCatalogApplication in … seconds` of the stream `backend` in `/vehicle-catalog/backend`, with `aws logs filter-log-events --log-stream-names backend --filter-pattern '"Started VehicleCatalogApplication in"'` over the last three hours.
- The host's processor credits: `aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance --period 300 --statistics Average` over the last half hour, the newest value taken, for the host found by its tag as `infra/README.md` finds it, with the id read into a variable and printed nowhere.

After it:

- The API's own times: `aws logs filter-log-events --log-group-name /vehicle-catalog/backend --log-stream-names backend --filter-pattern '{ $.message = "GET /api/catalogs *" }'` over the run's minute, every line's time and message kept, and the run's thirty-five told from others by lying between the times the command printed. The minute, 03:59, held 36 such lines: the run's thirty-five and the command's own read before it deleted, at 03:59:18.717, and nobody else's. The minute of the run after the change, 16:14, held 38: the run's thirty-five, the command's own read before it deleted, at 16:14:09.688, and two asks of the empty list while the command signed in and counted it, at 16:14:02.915 and 16:14:03.401, and nobody else's; in the run before, those two fell in 03:58, which was not read. The thirty timed are the 6th to the 35th of the run's.
- The trace: the median line's `traceId`, and after the change the first ask's as well, and the command `infra/README.md` gives for a trace, with a start time ten minutes before the run, listing each span's name, parent, start, length, and `catalog.id`, and nothing else of its record.
- The chart: the widget's definition from `aws cloudwatch get-dashboard`, given a start, an end, a width of 1000, and a height of 320, drawn with `aws cloudwatch get-metric-widget-image --output-format png` and looked at before it was kept. The dashboard's body stays outside the repository.
- The spans the run added, counted by one Logs Insights query over `aws/spans` for 03:58 to 04:01 UTC, and one for 16:13 to 16:16 after the change, by span name, with the group's stored size read first.

The machine's column is of the browser tests' stack, started with `npm run stack:up` and removed with `npm run stack:down` afterwards, with the command given no site: Caddy, the API, PostgreSQL, and the Redis cache in containers, without the largest catalog, as `visitor`, whose list is empty there after a start.

## What it does not show

- **A fault nobody knew of.** The trace has the three steps of each read and the request's span, and all of the time but about 27 ms lies in them or between them.
- **The statements inside a read.** No step times a single statement, so which of the seven statements of `catalog.read` takes its 9 to 16 ms, or how the three of `catalog.library` share their 7, is not known.
- **What lies before the first step,** 21.8 ms, and in each gap between two copies, 1.1 to 1.6 ms. No step covers the session's reading, the caller's lookup, the list's own statement, or a transaction's beginning and end.
- **Many people at once,** and what the list does to other requests while it holds a thread and a connection for half a second.
- **A list of twenty of the largest catalog there can be.**
- **A warm API.** The one measured had started 1 minute 55 seconds before the run before the change, and 2 minutes 57 seconds before the run after it.
- **Why the first ask after the change took longer than the first ask before it,** 938 ms against 804. The first ask before the change was not traced, so its steps cannot be set beside those of the one after, whose reads took 13.0 to 70.8 ms each where the median ask's before took 9.0 to 16.0.
- **The machine after the change.** It was not run again.
- **The machine's API's own times.** Its log was not read; its column is as sent alone.

## When, and on what

- **The host:** 2026-10-14, the run from 03:58:54.457 to 03:59:20.897 UTC, with `visitor`'s list full from 03:59:00.564 until 03:59:18.269, which is 17.7 seconds, and empty before and afterwards, with the role author. The commit released was `4b57a6c`, whose Deploy ended at 03:57:47; the API had logged `Started VehicleCatalogApplication in 19.499 seconds` at 03:57:03.668; the host's `CPUCreditBalance` was 576.0, the newest five-minute average before the run, at 03:53. The copies were made from Sedan 2027 v1 and deleted by the run, twenty of twenty. No release ran during it, and the run was made once.
- **The host, after the change:** 2026-10-14, the run from 16:13:59.277 to 16:14:12.040 UTC, with `visitor`'s list full from 16:14:06.246 until 16:14:09.636, which is 3.4 seconds, and empty before and afterwards, with the role author. The commit released was `3546e27`, the change that keeps the counts, whose Deploy ended at 16:11:46; the API had logged `Started VehicleCatalogApplication in 21.347 seconds` at 16:11:02.393; the host's `CPUCreditBalance` was 575.6, the newest five-minute average before the run, at 16:08. The copies were made from Sedan 2027 v1, as before, no approval having been logged since the demo reset of 03:00, and deleted by the run, twenty of twenty. No release ran during it, and the run was made once.
- **The machine:** 2026-10-14, from 03:52:26 UTC, with the list full from 03:52:27.186 until 03:52:30.909. AMD Ryzen 9 5900X, 32 GB of memory, Linux 7.2, Docker keeping its volumes on btrfs, with containers of other projects running beside the stack. Node 24.21.0 sent the requests, and Chromium, driven by Playwright 1.63, signed in. The earlier run on the same machine was from 01:50:16 UTC.
