# The dashboard and its alarms

What the CloudWatch dashboard `vehicle-catalog` shows, in what order, and how each of its charts was seen drawn. The page is made by `infra/telemetry.tf`, and the tests in `infra/tests/telemetry.tftest.hcl` hold its layout, its order, its axes, its labels, its limits, and its row of alarms. [The alarms](#the-alarms) below has what each alarm notices, what to do, how it clears, whom it tells, and how that was tried.

## The order of the page

The page reads from top to bottom in the order a fault is looked for. Each group below the first stands under a heading of its own.

1. **The alarms**: one row with the state of every alarm the Terraform makes, three today: `vehicle-catalog-backend-health`, `vehicle-catalog-jobs-failed`, and `vehicle-catalog-jobs-waiting`.
2. **Are the API and the worker up**: whether the API is ready and whether the worker is, and the starts of the API and of the worker by the hour, counted from the log.
3. **Requests**: requests a minute by outcome, and how long a request that succeeds takes.
4. **Catalogs**: how long saving an edit and copying a catalog take; how long reading a catalog and working out its issues takes; catalogs approved, rejected, and updated from Approved; submits refused by reason.
5. **Jobs and queues**: jobs a minute by type and outcome; how long a job takes; messages that wait on the jobs' queue and in the dead-letter queue; how long the oldest message has waited, against the 600 seconds at which the alarm goes off.
6. **The language model**: requests a minute; how long a request takes; tokens in and out; tool calls and refusals; what the day has cost, against the day's allowance.
7. **The cache**: reads of an Approved version's contents by how the cache answered, and the share of those reads the cache answered.
8. **The host**: the share of its memory and disk in use; its processor time and the credits it has saved up; the API's heap, against the most it may grow to; the size of each backup of the database.

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

## The alarms

The Terraform makes three alarms and no other: `vehicle-catalog-backend-health` in
`infra/telemetry.tf`, and `vehicle-catalog-jobs-failed` and `vehicle-catalog-jobs-waiting` in
`infra/jobs.tf`. Each tells the SNS topic `vehicle-catalog-alarms` when it goes off and when it
clears, and nothing for want of data, and the topic has one subscription: by email, to the address
the budget alert goes to. The tests in `infra/tests/jobs.tftest.hcl` and
`infra/tests/telemetry.tftest.hcl` hold the three to the topic, the topic to that one subscription,
and the pipeline's plan role to reading the topic and its subscription and to nothing else of SNS.
What sets each alarm off, its metric, its limit, its minutes, and what it makes of a missing minute,
is as it was. `infra/README.md` has, under each alarm's name, what to do and the commands.

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
| `vehicle-catalog-backend-health` | The API has not said it is ready. A minute in which nothing arrived counts as not ready, so a CloudWatch agent stopped on the host looks the same. The worker sends no `health`, so it says nothing of the worker. | About eight minutes after the API stopped, seen once on 2026-10-08. Three minutes for an API that runs and says it is not ready, by its settings, not seen. The agent stopped, by its settings, not tried. | Ask `/api/health/readiness` through the site: 200 means the API is up and its reports are not arriving, unless the API has just come back and the email of its clearing follows; anything else means the API. | By itself some minutes after the API is ready again, three and a half in the one trial. |
| `vehicle-catalog-jobs-failed` | A job has failed for good: its work failed in each of three tries, and its message is in the dead-letter queue. | Within a minute or two of the third failed try, by its settings. | The Jobs page says what the last try said; Retry there once the cause is put right. | At Retry, within a minute or two, whether or not the job is then done: the email of its clearing does not say a job was done, the Jobs page does. A retried job that fails three times again sets it off again about six minutes after the Retry. By itself after the demo reset at 03:00 UTC, which takes the job away. After fourteen days, when the queue lets go of a message that nothing took out. |
| `vehicle-catalog-jobs-waiting` | A message has been on the jobs' queue for more than ten minutes: a worker that runs and is stuck, one that stopped with a message still on the queue, or one that works and has more jobs than it gets through. A worker that stopped with the queue empty does not set it off. | Within a minute of the message's eleventh minute, by its settings. Not seen to go off for its cause. | Whether the worker runs and what its log says, then how many messages wait on the queue. | By itself once no message on the queue is that old. |

How soon each goes off follows from its settings but where the table says it was seen: the health
alarm's eight minutes and three and a half are the one trial of 2026-10-08, in pull request #63.

### The trial

Made on 2026-10-14 from 03:32:57 to 03:37:33 UTC, with all three alarms `OK`, no Deploy running,
and the subscription still unconfirmed (What the maintainer saw, below). Each alarm was set to
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

### What the maintainer saw

He has not said. The subscription was still pending confirmation at 03:38 UTC, when the trial was
over, so none of its six emails was sent to him, and the click on "Confirm subscription" is still
his to make: until it, the alarms tell a topic that reaches no one, and 48 hours after the apply
SNS deletes the subscription, which the next plan then shows to be added anew. He was asked on the
ticket to confirm the address and then to say of the emails that come, how many came, whether to
his inbox or his junk, and whether each showed its whole description; how an email reads and
where it lands stay not known until he does.

### What limits this

- An alarm set by hand shows the way from an alarm to the inbox, and not what sets an alarm off.
  Only the health alarm was ever seen to go off for its cause.
- The trial was made before the address had confirmed its subscription, so it showed the way from
  an alarm to the topic, six times, and not from the topic to the inbox.
- "Delivered" is SNS's count of what the mail server took. It does not say whether an email lay in
  the inbox or the junk, or how much of the description it showed. Those two are known only as far
  as the maintainer said.
- Each description is read in the email as AWS lays it out, and the rest of the email's wording is
  AWS's.

### What no alarm notices

Each checked against the code on 2026-10-14, where `infra/` makes the three alarms above and no
other:

- A worker that stopped with its queue empty. The worker is also what sends the messages
  (`WorkerLoops.publish`), so nothing reaches the queue and the waiting alarm has nothing to watch.
- A night without a backup. The host writes the backup on its own (`deploy/backup.sh`, given to
  the host by `infra/host.tf`), and a backup that fails ends in failure in the host's journal, which
  nothing reads.
- A disk that fills, and the host's memory. Both are on the dashboard, from the agent, and no
  alarm watches either.
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
its state every minute of a month would send 43,200 emails, which comes to about US$0.84. No
alarm and no metric is added, so CloudWatch charges what it did.

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
