# What the backend reports of itself, and where Amazon CloudWatch keeps it: its log, its traces, and
# a short list of metrics. An alarm watches the metric that says whether the backend is ready, and
# a dashboard shows the alarms' states, every metric, readiness among them, and what SQS reports of
# the jobs' two queues. A metric that neither of them uses is not sent: each one beyond the first
# ten is paid for by the month. How often the API and the worker have started, the dashboard counts
# from the log.

locals {
  # What the CloudWatch agent on the host is told to do, and the namespace it publishes metrics
  # under.
  agent               = jsondecode(file("${path.module}/../deploy/agent.json"))
  telemetry_namespace = local.agent.metrics.namespace

  # How the dashboard finds a metric of the backend's: in the namespace, by a name that follows.
  backend_metric = "Namespace=\"${local.telemetry_namespace}\""

  log_retention_days = 30

  # Two limits the dashboard draws a line at, read from where each is set: the most the API's heap
  # may grow to on the host, in MiB, and the day's allowance for the language model, in US dollars.
  api_heap_mib       = tonumber(regex("(?s)\n  backend:.*?JAVA_TOOL_OPTIONS: -XX:MaxHeapSize=([0-9]+)m ", file("${path.module}/../deploy/compose.yaml"))[0])
  ai_daily_allowance = tonumber(regex("\napp\\.ai\\.allowance\\.daily=([0-9.]+)\n", file("${path.module}/../backend/src/main/resources/application.properties"))[0])
}

# The backend's log. Docker on the host writes it here, under the name deploy/compose.yaml gives.
resource "aws_cloudwatch_log_group" "backend" {
  name              = "/vehicle-catalog/backend"
  retention_in_days = local.log_retention_days
}

# Traces are kept as spans in a log group of X-Ray's own making, aws/spans, where every one of
# them can be searched, which AWS calls Transaction Search. It is a setting of the whole account in
# this region. X-Ray keeps a span for thirty days.
resource "aws_xray_trace_segment_destination" "spans" {
  destination = "CloudWatchLogs"

  depends_on = [
    aws_cloudwatch_log_resource_policy.spans,
    aws_cloudwatch_log_group.application_signals,
  ]
}

# X-Ray makes a second log group with that setting, and would keep what is in it for good. It is
# made here first, so that what is in it goes like everything else.
resource "aws_cloudwatch_log_group" "application_signals" {
  name              = "/aws/application-signals/data"
  retention_in_days = local.log_retention_days
}

# X-Ray writes the spans into its log groups, which this lets it do, for this account's traces alone.
resource "aws_cloudwatch_log_resource_policy" "spans" {
  policy_name = "vehicle-catalog-spans"

  policy_document = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "XRayWritesSpans"
      Effect    = "Allow"
      Principal = { Service = "xray.amazonaws.com" }
      Action    = "logs:PutLogEvents"
      Resource = [
        "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:aws/spans:*",
        "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/application-signals/data:*",
      ]
      Condition = {
        ArnLike      = { "aws:SourceArn" = "arn:aws:xray:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:*" }
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })
}

# What reporting takes: the host writes the backend's log, and its CloudWatch agent publishes
# metrics under the app's namespace and sends traces. It does none of this anywhere else.
resource "aws_iam_role_policy" "host_report" {
  name = "report"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "WriteTheBackendsLog"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.backend.arn}:*"
      },
      {
        # CloudWatch has no name for one namespace, so the namespace is a condition.
        Sid       = "PublishMetrics"
        Effect    = "Allow"
        Action    = "cloudwatch:PutMetricData"
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = local.telemetry_namespace } }
      },
      {
        # X-Ray has no name for one account's traces either. The second action is the agent
        # saying how its sending went.
        Sid      = "SendTraces"
        Effect   = "Allow"
        Action   = ["xray:PutTelemetryRecords", "xray:PutTraceSegments"]
        Resource = "*"
      },
    ]
  })
}

# What the three alarms tell when they go off and when they clear: a topic of Amazon SNS, with one
# subscription, by email, to the address the budget alert goes to. The topic has no access policy
# of its own and no key: SNS gives it the policy it gives every topic, which lets this account's
# alarms publish to it. An email carries the alarm's name and its description, so each description
# says what the alarm notices, the first thing to do, and how it clears. The address confirms its
# subscription with a click before anything is sent to it; Terraform does not wait for the click.
resource "aws_sns_topic" "alarms" {
  name = "vehicle-catalog-alarms"
}

resource "aws_sns_topic_subscription" "alarms_email" {
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.budget_notification_email
}

# Goes off when the API has not said it is ready. By its settings that is three minutes of an API
# that runs and says it is not ready, which has not been seen; an API that stopped was seen once,
# on 2026-10-08, to set it off about eight minutes later, and it cleared three and a half minutes
# after the API was ready again. A minute in which nothing arrived counts as not ready, so an agent
# that is stopped on the host looks the same as a stopped API. A release ordinarily stops the API
# for less than that. The worker sends no health, so the alarm says nothing of it.
#
# The agent tells each of the backend's metrics apart by four labels of its own making besides, and
# one of them is the version of the library that sent the metric. So the alarm asks for the metric
# by its name, whatever labels it carries, and goes on finding it when that library is updated.
resource "aws_cloudwatch_metric_alarm" "backend_health" {
  alarm_name        = "vehicle-catalog-backend-health"
  alarm_description = "The API has not said it is ready. It goes off about eight minutes after the API stops, as seen once on 2026-10-08, and by its settings three minutes after an API that runs starts saying it is not ready, which has not been seen. A CloudWatch agent that is stopped on the host looks the same, since a minute with no report counts as not ready. It says nothing of the worker. First, ask https://catalog.rgonz.dev/api/health/readiness in a browser: 200 means the API is up and its reports are not arriving, so look at the agent on the host, unless the API has just come back, after a release or a restart by Docker, and the email that it cleared follows; anything else means the API, so look at its log and at the stack on the host. It clears by itself some minutes after the API is ready again, three and a half in the one trial. infra/README.md has the commands under this alarm's name."

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"

  metric_query {
    id          = "health"
    expression  = "SELECT MIN(health) FROM \"${local.telemetry_namespace}\""
    period      = 60
    return_data = true
  }
}

# The page reads from top to bottom in the order a fault is looked for: the alarms' states, then
# under a heading each whether the API is up, requests, catalogs, jobs and queues, the language
# model, the cache, and the host. The backend's metrics are found by name, as the alarm finds its
# own. The host's processor time is among them at no charge: EC2 reports it for every machine.
# Every chart says what it measures and in what unit, names its axis after the unit in place of
# what CloudWatch would say there, and gives each line a label of its own; a line is drawn where a
# limit is set. The page is 24 columns wide and a chart stands 12 of them.
resource "aws_cloudwatch_dashboard" "backend" {
  dashboard_name = "vehicle-catalog"

  dashboard_body = jsonencode({
    widgets = [
      # The alarms, each named by its ARN, which holds the account's number and so is not written
      # out. The test counts the alarms the Terraform makes and holds the row to as many.
      {
        type = "alarm", x = 0, y = 0, width = 24, height = 4
        properties = {
          title = "The alarms"
          alarms = [
            aws_cloudwatch_metric_alarm.backend_health.arn,
            aws_cloudwatch_metric_alarm.jobs_failed.arn,
            aws_cloudwatch_metric_alarm.jobs_waiting.arn,
          ]
        }
      },
      {
        type       = "text", x = 0, y = 4, width = 24, height = 1
        properties = { markdown = "## Is the API up" }
      },
      {
        type = "metric", x = 0, y = 5, width = 12, height = 6
        properties = {
          title  = "Whether the API is ready (1) or not (0)"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"health\"', 'Minimum', 60)"
            id         = "health"
            label      = "ready"
          }]]
          yAxis = { left = { label = "1 ready, 0 not", showUnits = false } }
        }
      },
      # Nothing sends a metric of this. The API and the worker each write a line to the log once
      # they have started, and the chart counts those lines, which is a query of the log: it reads
      # the log of the time the dashboard shows, and is paid for by how much that is.
      {
        type = "log", x = 12, y = 5, width = 12, height = 6
        properties = {
          title  = "Starts of the API and of the worker by the hour, those of each release among them"
          region = data.aws_region.current.region
          view   = "timeSeries"
          query = join("\n| ", [
            "SOURCE '${aws_cloudwatch_log_group.backend.name}' | filter @message like \"Started VehicleCatalogApplication in\"",
            "stats sum(@logStream = \"backend\") as API, sum(@logStream = \"worker\") as worker by bin(1h)",
          ])
        }
      },
      {
        type       = "text", x = 0, y = 11, width = 24, height = 1
        properties = { markdown = "## Requests" }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title   = "Requests a minute, by outcome"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          period  = 60
          stacked = true
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"http.server.requests\"', 'SampleCount', 60)"
            id         = "requests"
            label      = "$${PROP('Dim.outcome')}"
          }]]
          yAxis = { left = { label = "requests a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6
        properties = {
          title  = "How long a request that succeeds takes, in milliseconds"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"http.server.requests\" outcome=\"SUCCESS\"', 'p50', 60)"
              id         = "half"
              label      = "half of them, within"
            }],
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"http.server.requests\" outcome=\"SUCCESS\"', 'p99', 60)"
              id         = "most"
              label      = "99 in 100, within"
            }],
          ]
          yAxis = { left = { label = "milliseconds", showUnits = false } }
        }
      },
      {
        type       = "text", x = 0, y = 18, width = 24, height = 1
        properties = { markdown = "## Catalogs" }
      },
      {
        type = "metric", x = 0, y = 19, width = 12, height = 6
        properties = {
          title  = "How long saving an edit and copying a catalog take, on average, in milliseconds"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.edit\"', 'Average', 60)"
              id         = "edit"
              label      = "saving an edit"
            }],
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.copy\"', 'Average', 60)"
              id         = "copy"
              label      = "copying a catalog"
            }],
          ]
          yAxis = { left = { label = "milliseconds", showUnits = false } }
        }
      },
      # What a person waits for after the save of an edit, and when they open a catalog. At the
      # largest size a catalog can have it is many times the save, so it has a chart of its own.
      {
        type = "metric", x = 12, y = 19, width = 12, height = 6
        properties = {
          title  = "How long reading a catalog and working out its issues takes, on average, in milliseconds"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.validate\"', 'Average', 60)"
            id         = "validate"
            label      = "reading a catalog with its issues"
          }]]
          yAxis = { left = { label = "milliseconds", showUnits = false } }
        }
      },
      {
        type = "metric", x = 0, y = 25, width = 12, height = 6
        properties = {
          title  = "Catalogs approved, rejected, and updated from Approved, a minute"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.approved\"', 'Sum', 60)"
              id         = "approved"
              label      = "approved"
            }],
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.rejected\"', 'Sum', 60)"
              id         = "rejected"
              label      = "rejected"
            }],
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.merged\"', 'Sum', 60)"
              id         = "merged"
              label      = "updated from Approved"
            }],
          ]
          yAxis = { left = { label = "catalogs a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 25, width = 12, height = 6
        properties = {
          title   = "Submits refused a minute, by reason"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          period  = 60
          stacked = true
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"catalog.submit.refused\"', 'Sum', 60)"
            id         = "refused"
            label      = "$${PROP('Dim.reason')}"
          }]]
          yAxis = { left = { label = "submits a minute", showUnits = false } }
        }
      },
      {
        type       = "text", x = 0, y = 31, width = 24, height = 1
        properties = { markdown = "## Jobs and queues" }
      },
      {
        type = "metric", x = 0, y = 32, width = 12, height = 6
        properties = {
          title   = "Jobs a minute, by type and outcome"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          period  = 60
          stacked = true
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"job.run\"', 'SampleCount', 60)"
            id         = "jobs"
            label      = "$${PROP('Dim.type')} $${PROP('Dim.outcome')}"
          }]]
          yAxis = { left = { label = "jobs a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 32, width = 12, height = 6
        properties = {
          title  = "How long a job takes on average, by type and outcome, in milliseconds"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"job.run\"', 'Average', 60)"
            id         = "took"
            label      = "$${PROP('Dim.type')} $${PROP('Dim.outcome')}"
          }]]
          yAxis = { left = { label = "milliseconds", showUnits = false } }
        }
      },
      {
        type = "metric", x = 0, y = 38, width = 12, height = 6
        properties = {
          title  = "Messages that wait on the jobs' queue, and in the dead-letter queue"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Maximum"
          period = 60
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.jobs.name, { label = "waiting" }],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.jobs_failed.name, { label = "failed for good" }],
          ]
          yAxis = { left = { label = "messages", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 38, width = 12, height = 6
        properties = {
          title  = "How long the oldest message on the jobs' queue has waited, in seconds, against when the alarm goes off"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Maximum"
          period = 60
          metrics = [
            ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.jobs.name, { label = "oldest message" }],
          ]
          yAxis = { left = { label = "seconds", showUnits = false } }
          annotations = {
            horizontal = [{ label = "the alarm goes off", value = aws_cloudwatch_metric_alarm.jobs_waiting.threshold }]
          }
        }
      },
      {
        type       = "text", x = 0, y = 44, width = 24, height = 1
        properties = { markdown = "## The language model" }
      },
      {
        type = "metric", x = 0, y = 45, width = 12, height = 6
        properties = {
          title   = "Requests to the language model a minute, by what they were for and how they ended"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          period  = 60
          stacked = true
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"ai.call\"', 'SampleCount', 60)"
            id         = "asked"
            label      = "$${PROP('Dim.purpose')} $${PROP('Dim.outcome')}"
          }]]
          yAxis = { left = { label = "requests a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 45, width = 12, height = 6
        properties = {
          title  = "How long a request to the language model takes on average, by what it was for and how it ended, in milliseconds"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"ai.call\"', 'Average', 60)"
            id         = "answered"
            label      = "$${PROP('Dim.purpose')} $${PROP('Dim.outcome')}"
          }]]
          yAxis = { left = { label = "milliseconds", showUnits = false } }
        }
      },
      {
        type = "metric", x = 0, y = 51, width = 12, height = 6
        properties = {
          title  = "Tokens the language model was sent (in) and answered with (out), a minute"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"ai.tokens\"', 'Sum', 60)"
            id         = "tokens"
            label      = "$${PROP('Dim.direction')}"
          }]]
          yAxis = { left = { label = "tokens a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 51, width = 12, height = 6
        properties = {
          title  = "Tool calls of the analyst, and requests to the language model that were refused, a minute"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"ai.tool.calls\"', 'Sum', 60)"
              id         = "tools"
              label      = "tool calls"
            }],
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"ai.refused\"', 'Sum', 60)"
              id         = "refused"
              label      = "refused: $${PROP('Dim.reason')}"
            }],
          ]
          yAxis = { left = { label = "tool calls and refusals a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 0, y = 57, width = 12, height = 6
        properties = {
          title  = "What asking the language model has cost today, in US dollars, against the day's allowance"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"ai.spent\"', 'Maximum', 60)"
            id         = "spent"
            label      = "reserved and spent"
          }]]
          yAxis = { left = { label = "US dollars", showUnits = false } }
          # The backend's own setting, app.ai.allowance.daily.
          annotations = {
            horizontal = [{ label = "the day's allowance", value = local.ai_daily_allowance }]
          }
        }
      },
      {
        type       = "text", x = 0, y = 63, width = 24, height = 1
        properties = { markdown = "## The cache" }
      },
      {
        type = "metric", x = 0, y = 64, width = 12, height = 6
        properties = {
          title   = "Reads of an Approved version's contents a minute, by how the cache answered"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          period  = 60
          stacked = true
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"cache.read\"', 'Sum', 60)"
            id         = "reads"
            label      = "$${PROP('Dim.result')}"
          }]]
          yAxis = { left = { label = "reads a minute", showUnits = false } }
        }
      },
      {
        type = "metric", x = 12, y = 64, width = 12, height = 6
        properties = {
          title  = "Share of the reads of Approved versions' contents that the cache answered, in percent"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"cache.read\" result=\"hit\"', 'Sum', 60)"
              id         = "hits"
              visible    = false
            }],
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"cache.read\"', 'Sum', 60)"
              id         = "all"
              visible    = false
            }],
            # A minute without reads is zero over zero, which CloudWatch drops with a warning sign
            # on the chart: the share is asked for the minutes that had reads.
            [{
              expression = "IF(SUM(all) > 0, 100 * SUM(hits) / SUM(all))"
              id         = "share"
              label      = "hits"
            }],
          ]
          yAxis = { left = { label = "percent", min = 0, max = 100, showUnits = false } }
        }
      },
      {
        type       = "text", x = 0, y = 70, width = 24, height = 1
        properties = { markdown = "## The host" }
      },
      {
        type = "metric", x = 0, y = 71, width = 12, height = 6
        properties = {
          title  = "Share of the host's memory and disk in use, in percent"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = 60
          metrics = [
            [local.telemetry_namespace, "mem_used_percent", { label = "memory in use" }],
            [local.telemetry_namespace, "disk_used_percent", "path", "/", "fstype", "xfs", { label = "disk in use" }],
          ]
          yAxis = { left = { label = "percent", min = 0, max = 100, showUnits = false } }
        }
      },
      # The two lines are asked for by the instance's id, which stays out of their labels.
      {
        type = "metric", x = 12, y = 71, width = 12, height = 6
        properties = {
          title  = "The host's processor time in use, in percent, and the credits it has saved up"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = 300
          metrics = [
            ["AWS/EC2", "CPUUtilization", "InstanceId", aws_instance.host.id, { label = "processor time in use" }],
            ["AWS/EC2", "CPUCreditBalance", "InstanceId", aws_instance.host.id, { label = "credits saved up", yAxis = "right" }],
          ]
          yAxis = {
            left  = { label = "percent", showUnits = false }
            right = { label = "credits", showUnits = false }
          }
        }
      },
      # The API sends the bytes its heap holds; the chart draws them in MiB, the unit the limit is
      # set in, and the search itself is not drawn.
      {
        type = "metric", x = 0, y = 77, width = 12, height = 6
        properties = {
          title  = "Memory the API's heap uses, in MiB, against the most it may grow to"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [
            [{
              expression = "SEARCH('${local.backend_metric} MetricName=\"jvm.heap.used\"', 'Average', 60)"
              id         = "bytes"
              visible    = false
            }],
            [{
              expression = "bytes / 1048576"
              id         = "heap"
              label      = "heap"
            }],
          ]
          yAxis = { left = { label = "MiB", showUnits = false } }
          annotations = {
            horizontal = [{ label = "the most it may grow to", value = local.api_heap_mib }]
          }
        }
      },
    ]
  })
}
