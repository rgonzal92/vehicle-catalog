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
      !contains(keys(yamldecode(file("../deploy/compose.yaml")).services.worker.environment), "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED")
      && endswith(
        yamldecode(file("../deploy/compose.yaml")).services.worker.environment.MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT,
        ":${split(":", local.agent.traces.traces_collected.otlp.http_endpoint)[1]}/v1/traces",
      )
    )
    error_message = "The worker sends its traces to the port the agent takes traces on, and no metrics."
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
