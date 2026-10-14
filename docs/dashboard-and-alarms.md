# The dashboard and its alarms

What the CloudWatch dashboard `vehicle-catalog` shows, in what order, and how each of its charts was seen drawn. The page is made by `infra/telemetry.tf`, and the tests in `infra/tests/telemetry.tftest.hcl` hold its layout, its order, its axes, its labels, its limits, and its row of alarms. What each alarm says and whom it tells is not written here yet: the alarms tell no one today, and a later change gives them words and an address.

## The order of the page

The page reads from top to bottom in the order a fault is looked for. Each group below the first stands under a heading of its own.

1. **The alarms**: one row with the state of every alarm the Terraform makes, three today: `vehicle-catalog-backend-health`, `vehicle-catalog-jobs-failed`, and `vehicle-catalog-jobs-waiting`.
2. **Is the API up**: whether the API is ready, and the starts of the API and of the worker by the hour, counted from the log.
3. **Requests**: requests a minute by outcome, and how long a request that succeeds takes.
4. **Catalogs**: how long saving an edit and copying a catalog take; how long reading a catalog and working out its issues takes; catalogs approved, rejected, and updated from Approved; submits refused by reason.
5. **Jobs and queues**: jobs a minute by type and outcome; how long a job takes; messages that wait on the jobs' queue and in the dead-letter queue; how long the oldest message has waited, against the 600 seconds at which the alarm goes off.
6. **The language model**: requests a minute; how long a request takes; tokens in and out; tool calls and refusals; what the day has cost, against the day's allowance.
7. **The cache**: reads of an Approved version's contents by how the cache answered, and the share of those reads the cache answered.
8. **The host**: the share of its memory and disk in use; its processor time and the credits it has saved up; the API's heap, against the most it may grow to.

Every chart says in its title what it measures and, where the figures have one, in what unit. Every chart of metrics names its left axis after its unit, and the host's processor chart its right one too, in place of what CloudWatch would say there. Every line that is drawn has a label of its own. A line is drawn at each limit, read from where the limit is set: the alarm's threshold for the oldest message, the API's heap in `deploy/compose.yaml`, and `app.ai.allowance.daily` in `backend/src/main/resources/application.properties`.

## The charts

Each chart of metrics was drawn by command twice, before the change and after it, over the same hour: an hour within the fifteen days before 2026-10-14 in which the chart had something to draw, found by asking CloudWatch for its metrics by the hour. The pictures are `docs/dashboard/before/` and `docs/dashboard/after/`, a pair under one name, 1000 by 320. The starts chart cannot be drawn by command ([below](#the-starts-chart)).

"Values" means the line drew figures in that hour, "zero" that it lay at zero throughout, and "not there" that the line was in the legend or in the chart's definition and drew nothing, because its metric had not been sent in that hour or at all. The legend reads the same before and after unless the table says otherwise. Where a chart's label is followed by the metric's name, as in "ready health", that is how CloudWatch writes the legend of a line found by a search: the label, and then the metric's name and, for a percentile, its statistic.

| Chart | Title after the change | Unit | Hour, UTC | Axis before, after | Legend | Lines |
|---|---|---|---|---|---|---|
| 1 | Requests a minute, by outcome | requests a minute | 2026-10-13 15:00 | No unit, requests a minute | CLIENT_ERROR, REDIRECTION, SUCCESS | all three values, 15:24 to 15:26 and 15:52. SERVER_ERROR not there: none in the fifteen days |
| 2 | How long a request that succeeds takes, in milliseconds | milliseconds | 2026-10-13 21:00 | No unit, milliseconds | half of them, within http.server.requests p50; 99 in 100, within http.server.requests p99 | both values, one point each at 21:04: 1.84 and 1.91 |
| 3 | Whether the API is ready (1) or not (0) | 1 or 0 | 2026-10-13 23:00 | No unit, 1 ready, 0 not | ready health | values, 1 throughout |
| 4 | How long saving an edit and copying a catalog take, on average, in milliseconds | milliseconds | 2026-10-13 03:00 | No unit, milliseconds | saving an edit catalog.edit; copying a catalog catalog.copy | both values, one point each at 03:01: about 17 and 57 |
| 5 | How long reading a catalog and working out its issues takes, on average, in milliseconds | milliseconds | 2026-10-13 15:00 | No unit, milliseconds | reading a catalog with its issues catalog.validate | values: about 46 at 15:25, 17 at 15:26, 48 at 15:52 |
| 6 | Memory the API's heap uses, in MiB, against the most it may grow to | MiB | 2026-10-13 23:00 | No unit, MiB | heap jvm.heap.used | values: 60 M to 98 M bytes before, 57 to 94 MiB after, falling to 52 at 23:29; the line at 256 after |
| 7 | Share of the host's memory and disk in use, in percent | percent | 2026-10-13 23:00 | Percent, percent | before: mem_used_percent; xfs / disk_used_percent. After: memory in use; disk in use | both values: memory about 65, disk about 16. The axis runs 0 to 100 after |
| 8 | The host's processor time in use, in percent, and the credits it has saved up | percent; credits | 2026-10-13 23:00 | Percent and Count, percent and credits | before: CPUUtilization; CPUCreditBalance. After: processor time in use; credits saved up | both values: 3.1 to 3.4 percent, 576 credits. Neither legend shows the instance's id |
| 9 | Catalogs approved, rejected, and updated from Approved, a minute | catalogs a minute | 2026-10-11 03:00 | No unit, catalogs a minute | approved catalog.approved; rejected catalog.rejected; updated from Approved catalog.merged | approved values, 1 at 03:59 and zero before it; rejected zero; updated from Approved zero |
| 10 | Submits refused a minute, by reason | submits a minute | 2026-10-13 23:00 | No unit, submits a minute | HAS_ERRORS, STALE, VEHICLE_LINE_INACTIVE | all three zero throughout: no submit was refused in the fifteen days |
| 11 | Jobs a minute, by type and outcome | jobs a minute | 2026-10-11 03:00 | No unit, jobs a minute | AFTER_APPROVAL SUCCESS, EXPORT SUCCESS, PROCESS_DOCUMENT SUCCESS, RECHECK_APPROVED SUCCESS, SUMMARISE_SUBMISSION SUCCESS | RECHECK_APPROVED 7 and PROCESS_DOCUMENT 3 at 03:01, AFTER_APPROVAL 1 and SUMMARISE_SUBMISSION 1 at 03:59; EXPORT not there in the hour. No FAILURE line: no job failed in the fifteen days |
| 12 | How long a job takes on average, by type and outcome, in milliseconds | milliseconds | 2026-10-11 03:00 | No unit, milliseconds | as chart 11 | SUMMARISE_SUBMISSION 4,316 at 03:59, PROCESS_DOCUMENT about 1,000 and RECHECK_APPROVED about 120 at 03:01, AFTER_APPROVAL about 117 at 03:59; EXPORT not there in the hour |
| 13 | Messages that wait on the jobs' queue, and in the dead-letter queue | messages | 2026-10-13 03:00 | Count, messages | waiting; failed for good | waiting values, 5 at 03:00 and 0 after; failed for good zero |
| 14 | How long the oldest message on the jobs' queue has waited, in seconds, against when the alarm goes off | seconds | 2026-10-13 03:00 | Seconds, seconds | oldest message | values, 3 at 03:00 and 0 after; the line at 600 after, with the values low against it |
| 15 | Requests to the language model a minute, by what they were for and how they ended | requests a minute | 2026-10-10 16:00 | No unit, requests a minute | ANALYST SUCCESS, DOCUMENT_EMBEDDING SUCCESS, QUESTION_EMBEDDING SUCCESS, RULE_SUGGESTION SUCCESS, SUBMISSION_SUMMARY SUCCESS | ANALYST 2 at 16:23 and 3 a minute from 16:49 to 16:58, DOCUMENT_EMBEDDING 3 at 16:22, QUESTION_EMBEDDING 3 at 16:23; RULE_SUGGESTION and SUBMISSION_SUMMARY not there in the hour. No FAILURE line: no request failed in the fifteen days |
| 16 | How long a request to the language model takes on average, by what it was for and how it ended, in milliseconds | milliseconds | 2026-10-10 16:00 | No unit, milliseconds | as chart 15 | ANALYST 2,377 at 16:23 and from about 1,800 at 16:49 down to about 1,000 at 16:58, DOCUMENT_EMBEDDING about 950 at 16:22, QUESTION_EMBEDDING about 725 at 16:23; the other two not there in the hour |
| 17 | Tokens the language model was sent (in) and answered with (out), a minute | tokens a minute | 2026-10-13 03:00 | No unit, tokens a minute | in; out | both values at 03:01, about 1,100 and 80, and zero in the other minutes |
| 18 | Tool calls of the analyst, and requests to the language model that were refused, a minute | tool calls and refusals a minute | 2026-10-10 16:00 | No unit, tool calls and refusals a minute | tool calls ai.tool.calls | tool calls values: 1 at 16:23, 2 at 16:49, 2 at 16:58, zero between. The refused line is not there: `ai.refused` was not sent in the fifteen days |
| 19 | What asking the language model has cost today, in US dollars, against the day's allowance | US dollars | 2026-10-13 23:00 | No unit, US dollars | reserved and spent ai.spent | values, close to 0 throughout; the line at 1 before and after |
| 20 | Reads of an Approved version's contents a minute, by how the cache answered | reads a minute | 2026-10-11 04:00 | No unit, reads a minute | fallback, hit, miss | all three values: the hour of `docs/cache-stopped-on-the-host.png` |
| 21 | Share of the reads of Approved versions' contents that the cache answered, in percent | percent | 2026-10-11 04:00 | No unit, percent | hits | values in the eleven minutes of the hour that had reads |
| 22 | Starts of the API and of the worker by the hour, those of each release among them | starts | – | – | – | not drawn by command: [below](#the-starts-chart) |

The time charts are in milliseconds. Chart 4's two points, about 17 ms for an edit and 57 for a copy, and chart 5's, 17 to 48 ms for reading a catalog with its issues, are of the order of the API's own times on the host: `docs/cache-measurements.md` has a seeded version opened in 16 ms at the median there, and `docs/largest-catalog-measurements.md` a save of one cell of the largest catalog in 57 ms. Chart 2's 1.84 ms is a request that succeeds in a minute whose requests were the health checks. Chart 12's 4,316 for summarising a submission and chart 16's 2,377 for a request of the analyst's are seconds of waiting on the model, which is what such a request takes.

The app was not used as `visitor` for any chart: each of the 21 had a line with values in some hour of the fifteen days.

## The figures before and after

| | Before | After |
|---|---|---|
| Axes that say "No unit" | 17 of 21 | 0 |
| Lines without a label of their own | 4 | 0 |
| Charts with a picture in the repository | 1 of 22 | 21 of 22 |
| Charts never seen drawn | 9, 10, and 22 | 22 |

Before the change the axis said "No unit" on every chart that finds its metrics by name, seventeen of them. The four others said what AWS or the agent reports with the metric: "Percent" on chart 7, "Percent" and "Count" on chart 8, "Count" on chart 13, and "Seconds" on chart 14. An axis that is given a label alone says the label and then "No unit" beside it, in one word: "requests a minute•No unit", tried once on chart 1. So every axis has its label and `showUnits` set to false.

## How a picture is made

A picture is drawn by `aws cloudwatch get-metric-widget-image` from the chart's own definition, the `properties` of its widget as the dashboard's body has them, with a start, an end, a width of 1000, and a height of 320 added. It holds the title, the axes, the lines, and the legend, and no console around them. For the pictures "before" the body came from the dashboard as it was:

```sh
aws cloudwatch get-dashboard --dashboard-name vehicle-catalog \
  --query DashboardBody --output text > body.json
```

For the pictures "after" the body came from a saved plan of the change, before it was applied. `terraform show -json` prints every value of a plan in plain text, the secrets the state holds among them, so its output goes straight into a filter that keeps the dashboard's body and nothing else, and is never printed or kept whole:

```sh
terraform plan -out=branch.plan
terraform show -json branch.plan | jq '
  .resource_changes[] | select(.address == "aws_cloudwatch_dashboard.backend")
  | .change.after.dashboard_body | fromjson' > body.json
```

The hour was found with `aws cloudwatch get-metric-data`, asked for the chart's lines with a period of 3600 over the fifteen days, the search's own period of 60 set to 3600 for the asking; the hour with the most lines that had a value other than zero was taken, and the latest of them when several had as many. The chart was then drawn over that hour, by its title here:

```sh
jq '.widgets[] | select(.properties.title == "Requests a minute, by outcome") | .properties
    + {start: "2026-10-13T15:00:00Z", end: "2026-10-13T16:00:00Z", width: 1000, height: 320}' \
  body.json > widget.json
aws cloudwatch get-metric-widget-image --metric-widget file://widget.json --output-format png \
  --query MetricWidgetImage --output text | base64 -d > 01-requests-a-minute.png
```

The body holds the host's instance id, in the two lines of chart 8, so the body and the saved plan stay outside the repository and are removed once the apply has been compared with the body the pictures "after" were drawn from.

AWS's price list for CloudWatch in us-east-1, read on 2026-10-14 from `https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonCloudWatch/current/us-east-1/index.csv` as published on 2026-10-07, has a drawing at $0.02 per 1,000 metrics requested with `GetMetricWidgetImage`, and the asking by the hour at $0.01 per 1,000 metrics requested with `GetMetricData`. The 66 drawings and 42 askings for these pictures asked for fewer than a thousand metrics in all, so they cost under three cents.

## How a picture is made safe to publish

Every picture is looked at before it is committed, for the account's number, an ARN, an instance's id, an IP address, the id of the distribution or of the user pool, an email address, a sign-in's name, and a session's value. A picture that shows one of these is not committed, and the record says in words what it showed. A picture drawn by command from a chart's definition names none of these unless a title or a label does: chart 8's two lines are asked for by the instance's id, and its legend showed the metrics' names before the change and the lines' labels after it, and the id in neither. The 42 pictures in `docs/dashboard/` were each looked at and show none of the things listed.

A screenshot of the console is cropped first, to the dashboard's own area, without the console's bar and menu and the browser's address line, and the record says what the crop took away, as the maintainer tells it, and that the picture kept names none of these.

## The starts chart

The chart of starts is a query of the log, and `GetMetricWidgetImage` draws charts of metrics alone. Asked once by command, on 2026-10-14 at 00:47 UTC, with the chart's `properties` and a start, an end, a width, and a height, AWS answered:

```
An error occurred (ValidationError) when calling the GetMetricWidgetImage operation: Should have required property 'metrics'
The "query" property is not expected, will be ignored
```

So the chart has no picture by command, and only the page in the console shows whether it draws, as two lines, one, or none, and how it draws the hours without a start, for which its query has no row. That look is the maintainer's, after the apply, with the page showing the last week: the alarms' row, the first heading, and the row with the starts chart, in one cropped screenshot kept as `docs/dashboard/page.png`, beside what its query counts for an hour the screenshot shows. It has not been taken yet.
