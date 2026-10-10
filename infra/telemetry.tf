# What the backend reports of itself, and where Amazon CloudWatch keeps it: its log, its traces, and
# a short list of metrics. An alarm watches the metric that says whether the backend is ready, and
# a dashboard shows the others, beside what SQS reports of the jobs' two queues. A metric that neither of them uses is not sent: each one beyond the
# first ten is paid for by the month.

locals {
  # What the CloudWatch agent on the host is told to do, and the namespace it publishes metrics
  # under.
  agent               = jsondecode(file("${path.module}/../deploy/agent.json"))
  telemetry_namespace = local.agent.metrics.namespace

  # How the dashboard finds a metric of the backend's: in the namespace, by a name that follows.
  backend_metric = "Namespace=\"${local.telemetry_namespace}\""

  log_retention_days = 30
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

# Goes off when the backend has said for three minutes that it is not ready, and when it has said
# nothing in that time, as a backend that is stopped does. A release ordinarily stops it for less
# than that; one whose backend is slow to start sets the alarm off until it is ready.
#
# The agent tells each of the backend's metrics apart by four labels of its own making besides, and
# one of them is the version of the library that sent the metric. So the alarm asks for the metric
# by its name, whatever labels it carries, and goes on finding it when that library is updated.
resource "aws_cloudwatch_metric_alarm" "backend_health" {
  alarm_name        = "vehicle-catalog-backend-health"
  alarm_description = "The backend has not been ready for three minutes, or has reported nothing in that time."

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

# The metrics, side by side. The backend's are found by name, as the alarm finds its own. The
# host's processor time is among them at no charge: EC2 reports it for every machine.
resource "aws_cloudwatch_dashboard" "backend" {
  dashboard_name = "vehicle-catalog"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title  = "How long a request that succeeds takes"
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
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title  = "Whether the backend is ready (1) or not (0)"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"health\"', 'Minimum', 60)"
            id         = "health"
            label      = "ready"
          }]]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "How long saving an edit and copying a catalog take, on average"
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
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title  = "Memory the backend's heap uses"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"jvm.heap.used\"', 'Average', 60)"
            id         = "heap"
            label      = "heap"
          }]]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6
        properties = {
          title  = "Share of the host's memory and disk in use"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = 60
          metrics = [
            [local.telemetry_namespace, "mem_used_percent"],
            [local.telemetry_namespace, "disk_used_percent", "path", "/", "fstype", "xfs"],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 18, width = 12, height = 6
        properties = {
          title  = "The host's processor time, and what it has saved up"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = 300
          metrics = [
            ["AWS/EC2", "CPUUtilization", "InstanceId", aws_instance.host.id],
            ["AWS/EC2", "CPUCreditBalance", "InstanceId", aws_instance.host.id, { yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 18, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 0, y = 24, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 12, y = 24, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 0, y = 30, width = 12, height = 6
        properties = {
          title  = "How long a job takes on average, by type and outcome"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"job.run\"', 'Average', 60)"
            id         = "took"
            label      = "$${PROP('Dim.type')} $${PROP('Dim.outcome')}"
          }]]
        }
      },
      {
        type = "metric", x = 12, y = 30, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 0, y = 36, width = 12, height = 6
        properties = {
          title  = "How long the oldest message on the jobs' queue has waited, in seconds"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Maximum"
          period = 60
          metrics = [
            ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.jobs.name, { label = "oldest message" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 36, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 0, y = 42, width = 12, height = 6
        properties = {
          title  = "How long a request to the language model takes on average, by what it was for and how it ended"
          region = data.aws_region.current.region
          view   = "timeSeries"
          period = 60
          metrics = [[{
            expression = "SEARCH('${local.backend_metric} MetricName=\"ai.call\"', 'Average', 60)"
            id         = "answered"
            label      = "$${PROP('Dim.purpose')} $${PROP('Dim.outcome')}"
          }]]
        }
      },
      {
        type = "metric", x = 12, y = 42, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 0, y = 48, width = 12, height = 6
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
        }
      },
      {
        type = "metric", x = 12, y = 48, width = 12, height = 6
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
          # The backend's own setting, app.ai.allowance.daily.
          annotations = {
            horizontal = [{ label = "the day's allowance", value = 1 }]
          }
        }
      },
    ]
  })
}
