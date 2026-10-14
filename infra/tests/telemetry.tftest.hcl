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
      strcontains(one(aws_cloudwatch_metric_alarm.backend_health.metric_query).expression, " FROM \"vehicle-catalog\"")
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
    # By name alone: the agent adds labels of its own to the metric, and one of them changes with
    # the version of the library that sends it.
    condition = (
      one(aws_cloudwatch_metric_alarm.backend_health.metric_query).expression == "SELECT MIN(health) FROM \"vehicle-catalog\""
      && aws_cloudwatch_metric_alarm.backend_health.metric_name == null
      && aws_cloudwatch_metric_alarm.backend_health.comparison_operator == "LessThanThreshold"
      && aws_cloudwatch_metric_alarm.backend_health.threshold == 1
    )
    error_message = "The alarm goes off when the least the backend said of its health in a minute is below one, whatever labels the metric carries."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.backend_health.treat_missing_data == "breaching"
    error_message = "A backend that says nothing is taken for one that is not ready."
  }

  assert {
    # Three minutes is what the settings give for an API that runs and says it is not ready. An
    # API that stopped was seen once to set the alarm off about eight minutes later.
    condition = (
      one(aws_cloudwatch_metric_alarm.backend_health.metric_query).period == 60
      && aws_cloudwatch_metric_alarm.backend_health.evaluation_periods == 3
    )
    error_message = "The alarm looks at three minutes, which is longer than a release ordinarily stops the API for."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.backend_health.alarm_actions == toset([aws_sns_topic.alarms.arn])
      && aws_cloudwatch_metric_alarm.backend_health.ok_actions == toset([aws_sns_topic.alarms.arn])
      && aws_cloudwatch_metric_alarm.backend_health.insufficient_data_actions == null
    )
    error_message = "The alarm tells the topic when it goes off and when it clears, and nothing for want of data."
  }
}

run "the_alarms_email_the_maintainer" {
  assert {
    condition     = aws_sns_topic.alarms.name == "vehicle-catalog-alarms" && aws_sns_topic.alarms.kms_master_key_id == null
    error_message = "The alarms tell one topic, which has no key of its own."
  }

  assert {
    # Terraform cannot list the resources of one type, so the subscriptions are counted in the
    # text of the Terraform files, as the alarms are below.
    condition = (
      aws_sns_topic_subscription.alarms_email.topic_arn == aws_sns_topic.alarms.arn
      && aws_sns_topic_subscription.alarms_email.protocol == "email"
      && aws_sns_topic_subscription.alarms_email.endpoint == var.budget_notification_email
      && sum([
        for name in fileset(".", "*.tf") : length(regexall("\nresource \"aws_sns_topic_subscription\" ", file(name)))
      ]) == 1
    )
    error_message = "The topic's only subscription is by email, to the address the budget alert goes to: one address is told, and nothing else."
  }
}

run "the_pipeline_plans_with_the_topic_in_place" {
  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if statement.Sid == "ReadTheAlarmsTopic"
      ]) == {
      Sid      = "ReadTheAlarmsTopic"
      Effect   = "Allow"
      Action   = ["sns:GetSubscriptionAttributes", "sns:GetTopicAttributes", "sns:ListTagsForResource"]
      Resource = [aws_sns_topic.alarms.arn]
    }
    error_message = "The plan role reads how the topic and its subscription are set up, which a plan compares them with, and publishes nothing."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if anytrue([for action in statement.Action : startswith(action, "sns:")]) && statement.Sid != "ReadTheAlarmsTopic"
    ]) == 0
    error_message = "The plan role does nothing else with a topic."
  }
}

run "every_metric_that_is_sent_is_looked_at" {
  assert {
    # Tests in backend/ hold the API to the first eleven and the last five, and the worker to
    # job.run and the model's requests, tokens, and refusals.
    condition = alltrue([
      for metric in [
        "health",
        "http.server.requests",
        "jvm.heap.used",
        "catalog.edit",
        "catalog.copy",
        "catalog.validate",
        "catalog.submit.refused",
        "catalog.approved",
        "catalog.rejected",
        "catalog.merged",
        "cache.read",
        "job.run",
        "ai.call",
        "ai.tokens",
        "ai.tool.calls",
        "ai.refused",
        "ai.spent",
      ] :
      anytrue([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        strcontains(jsonencode(try(widget.properties.metrics, [])), "Namespace=\\\"vehicle-catalog\\\" MetricName=\\\"${metric}\\\"")
      ])
    ])
    error_message = "The dashboard shows each of the backend's metrics, found in the app's namespace by its name."
  }

  assert {
    condition = one([
      for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
      [for metric in widget.properties.metrics : metric[0].expression]
      if strcontains(jsonencode(try(widget.properties.metrics, [])), "result=\\\"hit\\\"")
      ]) == [
      "SEARCH('Namespace=\"vehicle-catalog\" MetricName=\"cache.read\" result=\"hit\"', 'Sum', 60)",
      "SEARCH('Namespace=\"vehicle-catalog\" MetricName=\"cache.read\"', 'Sum', 60)",
      "IF(SUM(all) > 0, 100 * SUM(hits) / SUM(all))",
    ]
    error_message = "One chart shows the share of the reads of Approved versions that the cache answered: the hits over all of them, whatever labels the metric carries, in the minutes that had reads and in no other."
  }

  assert {
    condition = one([
      for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
      widget.properties.annotations.horizontal[0].value
      if strcontains(jsonencode(try(widget.properties.metrics, [])), "MetricName=\\\"ai.spent\\\"")
    ]) == tonumber(regex("\napp\\.ai\\.allowance\\.daily=([0-9.]+)\n", file("../backend/src/main/resources/application.properties"))[0])
    error_message = "What the model has cost today is drawn against the day's allowance, as the backend's own setting has it."
  }

  assert {
    # Left to itself, CloudWatch draws such a chart in steps of five minutes and shows one point
    # in five.
    condition = alltrue([
      for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
      widget.properties.period == 60
      if strcontains(jsonencode(try(widget.properties.metrics, [])), "SEARCH(")
    ])
    error_message = "A chart that finds its metrics by name is drawn by the minute, which is how its metrics are asked for."
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

run "every_chart_says_what_it_measures_and_stands_where_a_fault_is_looked_for" {
  assert {
    # No two charts overlap, and none reaches beyond the page's 24 columns.
    condition = (
      alltrue(flatten([
        for i, a in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets : [
          for j, b in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
          i == j || a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y
        ]
      ]))
      && alltrue([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.x >= 0 && widget.y >= 0 && widget.width > 0 && widget.height > 0 && widget.x + widget.width <= 24
      ])
    )
    error_message = "No two charts of the dashboard overlap, and none reaches beyond the page's 24 columns."
  }

  assert {
    # Read by row and then by column. A chart of metrics is known by the first metric it asks for
    # and the statistic it asks for, a heading by its words, and the starts chart by its type.
    condition = [
      for key in sort([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets : format("%03d-%03d", widget.y, widget.x)
      ]) :
      one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        (
          widget.type == "alarm" ? "the alarms" :
          widget.type == "text" ? widget.properties.markdown :
          widget.type == "log" ? "the starts" :
          try(
            join(" ", regex("MetricName=\"([^\"]+)\"[^']*', '([^']+)'", widget.properties.metrics[0][0].expression)),
            widget.properties.metrics[0][1],
          )
        )
        if format("%03d-%03d", widget.y, widget.x) == key
      ])
      ] == [
      "the alarms",
      "## Is the API up",
      "health Minimum",
      "the starts",
      "## Requests",
      "http.server.requests SampleCount",
      "http.server.requests p50",
      "## Catalogs",
      "catalog.edit Average",
      "catalog.validate Average",
      "catalog.approved Sum",
      "catalog.submit.refused Sum",
      "## Jobs and queues",
      "job.run SampleCount",
      "job.run Average",
      "ApproximateNumberOfMessagesVisible",
      "ApproximateAgeOfOldestMessage",
      "## The language model",
      "ai.call SampleCount",
      "ai.call Average",
      "ai.tokens Sum",
      "ai.tool.calls Sum",
      "ai.spent Maximum",
      "## The cache",
      "cache.read Sum",
      "cache.read Sum",
      "## The host",
      "mem_used_percent",
      "CPUUtilization",
      "jvm.heap.used Average",
    ]
    error_message = "The page reads in the order a fault is looked for: the alarms, then whether the API is up, requests, catalogs, jobs and queues, the language model, the cache, and the host, each group under a heading of its own."
  }

  assert {
    # An axis with a label alone says the label and then "No unit" beside it.
    condition = (
      alltrue([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        try(widget.properties.yAxis.left.label != "" && widget.properties.yAxis.left.showUnits == false, false)
        if widget.type == "metric"
      ])
      && one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        try(widget.properties.yAxis.right.label != "" && widget.properties.yAxis.right.showUnits == false, false)
        if strcontains(jsonencode(try(widget.properties.metrics, [])), "CPUCreditBalance")
      ])
    )
    error_message = "Every chart of metrics names its left axis after its unit, and the host's processor its right one too, in place of what CloudWatch would say there."
  }

  assert {
    # A line that is drawn has a label of its own: a line found by a search that is not drawn
    # needs none. The host's four lines name the machine by its id, and the id stays out of the
    # words on the page.
    condition = (
      alltrue(flatten([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets : [
          for line in widget.properties.metrics :
          can(line[0].expression)
          ? try(line[0].visible, true) == false || try(line[0].label, "") != ""
          : try(line[length(line) - 1].label, "") != ""
        ] if widget.type == "metric"
      ]))
      && length(regexall("\\bi-[0-9a-f]{17}\\b", jsonencode([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets : [
          try(widget.properties.title, ""),
          [for line in try(widget.properties.metrics, []) : try(line[0].label, try(line[length(line) - 1].label, ""))],
        ]
      ]))) == 0
    )
    error_message = "Every line that is drawn has a label of its own, and no title or label holds the instance's id."
  }

  assert {
    # Each limit is drawn as it is set: the seconds at which the alarm on the jobs' queue goes
    # off, the most the API's heap may grow to on the host, and the day's allowance for the model.
    condition = (
      one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.properties.annotations.horizontal[0].value
        if strcontains(jsonencode(try(widget.properties.metrics, [])), "ApproximateAgeOfOldestMessage")
      ]) == aws_cloudwatch_metric_alarm.jobs_waiting.threshold
      && one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.properties.annotations.horizontal[0].value
        if strcontains(jsonencode(try(widget.properties.metrics, [])), "MetricName=\\\"jvm.heap.used\\\"")
      ]) == tonumber(regex("(?s)\n  backend:.*?JAVA_TOOL_OPTIONS: -XX:MaxHeapSize=([0-9]+)m ", file("../deploy/compose.yaml"))[0])
      && one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.properties.annotations.horizontal[0].value
        if strcontains(jsonencode(try(widget.properties.metrics, [])), "MetricName=\\\"ai.spent\\\"")
      ]) == tonumber(regex("\napp\\.ai\\.allowance\\.daily=([0-9.]+)\n", file("../backend/src/main/resources/application.properties"))[0])
    )
    error_message = "A line is drawn at each limit as it is set: the alarm's threshold on the oldest message, the API's heap in deploy/compose.yaml in MiB, and the day's allowance in application.properties."
  }

  assert {
    # Terraform cannot list the resources of one type, so the alarms are counted in the text of
    # the Terraform files. An alarm made with count or for_each would be one block for several
    # alarms; none is made so.
    condition = (
      length(distinct(try(one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.properties.alarms if widget.type == "alarm"
        ]), []))) == sum([
        for name in fileset(".", "*.tf") : length(regexall("\nresource \"aws_cloudwatch_metric_alarm\" ", file(name)))
      ])
      && alltrue([
        for arn in [
          aws_cloudwatch_metric_alarm.backend_health.arn,
          aws_cloudwatch_metric_alarm.jobs_failed.arn,
          aws_cloudwatch_metric_alarm.jobs_waiting.arn,
        ] :
        contains(try(one([
          for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
          widget.properties.alarms if widget.type == "alarm"
        ]), []), arn)
      ])
    )
    error_message = "The alarms' row shows the state of every alarm the Terraform makes, each once, by the ARN of its resource."
  }
}

run "the_starts_of_the_api_and_the_worker_are_counted_from_the_backends_log" {
  assert {
    condition = [
      for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
      split(" | ", widget.properties.query)[0] if widget.type == "log"
    ] == ["SOURCE '/vehicle-catalog/backend'"]
    error_message = "One chart of the dashboard is a query of the log, and the log it reads is the backend's."
  }

  assert {
    # Spring Boot writes the line once a process has started, and names the application's class
    # in it.
    condition = (
      strcontains(
        one([
          for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
          widget.properties.query if widget.type == "log"
        ]),
        " | filter @message like \"Started VehicleCatalogApplication in\"\n",
      )
      && fileexists("../backend/src/main/java/dev/rgonz/catalog/VehicleCatalogApplication.java")
    )
    error_message = "The chart counts the line that the API and the worker each write once they have started."
  }

  assert {
    # Two counts by the hour and by nothing else, which is the form AWS's page on stats gives for
    # several values drawn over time.
    condition = (
      endswith(
        one([
          for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
          widget.properties.query if widget.type == "log"
        ]),
        "\n| stats sum(@logStream = \"backend\") as API, sum(@logStream = \"worker\") as worker by bin(1h)",
      )
      && strcontains(file("../deploy/compose.yaml"), "awslogs-stream: backend\n")
      && strcontains(file("../deploy/compose.yaml"), "awslogs-stream: worker\n")
      && one([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.properties.view if widget.type == "log"
      ]) == "timeSeries"
    )
    error_message = "The chart counts the starts by the hour, the API's and the worker's each, by the names Docker writes their logs under."
  }
}

run "traces_are_kept_as_spans_that_xray_alone_writes" {
  assert {
    condition     = aws_cloudwatch_log_group.application_signals.retention_in_days == 30
    error_message = "What X-Ray keeps beside the spans goes after thirty days too."
  }

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
