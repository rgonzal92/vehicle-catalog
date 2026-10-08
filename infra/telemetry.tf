# What the backend reports of itself, and where Amazon CloudWatch keeps it: its log, its traces, and
# a short list of metrics. An alarm watches the metric that says whether the backend is ready, and
# a dashboard shows the others. A metric that neither of them uses is not sent: each one beyond the
# first ten is paid for by the month.

locals {
  # What the CloudWatch agent on the host is told to do, and the namespace it publishes metrics
  # under.
  agent               = jsondecode(file("${path.module}/../deploy/agent.json"))
  telemetry_namespace = local.agent.metrics.namespace

  # What a request can come to. The backend counts its requests by this and by nothing else.
  request_outcomes = ["SUCCESS", "REDIRECTION", "CLIENT_ERROR", "SERVER_ERROR"]

  log_retention_days = 30
}

# The backend's log. Docker on the host writes it here, under the name deploy/compose.yaml gives.
resource "aws_cloudwatch_log_group" "backend" {
  name              = "/vehicle-catalog/backend"
  retention_in_days = local.log_retention_days
}

# Traces are kept as spans in a log group of X-Ray's own making, where every one of them can be
# searched, which AWS calls Transaction Search. It is a setting of the whole account in this region.
resource "aws_xray_trace_segment_destination" "spans" {
  destination = "CloudWatchLogs"

  depends_on = [aws_cloudwatch_log_resource_policy.spans]
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
resource "aws_cloudwatch_metric_alarm" "backend_health" {
  alarm_name        = "vehicle-catalog-backend-health"
  alarm_description = "The backend has not been ready for three minutes, or has reported nothing in that time."

  namespace           = local.telemetry_namespace
  metric_name         = "health"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
}

# The metrics, side by side. The host's processor time is among them at no charge: EC2 reports it
# for every machine.
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
          stacked = true
          stat    = "SampleCount"
          period  = 60
          metrics = [
            for outcome in local.request_outcomes :
            [local.telemetry_namespace, "http.server.requests", "outcome", outcome]
          ]
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
            [local.telemetry_namespace, "http.server.requests", "outcome", "SUCCESS", { stat = "p50", label = "half of them, within" }],
            [local.telemetry_namespace, "http.server.requests", "outcome", "SUCCESS", { stat = "p99", label = "99 in 100, within" }],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title   = "Whether the backend is ready (1) or not (0)"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          stat    = "Minimum"
          period  = 60
          metrics = [[local.telemetry_namespace, "health"]]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title  = "How long saving an edit and copying a catalog take, on average"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = 60
          metrics = [
            [local.telemetry_namespace, "catalog.edit"],
            [local.telemetry_namespace, "catalog.copy"],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title   = "Memory the backend's heap uses"
          region  = data.aws_region.current.region
          view    = "timeSeries"
          stat    = "Average"
          period  = 60
          metrics = [[local.telemetry_namespace, "jvm.heap.used"]]
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
    ]
  })
}
