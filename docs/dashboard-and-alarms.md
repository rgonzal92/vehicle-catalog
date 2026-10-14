# The dashboard and its alarms

What the CloudWatch dashboard `vehicle-catalog` shows, in what order, and how each of its charts was seen drawn. The page is made by `infra/telemetry.tf`, and the tests in `infra/tests/telemetry.tftest.hcl` hold its layout, its order, its axes, its labels, its limits, and its row of alarms. [The alarms](#the-alarms) below has what each alarm notices, what to do, how it clears, whom it tells, and how that was tried.

## The order of the page

The page reads from top to bottom in the order a fault is looked for. Each group below the first stands under a heading of its own.

1. **The alarms**: one row with the state of every alarm the Terraform makes, six today: `vehicle-catalog-backend-health`, `vehicle-catalog-worker-health`, `vehicle-catalog-jobs-failed`, `vehicle-catalog-jobs-waiting`, `vehicle-catalog-backup-missing`, and `vehicle-catalog-host-disk`.
2. **Are the API and the worker up**: whether the API is ready and whether the worker is, and the starts of the API and of the worker by the hour, counted from the log.
3. **Requests**: requests a minute by outcome, and how long a request that succeeds takes.
4. **Catalogs**: how long saving an edit and copying a catalog take; how long reading a catalog and working out its issues takes; catalogs approved, rejected, and updated from Approved; submits refused by reason.
5. **Jobs and queues**: jobs a minute by type and outcome; how long a job takes; messages that wait on the jobs' queue and in the dead-letter queue; how long the oldest message has waited, against the 600 seconds at which the alarm goes off.
6. **The language model**: requests a minute; how long a request takes; tokens in and out; tool calls and refusals; what the day has cost, against the day's allowance.
7. **The cache**: reads of an Approved version's contents by how the cache answered, and the share of those reads the cache answered.
8. **The host**: the share of its memory and disk in use; its processor time and the credits it has saved up; the API's heap, against the most it may grow to; the size of each backup of the database.

Every chart says in its title what it measures and, where the figures have one, in what unit. Every chart of metrics names its left axis after its unit, and the host's processor chart its right one too, in place of what CloudWatch would say there. Every line that is drawn has a label of its own. A line is drawn at each limit, read from where the limit is set: the alarm's threshold for the oldest message, the API's heap in `deploy/compose.yaml`, `app.ai.allowance.daily` in `backend/src/main/resources/application.properties`, and the disk alarm's threshold on the chart of the host's memory and disk.

## The charts

Each chart of metrics that the page had on 2026-10-14 at 00:47 UTC, 21 of them, was drawn by command twice, before the change and after it, over the same hour: an hour within the fifteen days before 2026-10-14 in which the chart had something to draw, found by asking CloudWatch for its metrics by the hour. The pictures are `docs/dashboard/before/` and `docs/dashboard/after/`, a pair under one name, 1000 by 320. The starts chart cannot be drawn by command ([below](#the-starts-chart)).

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

Three charts have changed since those pairs were drawn. Chart 7, the host's memory and disk, has a dashed line at 80 percent labelled "the disk's alarm goes off (80)", where `vehicle-catalog-host-disk` goes off; drawn once more by command on 2026-10-14 after the alarm's apply, over the three hours to 05:21 UTC, 1000 by 400, it is `docs/dashboard/07-memory-and-disk-with-the-alarms-line.png`: the disk's line runs at about 15 and the memory's between 60 and 70, with two dips to about 40 at the releases of 03:56 and 04:30 UTC. Chart 3 is now titled "Whether the API and the worker are ready (1) or not (0)" and has a second line, the worker's `worker.health`, under the label "worker ready"; and a chart of the size of each backup of the database, in bytes, stands under The host beside the API's heap, drawn by the hour over the last seven days whatever the page shows, which found its metric by name and so draws its legend as "backup". Neither has a pair; the readiness chart was drawn once more by command on 2026-10-14, after the release that brought the worker's line, over the three hours to 04:41 UTC, 1000 by 400: `docs/dashboard/03-api-and-worker-ready.png`. Its legend reads "API ready health" and "worker ready worker.health", the worker's line runs at 1 from 04:32 UTC, the first full minute after that release, and the API's line alone covers the hours before, so the search for `health` in quotes did not also find `worker.health`. The API's line touches 0 three times in the picture, at about 03:40, 03:56, and 04:30 UTC, which are the three releases of that night, and has a gap of one minute at 04:16 UTC, when an apply gave the agent its file anew and started it again. The backups chart, drawn the same way, had one point, 138,183 bytes, and its axis spanned two bytes around it; it is not kept, since it will look different once a second size arrives.

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

## The alarms

The Terraform makes six alarms and no other: `vehicle-catalog-backend-health`,
`vehicle-catalog-worker-health`, and `vehicle-catalog-host-disk` in `infra/telemetry.tf`,
`vehicle-catalog-jobs-failed` and `vehicle-catalog-jobs-waiting` in `infra/jobs.tf`, and
`vehicle-catalog-backup-missing` in `infra/backup.tf`. Each tells the SNS topic
`vehicle-catalog-alarms` when it goes off and when it clears, and nothing for want of data, and the
topic has one subscription: by email, to the address the budget alert goes to. The tests in
`infra/tests/jobs.tftest.hcl`, `infra/tests/telemetry.tftest.hcl`, and
`infra/tests/backup.tftest.hcl` hold each alarm's definition and each to the topic, the topic to
that one subscription, and the pipeline's plan role to reading the topic and its subscription and
to nothing else of SNS. `infra/README.md` has, under each alarm's name, what to do and the
commands. The first three were given their actions on 2026-10-14 ([Before](#before), [After](#after),
[The trial](#the-trial)); the other three were made later that day
([The worker, the backup, and the disk](#the-worker-the-backup-and-the-disk)).

### Before

Read on 2026-10-14 at 03:20 UTC with `aws cloudwatch describe-alarms` and `describe-alarm-history`,
before anything was changed and as far back as AWS keeps the history, which here reaches back to
each alarm's making. All three were `OK`, none had an action of any kind, and none had ever told
anyone.

- `vehicle-catalog-backend-health`, made on 2026-10-08 at 16:33 UTC, `OK` since 17:33:43 UTC that
  day. It went off twice, both times on the day it was made, and not since:
  - From 16:34:59 to 17:10:43 UTC, 35 minutes and 44 seconds, going from want of data straight to
    `ALARM` with the reason "no datapoints were received for 3 periods": the alarm asked for its
    metric under a name nothing arrived by. Pull request #63 found the metric; its apply changed
    the alarm at 17:09:38 UTC, and a minute later three minutes of `1.0` cleared it.
  - From 17:29:43 to 17:33:43 UTC, 4 minutes: the trial of that day, in which the API was stopped.
    A last reading of `0.0` at 17:21 UTC and two missing minutes set it off, and a reading of
    `1.0` at 17:31 UTC cleared it.
  - No change of state for any release from 2026-10-08 on, nor for any demo reset.
- `vehicle-catalog-jobs-failed`, made on 2026-10-09 at 18:13 UTC, `OK` since 18:14:43 UTC that day,
  when it went from want of data to `OK`. It has never gone off.
- `vehicle-catalog-jobs-waiting`, made on 2026-10-09 at 19:08 UTC, `OK` since 19:09:18 UTC that
  day, the same way. It has never gone off.

So no alarm has gone off for something that is no fault, a release among them, and the emails to
expect are those of a fault and of the trial below. Each alarm's one change from want of data came
in its first minutes. Such a change, to `OK` or to `ALARM`, runs the action of the state it goes
to, so an alarm made anew emails once as it settles; a change into want of data runs nothing,
since no alarm has an action for it.

### After

| Alarm | What it notices | How soon | What to do first | How it clears |
|---|---|---|---|---|
| `vehicle-catalog-backend-health` | The API has not said it is ready. A minute in which nothing arrived counts as not ready, so a CloudWatch agent stopped on the host looks the same. The worker's readiness is `worker.health`, which `vehicle-catalog-worker-health` watches, so this one says nothing of the worker. | About eight minutes after the API stopped, seen once on 2026-10-08. Three minutes for an API that runs and says it is not ready, by its settings, not seen. The agent stopped, by its settings, not tried. | Ask `/api/health/readiness` through the site: 200 means the API is up and its reports are not arriving, unless the API has just come back and the email of its clearing follows; anything else means the API. | By itself some minutes after the API is ready again, three and a half in the one trial. |
| `vehicle-catalog-jobs-failed` | A job has failed for good: its work failed in each of three tries, and its message is in the dead-letter queue. | Within a minute or two of the third failed try, by its settings. | The Jobs page says what the last try said; Retry there once the cause is put right. | At Retry, within a minute or two, whether or not the job is then done: the email of its clearing does not say a job was done, the Jobs page does. A retried job that fails three times again sets it off again about six minutes after the Retry. By itself after the demo reset at 03:00 UTC, which takes the job away. After fourteen days, when the queue lets go of a message that nothing took out. |
| `vehicle-catalog-jobs-waiting` | A message has been on the jobs' queue for more than ten minutes: a worker that runs and is stuck, one that stopped with a message still on the queue, or one that works and has more jobs than it gets through. A worker that stopped with the queue empty does not set it off; `vehicle-catalog-worker-health` notices it. | Within a minute of the message's eleventh minute, by its settings. Not seen to go off for its cause. | Whether the worker runs and what its log says, then how many messages wait on the queue. | By itself once no message on the queue is that old. |

How soon each goes off follows from its settings but where the table says it was seen: the health
alarm's eight minutes and three and a half are the one trial of 2026-10-08, in pull request #63.

### The trial

Made on 2026-10-14 from 03:32:57 to 03:37:33 UTC, with all three alarms `OK`, no Deploy running,
and the subscription still unconfirmed (The trial after the click, below). Each alarm was set to
`ALARM` by hand once, with the reason "Set by hand to see the email. Nothing is wrong.", and asked
for every 30 seconds. Each returned to `OK` by itself within the five minutes, so none was set
back by hand. The times are those its history gives, in UTC:

| Alarm | Set to `ALARM` by hand | Told the topic | Back to `OK` by itself | Told the topic | On for |
|---|---|---|---|---|---|
| `vehicle-catalog-backend-health` | 03:32:58 | 03:32:58, succeeded | 03:33:48 | 03:33:48, succeeded | 51 s |
| `vehicle-catalog-jobs-failed` | 03:33:59 | 03:33:59, succeeded | 03:35:04 | 03:35:04, succeeded | 65 s |
| `vehicle-catalog-jobs-waiting` | 03:35:31 | 03:35:31, succeeded | 03:37:04 | 03:37:04, succeeded | 93 s |

Over those minutes each history has the change from `OK` to `ALARM`, an item of the type `Action`
that says "Successfully executed action" and names the topic, the change from `ALARM` to `OK`, and
a second such item. So the topic, with no access policy of its own, takes this account's alarms,
and an alarm that returns to its real state by itself runs its clearing action. Before them each
history has a `ConfigurationUpdate` at 03:32:21 UTC, the apply that gave the alarms their actions,
and no change of state for it. "Often within seconds", which AWS's page gives for the return, was
51 to 93 seconds here.

The topic's counts from 03:31 to 03:41 UTC, asked for by the minute and summed:
`NumberOfMessagesPublished` 6, one at 03:32, two at 03:33, two at 03:35, and one at 03:37;
`NumberOfNotificationsDelivered` 1, in the minute 03:32 and in no other; and
`NumberOfNotificationsFailed` one datapoint of 0 at 03:32 and none in any other minute. Six
published is the six emails the alarms asked for. One delivered and not six is the subscription:
the address had not confirmed it, and SNS sends nothing to an address that has not. The minute
03:32 holds both the apply, in which SNS sent the address its email to confirm (03:32:17 to
03:32:22), and the health alarm's first message (03:32:58); which of the two that one delivery
counts, SNS's page does not say.

The pipeline's `plan` check, run again on the pull request at 03:33 UTC after the apply, read the
topic and the subscription as the plan role, with the statement that names the topic's ARN alone,
and said "No changes. Your infrastructure matches the configuration."

### The worker, the backup, and the disk

Three faults that set no alarm off before 2026-10-14 each set one off now: a worker that is not
ready or says nothing, a night without a backup, and a disk that is nearly full. For that the
worker reports `worker.health`, 1 while it is ready and 0 while it is not, and `deploy/backup.sh`
reports each backup's size in bytes as `backup.size` once the bucket has it; the disk's share in
use, `disk_used_percent`, the agent reported already. Each alarm tells the topic when it goes off
and when it clears, and nothing for want of data, as the three before it do.

| Alarm | What it notices | How soon, by its settings | What to do first | How it clears |
|---|---|---|---|---|
| `vehicle-catalog-worker-health` | The worker has not said it is ready: `SELECT MIN("worker.health")` in the app's namespace, by the minute, below 1 for three minutes running, with a minute in which nothing arrived counted as below. A CloudWatch agent stopped on the host looks the same and sets off the API's alarm too. | Three minutes for a worker that runs and says it is not ready. Later for one that stopped: about eight minutes, if it goes as the API's alarm went on 2026-10-08. Neither seen. | Whether the worker runs and what its log says, and the chart of starts. Jobs wait in the database meanwhile. | By itself some minutes after the worker is ready again. |
| `vehicle-catalog-backup-missing` | No hour of the last 26 has a backup's size in it: `backup.size` at its `Maximum` by the hour, each hour without a value filled with 0 by `FILL`, all 26 at or below 0. | About 26 hours after the last size: at about 04:30 UTC after a night without a backup, later by the time the dump took. A backup an hour late sets nothing off. Not seen. | On the host, the unit's journal and its timer: a journal that says the backup was made and its size was not reported means the backup is there. | By itself once a backup has reported its size, one started by hand among them; how soon after that was not seen. |
| `vehicle-catalog-host-disk` | The host's one disk, `disk_used_percent` with `path` `/` and `fstype` `xfs`, above 80 percent on average for five minutes of five. A minute without a value is no fault to it. | Within a minute of the fifth minute over the line. Not seen. | On the host, what takes the room: `df -h`, `docker system df`, the images no container runs. | By itself within about a minute of the share being below the line. |

Why 26 hours and `FILL`: a backup is due every 24 hours, so 26 lets one run an hour late and
still be found, and the 26 hours move with every look the alarm takes, so the alarm goes off about
26 hours after the last size arrived. Whether `FILL` gives a zero for an hour with no value when
the whole window has none was not on AWS's page, so it was asked by command on 2026-10-14 with
`aws cloudwatch get-metric-data`, `FILL(m1, 0)` over `backup.size` at its `Maximum` by the hour:
over the 26 hours ending with the backup started by hand that day (03:00 UTC on 2026-10-13 to
05:00 UTC on 2026-10-14) it gave 26 values, 25 of them 0 and the hour 04:00 UTC 138,183, and over
the 26 before those (01:00 UTC on 2026-10-12 to 03:00 UTC on 2026-10-13), in which the metric
itself had no value at all, it gave 26 values, all 0. So the alarm is the one that goes off when
all 26 are zero, and not the one that counts a missing hour as a fault; it counts a missing hour as
a fault all the same, in case one is ever missing.

Why 80 percent: read on 2026-10-14 at 04:03 UTC, before anything was changed, the disk was 15.02
percent full, its highest 15.54 percent in the hour 03:00 UTC that day since the agent first
reported on 2026-10-08, when it was 12.04; the agent reports it once a minute. Of the 4.5 GiB in
use on the 29.9 GiB disk, Docker's images took 1.9 GB, 1.1 GB of it in images no container runs,
the database's volume 69 MiB, and the logs of Caddy, the database, and the cache, which Docker
keeps as `json-file` and nothing limits, 108 KiB together. A backup is made in `/tmp`, a tmpfs in
memory, so it is never on the disk. The line is 80 percent, which leaves 6 GiB and is 65 points
above that day's share; it would have been lower had the disk been more than 65 percent full.

#### The figures before and after the three alarms

| | Before | After |
|---|---|---|
| Of a stopped worker, a night without a backup, and a disk that fills, how many set an alarm off | 0 | 3 |
| Alarms | 3 | 6 |
| The disk's share in use, read on 2026-10-14 at 04:03 UTC | 15.02 percent, 15.54 at its highest | the same; the line at 80 percent |
| Backups with a size on record | 0 | 1: the one started by hand on 2026-10-14 at 04:18 UTC, 138,183 bytes, equal to what the bucket lists for it. The first night's follows the next 02:30 UTC. |
| Metrics that are paid for | up to thirty-seven | up to thirty-nine |

What the three alarms and the two metrics cost a month, by AWS's price list for CloudWatch in
us-east-1 read on 2026-10-14 at 02:11 UTC (published 2026-10-07, rates effective 2026-10-01) and
its public price page read the same minute: `worker.health` is a custom metric at $0.30 a month;
`backup.size`, sent in one hour of each night, is charged for those hours alone, "prorated by the
hour", about $0.0125; the worker's alarm asks by a query, $0.10 for each metric it analyses, or
$0.20 while an update of Spring Boot has the metric under two versions; the backup's and the disk's
alarms list their metric, and with the two on the queues make four of the ten such alarms the
account has free, so nothing, or $0.10 if an alarm that reads its metric through `FILL` is not
counted among them. In all about $0.41 to $0.61 a month. The 30 or so `PutMetricData` requests a
month are within the free million.

#### What was not tried

No worker was stopped, no night's backup was left out, and the disk was not filled: the worker's
work would have waited, the database is on the disk, and a backup left out is a day's work at risk.
So none of the three was seen to go off for its cause, and how soon each goes off is from its
settings, as the table says. Each was set to `ALARM` by hand once after its apply, as the three
before it were ([The trial of the three](#the-trial-of-the-three)). Whether a release of the backend
leaves the worker silent for longer than the alarm waits is read from the worker's alarm's history
over the release that follows.

#### The trial of the three

Applied on 2026-10-14 from 05:09:53 to 05:09:58 UTC, from a plan that added the three alarms and
changed the waiting alarm's description and the dashboard in place: 3 added, 2 changed, 0
destroyed. Each alarm left want of data for `OK` by itself and told the topic as it did, by its
history: the disk's at 05:10:36 (39 seconds after it was made, its reason naming 15.027 percent
for 05:09 UTC, against 15.026 on the chart's minute before), the worker's at 05:11:22 (85 seconds),
and the backup's at 05:32:52 (22 minutes 56 seconds; its reason was "Unchecked: Initial alarm
creation" until then, and named the one hour with 138,183 after). So CloudWatch looks at an alarm
whose period is an hour seldom, where it looked at the two by the minute within two minutes.

Then, from 05:33:34 to 05:40:43 UTC, with all six alarms `OK` and no Deploy running, each of the
three was set to `ALARM` by hand once, with the reason "Set by hand to see the email. Nothing is
wrong.", and asked for every 30 seconds. The times are those its history gives, in UTC:

| Alarm | Set to `ALARM` by hand | Told the topic | Back to `OK` | Told the topic | On for |
|---|---|---|---|---|---|
| `vehicle-catalog-worker-health` | 05:33:35 | 05:33:35, succeeded | 05:34:57, by itself | 05:34:57, succeeded | 82 s |
| `vehicle-catalog-backup-missing` | 05:35:07 | 05:35:07, succeeded | 05:40:12, by hand | 05:40:12, succeeded | 305 s |
| `vehicle-catalog-host-disk` | 05:40:13 | 05:40:13, succeeded | 05:40:41, by itself | 05:40:41, succeeded | 28 s |

The worker's and the disk's returned by themselves, as the three before them had. The backup's was
still `ALARM` after five minutes, so it was set back to `OK` the same way, which ran its clearing
action; that it is an alarm whose period is an hour, and that CloudWatch took 23 minutes to look at
it the first time, is the reason that fits, and so its return by itself, to its real state after a
trial, was not seen. Over those minutes each history has the change to `ALARM`, an item of the type
`Action` that says "Successfully executed action" and names the topic, the change back, and a
second such item.

The topic's counts from 05:08 to 05:42 UTC, asked for by the minute: `NumberOfMessagesPublished`
one each at 05:10, 05:11, and 05:32, the three changes to `OK`, and then one each at 05:33, 05:34,
and 05:35 and three at 05:40, the six of the trial, nine in all; `NumberOfNotificationsDelivered`
no datapoint in any minute; `NumberOfNotificationsFailed` none either. Nine published is the nine
emails the alarms asked for; none delivered is the subscription, still unconfirmed, as in the trial
above.

### The trial after the click

The click on "Confirm subscription" came on 2026-10-14. The maintainer's first click did not
confirm the address: at 17:01:12 UTC the subscription was still pending, the topic counting no
confirmed one and one pending. The email to confirm was sent again at 17:01:30 UTC with
`aws sns subscribe` to the same address, which is the same subscription: the topic still has one,
and nothing in the Terraform changed. He clicked it, and by 17:02:08 UTC
`aws sns get-subscription-attributes` with `--query Attributes.PendingConfirmation` answered
`false`.

Then, from 17:02:32 to 17:07:33 UTC, with all six alarms `OK` and no Deploy running, the first
three were set off again, as in [The trial](#the-trial): one at a time, set to `ALARM` by hand with
the reason "Set by hand to see the email. Nothing is wrong.", and asked for every 30 seconds. Each
returned to `OK` by itself within the five minutes, so none was set back by hand. The times are
those its history gives, in UTC:

| Alarm | Set to `ALARM` by hand | Told the topic | Back to `OK` by itself | Told the topic | On for |
|---|---|---|---|---|---|
| `vehicle-catalog-backend-health` | 17:02:32 | 17:02:32, succeeded | 17:04:03 | 17:04:03, succeeded | 91 s |
| `vehicle-catalog-jobs-failed` | 17:04:24 | 17:04:24, succeeded | 17:05:42 | 17:05:42, succeeded | 77 s |
| `vehicle-catalog-jobs-waiting` | 17:06:16 | 17:06:16, succeeded | 17:07:33 | 17:07:33, succeeded | 77 s |

The topic's counts from 17:02 to 17:07 UTC, asked for by the minute and summed:
`NumberOfMessagesPublished` 6, one at 17:02, two at 17:04, and one each at 17:05, 17:06, and
17:07; `NumberOfNotificationsDelivered` 6, in the same minutes; and `NumberOfNotificationsFailed`
datapoints of 0. The delivery of 17:07 was counted at 17:08:59, about a minute and a half after
that email was sent. The minute 17:01 has one delivered and nothing published: the email to
confirm, sent again.

### What the maintainer saw

He answered at about 17:12 UTC on 2026-10-14: all six emails of the trial after the click came,
all six to his inbox. He pasted two of them, the health alarm's going off and its clearing, and in
both the alarm's description shows whole, all 884 characters. The other four were counted, and
not read here. The email opens with a link to the alarm in the console. In the email of a clearing
the reason is CloudWatch's own, that the alarm's limit was crossed, and not the reason set by hand.

### What limits this

- An alarm set by hand shows the way from an alarm to the inbox, and not what sets an alarm off.
  Only the health alarm was ever seen to go off for its cause.
- The first two trials were made before the address had confirmed its subscription, so they
  showed the way from an alarm to the topic, and not from the topic to the inbox. The trial after
  the click showed both for the first three alarms; the worker's, the backup's, and the disk's
  were not set off again after it, and tell the same topic.
- The backup's alarm, set to `ALARM` by hand, was set back by hand after five minutes, so whether
  and how soon it returns by itself after a trial is not known.
- "Delivered" is SNS's count of what the mail server took. It does not say whether an email lay in
  the inbox or the junk, or how much of the description it showed. Those two are known only as far
  as the maintainer said.
- Each description is read in the email as AWS lays it out, and the rest of the email's wording is
  AWS's.

### What no alarm notices

Each checked against the code on 2026-10-14, where `infra/` makes the six alarms above and no
other:

- The host's memory. It is on the dashboard, from the agent, and no alarm watches it.
- An API or a worker that starts again and again. The dashboard counts the starts from the log,
  and nothing else does.
- A demo reset that fails. The reset writes to the log and sends no metric.
- The language model's allowance being reached. A refusal is counted in `ai.refused`, which the
  dashboard shows and no alarm watches.
- An answer of 502 or 504. Caddy and CloudFront answer those when the API does not, and the API's
  own count of requests does not see them.

### The price

AWS's price list for Amazon SNS in us-east-1, read on 2026-10-14 at 01:42 UTC from
`https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonSNS/current/us-east-1/index.csv` as
published on 2026-09-15: the first 1,000 email notifications a month are free, and each 100,000
after them cost US$2.00; the first 1,000,000 requests a month are free, and each million after
them costs US$0.50. The trial is six emails and the click's email a seventh. An alarm that changed
its state every minute of a month would send 43,200 emails, which comes to about US$0.84. The
three alarms and the two metrics of 2026-10-14 are priced above, under The worker, the backup, and
the disk.

### The trial's commands

One alarm at a time, with all three `OK` and no Deploy running, set to `ALARM` by hand with a
reason that says it is a trial, and left to return to `OK` by itself:

```sh
aws cloudwatch set-alarm-state --alarm-name <the alarm> --state-value ALARM \
  --state-reason "Set by hand to see the email. Nothing is wrong."
```

One that has not returned after five minutes is set back the same way. The alarm's history then
shows the change, an action to the topic that succeeded, the change back, and a second action:

```sh
aws cloudwatch describe-alarm-history --alarm-name <the alarm> --start-date <before the first> \
  --history-item-type Action
```

The topic's counts over those minutes, `NumberOfMessagesPublished`,
`NumberOfNotificationsDelivered`, and `NumberOfNotificationsFailed`, each asked for so, where an
answer without a datapoint counts as none:

```sh
aws cloudwatch get-metric-statistics --namespace AWS/SNS --metric-name <the count> \
  --dimensions Name=TopicName,Value=vehicle-catalog-alarms \
  --start-time <before the first> --end-time <after the last> --period 60 --statistics Sum
```
