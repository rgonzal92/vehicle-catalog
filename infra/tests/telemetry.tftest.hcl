# Where the backend's log, traces and metrics go, what the host may do to send them, and what
# watches them. Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "the_backends_log_is_kept_for_a_while_and_the_host_writes_it" {
  assert {
    condition     = strcontains(file("../deploy/compose.yaml"), "awslogs-group: ${aws_cloudwatch_log_group.backend.name}\n")
    error_message = "Docker on the host writes the backend's log to the group that is made for it."
  }

  assert {
    # Docker asks no one for the region as the container starts, and in this mode does not wait
    # for CloudWatch either.
    condition = (
      strcontains(file("../deploy/compose.yaml"), "awslogs-region: ${data.aws_region.current.region}\n")
      && strcontains(file("../deploy/compose.yaml"), "mode: non-blocking\n")
    )
    error_message = "The backend starts and runs whether or not its log can be written just then."
  }

  assert {
    condition     = aws_cloudwatch_log_group.backend.retention_in_days == 30
    error_message = "What is in the backend's log goes after thirty days."
  }

  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.host_report.policy).Statement : statement
      if statement.Sid == "WriteTheBackendsLog"
      ]) == {
      Sid      = "WriteTheBackendsLog"
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
      Resource = "${aws_cloudwatch_log_group.backend.arn}:*"
    }
    error_message = "The host writes to the backend's log group and to no other, and makes no group."
  }
}

run "the_host_publishes_metrics_under_the_apps_namespace_alone" {
  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.host_report.policy).Statement : statement
      if contains(flatten([statement.Action]), "cloudwatch:PutMetricData")
    ]).Condition == { StringEquals = { "cloudwatch:namespace" = "vehicle-catalog" } }
    error_message = "The host publishes metrics under the app's namespace and under no other."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.backend_health.namespace == "vehicle-catalog"
      && local.agent.metrics.namespace == "vehicle-catalog"
    )
    error_message = "The agent publishes under the namespace the alarm looks in."
  }

  assert {
    condition = toset(flatten([
      for statement in jsondecode(aws_iam_role_policy.host_report.policy).Statement : statement.Action
      ])) == toset([
      "cloudwatch:PutMetricData",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "xray:PutTelemetryRecords",
      "xray:PutTraceSegments",
    ])
    error_message = "Reporting takes those five actions and no other."
  }
}

run "the_agent_is_where_the_backend_sends_to" {
  assert {
    condition     = strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/agent.json"))
    error_message = "The host is given the agent's file as it is in deploy/."
  }

  assert {
    condition = strcontains(
      file("../deploy/compose.yaml"),
      "MANAGEMENT_OTLP_METRICS_EXPORT_URL: http://host.docker.internal:${split(":", local.agent.metrics.metrics_collected.otlp.http_endpoint)[1]}/v1/metrics\n",
    )
    error_message = "The backend sends its metrics to the port the agent takes metrics on."
  }

  assert {
    condition = strcontains(
      file("../deploy/compose.yaml"),
      "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT: http://host.docker.internal:${split(":", local.agent.traces.traces_collected.otlp.http_endpoint)[1]}/v1/traces\n",
    )
    error_message = "The backend sends its traces to the port the agent takes traces on."
  }

  assert {
    # A container reaches the host on an address of Docker's, not on the host's own.
    condition = alltrue([
      for endpoint in [
        local.agent.metrics.metrics_collected.otlp.http_endpoint,
        local.agent.traces.traces_collected.otlp.http_endpoint,
      ] : startswith(endpoint, "0.0.0.0:")
    ])
    error_message = "The agent listens where a container can reach it."
  }

  assert {
    condition = length(distinct([
      for endpoint in [
        local.agent.metrics.metrics_collected.otlp.grpc_endpoint,
        local.agent.metrics.metrics_collected.otlp.http_endpoint,
        local.agent.traces.traces_collected.otlp.grpc_endpoint,
        local.agent.traces.traces_collected.otlp.http_endpoint,
      ] : split(":", endpoint)[1]
    ])) == 4
    error_message = "Each of the agent's four listeners has a port of its own."
  }
}

run "a_backend_that_is_not_ready_or_says_nothing_sets_off_the_alarm" {
  assert {
    condition = (
      aws_cloudwatch_metric_alarm.backend_health.metric_name == "health"
      && aws_cloudwatch_metric_alarm.backend_health.dimensions == null
      && aws_cloudwatch_metric_alarm.backend_health.statistic == "Minimum"
      && aws_cloudwatch_metric_alarm.backend_health.comparison_operator == "LessThanThreshold"
      && aws_cloudwatch_metric_alarm.backend_health.threshold == 1
    )
    error_message = "The alarm goes off when the least the backend said of its health in a minute is below one."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.backend_health.treat_missing_data == "breaching"
    error_message = "A backend that says nothing is taken for one that is not ready."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.backend_health.period == 60
      && aws_cloudwatch_metric_alarm.backend_health.evaluation_periods == 3
    )
    error_message = "The alarm waits three minutes, which is longer than a release ordinarily stops the backend for."
  }
}

run "every_metric_that_is_sent_is_looked_at" {
  assert {
    # The backend sends these four and its health, which the alarm watches. A test in backend/
    # holds it to those five.
    condition = alltrue([
      for metric in ["http.server.requests", "jvm.heap.used", "catalog.edit", "catalog.copy"] :
      strcontains(aws_cloudwatch_dashboard.backend.dashboard_body, "\"${metric}\"")
    ])
    error_message = "The dashboard shows the requests, the heap, and how long an edit and a copy take."
  }

  assert {
    # The agent names a measurement after what it measures: used_percent of disk is
    # disk_used_percent.
    condition = alltrue(flatten([
      for name, settings in local.agent.metrics.metrics_collected : [
        for measurement in settings.measurement :
        strcontains(
          aws_cloudwatch_dashboard.backend.dashboard_body,
          "\"${startswith(measurement, name) ? measurement : "${name}_${measurement}"}\"",
        )
      ] if name != "otlp"
    ]))
    error_message = "The dashboard shows everything the agent reports of the host."
  }

  assert {
    condition = [
      for name, settings in local.agent.metrics.metrics_collected : name if name != "otlp"
    ] == ["disk", "mem"]
    error_message = "Of the host, the agent reports on memory and disk and on nothing else."
  }
}

run "traces_are_kept_as_spans_that_xray_alone_writes" {
  assert {
    condition     = aws_xray_trace_segment_destination.spans.destination == "CloudWatchLogs"
    error_message = "Traces are kept in CloudWatch Logs, where each span can be searched."
  }

  assert {
    condition = (
      length(jsondecode(aws_cloudwatch_log_resource_policy.spans.policy_document).Statement) == 1
      && jsondecode(aws_cloudwatch_log_resource_policy.spans.policy_document).Statement[0].Principal == { Service = "xray.amazonaws.com" }
      && jsondecode(aws_cloudwatch_log_resource_policy.spans.policy_document).Statement[0].Action == "logs:PutLogEvents"
      && jsondecode(aws_cloudwatch_log_resource_policy.spans.policy_document).Statement[0].Condition == {
        ArnLike      = { "aws:SourceArn" = "arn:aws:xray:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:*" }
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    )
    error_message = "X-Ray writes the spans, for this account's traces alone, and no one else is let in."
  }

  assert {
    condition = jsondecode(aws_cloudwatch_log_resource_policy.spans.policy_document).Statement[0].Resource == [
      "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:aws/spans:*",
      "arn:aws:logs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/application-signals/data:*",
    ]
    error_message = "X-Ray writes into its own two log groups and into no other."
  }
}
