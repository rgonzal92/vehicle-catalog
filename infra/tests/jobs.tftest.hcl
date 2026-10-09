# The queue of the backend's jobs, the queue that takes what could not be handled, what the host
# may do with the two, and how the host runs the worker. Nothing here reaches AWS: the provider is
# a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

# A queue's address names the region it is in, which is where the worker reaches it.
override_resource {
  target = aws_sqs_queue.jobs
  values = {
    arn = "arn:aws:sqs:us-east-1:123456789012:vehicle-catalog-jobs"
    url = "https://sqs.us-east-1.amazonaws.com/123456789012/vehicle-catalog-jobs"
  }
}

override_resource {
  target = aws_sqs_queue.jobs_failed
  values = {
    arn = "arn:aws:sqs:us-east-1:123456789012:vehicle-catalog-jobs-failed"
    url = "https://sqs.us-east-1.amazonaws.com/123456789012/vehicle-catalog-jobs-failed"
  }
}

run "a_message_no_one_could_handle_goes_to_the_dead_letter_queue" {
  assert {
    condition = jsondecode(aws_sqs_queue.jobs.redrive_policy) == {
      deadLetterTargetArn = aws_sqs_queue.jobs_failed.arn
      maxReceiveCount     = 3
    }
    error_message = "A message that was received three times and not deleted moves to the dead-letter queue."
  }

  assert {
    condition     = aws_sqs_queue.jobs.visibility_timeout_seconds == 120
    error_message = "A message that a worker has received is hidden from other receivers for two minutes."
  }

  assert {
    condition = jsondecode(aws_sqs_queue_redrive_allow_policy.jobs_failed.redrive_allow_policy) == {
      redrivePermission = "byQueue"
      sourceQueueArns   = [aws_sqs_queue.jobs.arn]
    }
    error_message = "No queue but the jobs' own sends its unhandled messages to the dead-letter queue."
  }

  assert {
    condition     = aws_sqs_queue.jobs.sqs_managed_sse_enabled && aws_sqs_queue.jobs_failed.sqs_managed_sse_enabled
    error_message = "What is on either queue is kept encrypted."
  }
}

run "a_job_that_has_failed_for_good_sets_off_an_alarm" {
  assert {
    condition = (
      aws_cloudwatch_metric_alarm.jobs_failed.namespace == "AWS/SQS"
      && aws_cloudwatch_metric_alarm.jobs_failed.metric_name == "ApproximateNumberOfMessagesVisible"
      && aws_cloudwatch_metric_alarm.jobs_failed.dimensions == tomap({ QueueName = aws_sqs_queue.jobs_failed.name })
    )
    error_message = "The alarm watches how many messages the dead-letter queue holds."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.jobs_failed.statistic == "Maximum"
      && aws_cloudwatch_metric_alarm.jobs_failed.comparison_operator == "GreaterThanThreshold"
      && aws_cloudwatch_metric_alarm.jobs_failed.threshold == 0
      && aws_cloudwatch_metric_alarm.jobs_failed.period == 60
      && aws_cloudwatch_metric_alarm.jobs_failed.evaluation_periods == 1
    )
    error_message = "The alarm goes off at one message, within a minute."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.jobs_failed.treat_missing_data == "notBreaching"
    error_message = "A dead-letter queue that reports nothing holds nothing, and sets off no alarm."
  }

  assert {
    condition     = aws_cloudwatch_metric_alarm.jobs_failed.alarm_actions == null
    error_message = "The alarm tells no one: it is there to be looked at."
  }

  assert {
    condition     = aws_sqs_queue.jobs_failed.name == "${aws_sqs_queue.jobs.name}-failed"
    error_message = "The dead-letter queue is named after the jobs' queue, which is how the worker finds it."
  }
}

run "a_queue_that_no_one_works_off_sets_off_an_alarm" {
  assert {
    condition = (
      aws_cloudwatch_metric_alarm.jobs_waiting.namespace == "AWS/SQS"
      && aws_cloudwatch_metric_alarm.jobs_waiting.metric_name == "ApproximateAgeOfOldestMessage"
      && aws_cloudwatch_metric_alarm.jobs_waiting.dimensions == tomap({ QueueName = aws_sqs_queue.jobs.name })
    )
    error_message = "The alarm watches how long the oldest message on the jobs' queue has waited."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.jobs_waiting.statistic == "Maximum"
      && aws_cloudwatch_metric_alarm.jobs_waiting.comparison_operator == "GreaterThanThreshold"
      && aws_cloudwatch_metric_alarm.jobs_waiting.threshold == 600
      && aws_cloudwatch_metric_alarm.jobs_waiting.period == 60
      && aws_cloudwatch_metric_alarm.jobs_waiting.evaluation_periods == 1
    )
    error_message = "The alarm goes off once a message has waited for more than ten minutes."
  }

  assert {
    # Three deliveries, each as far apart as a message stays hidden, end before the alarm does.
    condition = (
      jsondecode(aws_sqs_queue.jobs.redrive_policy).maxReceiveCount * aws_sqs_queue.jobs.visibility_timeout_seconds
      < aws_cloudwatch_metric_alarm.jobs_waiting.threshold
    )
    error_message = "A job that fails every time has left the queue before the alarm would go off for it."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.jobs_waiting.treat_missing_data == "notBreaching"
      && aws_cloudwatch_metric_alarm.jobs_waiting.alarm_actions == null
    )
    error_message = "A queue that reports nothing holds nothing, and the alarm tells no one: it is there to be looked at."
  }
}

run "the_dashboard_shows_what_waits_on_the_two_queues" {
  assert {
    condition = alltrue([
      for chart in [
        ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.jobs.name],
        ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.jobs.name],
        ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.jobs_failed.name],
      ] :
      anytrue([
        for widget in jsondecode(aws_cloudwatch_dashboard.backend.dashboard_body).widgets :
        widget.properties.period == 60
        && strcontains(jsonencode(widget.properties.metrics), trimsuffix(jsonencode(chart), "]"))
      ])
    ])
    error_message = "The dashboard shows, by the minute, how many messages wait on the jobs' queue, how old the oldest is, and how many are in the dead-letter queue."
  }
}

run "the_host_works_off_these_two_queues_and_no_other" {
  assert {
    condition = jsondecode(aws_iam_role_policy.host_jobs.policy).Statement == [{
      Sid    = "WorkOffTheJobs"
      Effect = "Allow"
      Action = [
        "sqs:ChangeMessageVisibility",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:ReceiveMessage",
        "sqs:SendMessage",
      ]
      Resource = [aws_sqs_queue.jobs.arn, aws_sqs_queue.jobs_failed.arn]
    }]
    error_message = "The host sends to, receives from, and deletes from the two queues, changes how long a message stays hidden, reads their attributes, and does nothing with any other queue."
  }

  assert {
    condition     = contains(aws_iam_role_policies_exclusive.host.policy_names, aws_iam_role_policy.host_jobs.name)
    error_message = "The policy is among the ones the host's role is held to."
  }
}

run "the_pipeline_plans_with_the_queues_in_place" {
  assert {
    condition = one([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if statement.Sid == "ReadTheQueues"
      ]) == {
      Sid      = "ReadTheQueues"
      Effect   = "Allow"
      Action   = ["sqs:GetQueueAttributes", "sqs:ListQueueTags"]
      Resource = [aws_sqs_queue.jobs.arn, aws_sqs_queue.jobs_failed.arn]
    }
    error_message = "The plan role reads how the two queues are set up, which a plan compares them with, and nothing that is on them."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if anytrue([for action in statement.Action : startswith(action, "sqs:")]) && statement.Sid != "ReadTheQueues"
    ]) == 0
    error_message = "The plan role does nothing else with a queue."
  }
}

run "the_host_runs_the_worker_beside_the_api" {
  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/compose.yaml"))
      && yamldecode(file("../deploy/compose.yaml")).services.worker.image == yamldecode(file("../deploy/compose.yaml")).services.backend.image
      && yamldecode(file("../deploy/compose.yaml")).services.worker.environment.SPRING_PROFILES_ACTIVE == "worker"
      && yamldecode(file("../deploy/compose.yaml")).services.worker.depends_on.backend.condition == "service_healthy"
    )
    error_message = "In the stack the host is given, the worker is the backend's image, run as the worker, and starts once the API is healthy."
  }

  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, "\nJOBS_QUEUE_URL=${aws_sqs_queue.jobs.url}\n")
      && strcontains(file("../backend/src/main/resources/application-worker.properties"), "app.jobs.queue-url=$${JOBS_QUEUE_URL}\n")
      && !strcontains(file("../backend/src/main/resources/application.properties"), "JOBS_QUEUE_URL")
    )
    error_message = "The host is told where the queue is, and of the two processes only the worker reads it."
  }

  assert {
    condition = (
      yamldecode(file("../deploy/compose.yaml")).services.worker.logging.options.awslogs-group == aws_cloudwatch_log_group.backend.name
      && yamldecode(file("../deploy/compose.yaml")).services.worker.logging.options.awslogs-stream == "worker"
      && yamldecode(file("../deploy/compose.yaml")).services.backend.logging.options.awslogs-stream == "backend"
    )
    error_message = "The worker's log goes to the backend's log group, under a stream of its own."
  }

  assert {
    condition = (
      yamldecode(file("../deploy/compose.yaml")).services.worker.environment.MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED == "true"
      && endswith(
        yamldecode(file("../deploy/compose.yaml")).services.worker.environment.MANAGEMENT_OTLP_METRICS_EXPORT_URL,
        ":${split(":", local.agent.metrics.metrics_collected.otlp.http_endpoint)[1]}/v1/metrics",
      )
      && endswith(
        yamldecode(file("../deploy/compose.yaml")).services.worker.environment.MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT,
        ":${split(":", local.agent.traces.traces_collected.otlp.http_endpoint)[1]}/v1/traces",
      )
    )
    error_message = "The worker sends its metric and its traces to the ports the agent takes each on."
  }

  assert {
    # Left to itself, Java lets each process have a quarter of the machine's memory.
    condition = (
      aws_instance.host.instance_type == "t4g.small"
      && can(regex("^-XX:MaxHeapSize=[0-9]+m$", yamldecode(file("../deploy/compose.yaml")).services.worker.environment.JAVA_TOOL_OPTIONS))
    )
    error_message = "On a host this small, the worker's heap has a limit of its own."
  }

  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/release.sh"))
      && strcontains(file("../deploy/release.sh"), "docker compose rm --stop --force backend worker\n")
      && strcontains(file("../deploy/release.sh"), "--wait-timeout 300 backend worker; then\n")
    )
    error_message = "With the release script the host is given, a release that is taken back takes back the API and the worker."
  }
}
