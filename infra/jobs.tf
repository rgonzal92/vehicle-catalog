# The queue that carries the messages of the backend's jobs, from the worker that sends them to the
# worker that does them, and the queue that takes the messages no one could handle. The worker runs
# on the host beside the API; docs/adr/0011 says why it alone talks to the queue.

# Where a message goes once it has been received three times and was not deleted. It is kept
# there for fourteen days, which is as long as SQS keeps anything. The worker finds this queue by
# its name, which is the jobs' queue's with "-failed" after it.
resource "aws_sqs_queue" "jobs_failed" {
  name                      = "vehicle-catalog-jobs-failed"
  message_retention_seconds = 14 * 24 * 60 * 60
  sqs_managed_sse_enabled   = true
}

# A message that a worker has received is hidden from other receivers for two minutes, which is
# the time a job has before its message is delivered again.
resource "aws_sqs_queue" "jobs" {
  name                       = "vehicle-catalog-jobs"
  visibility_timeout_seconds = 120
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.jobs_failed.arn
    maxReceiveCount     = 3
  })
}

# No queue but the jobs' own sends its unhandled messages there.
resource "aws_sqs_queue_redrive_allow_policy" "jobs_failed" {
  queue_url = aws_sqs_queue.jobs_failed.id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.jobs.arn]
  })
}

# Goes off while the dead-letter queue holds a message, which is a job that has failed for good:
# its message was delivered three times and its work failed each time. It emails the maintainer
# through the topic in telemetry.tf when it goes off and when it clears, as every alarm does. It
# clears at Retry on the Jobs page, done or not: within a minute of the Retry the worker takes the
# message of the tries before out of that queue, since the job is no longer failed, whether or not
# the new try has been done. It clears by itself after the demo reset at 03:00 UTC, which takes
# the job away, and a message that nothing takes out leaves the queue after fourteen days. SQS
# reports the queue's depth at no charge.
resource "aws_cloudwatch_metric_alarm" "jobs_failed" {
  alarm_name        = "vehicle-catalog-jobs-failed"
  alarm_description = "A job has failed for good: its work failed in each of three tries, and its message is in the dead-letter queue. First, the Jobs page in the app, which an admin reaches from the sidebar, says what the last try said. Once the cause is put right, Retry there has the job tried again. The alarm clears within a minute or two of Retry, whether or not the job is then done: the Jobs page says whether it was, and this email does not. A retried job that fails three times again sets the alarm off again about six minutes after the Retry. It also clears by itself a few minutes after the demo reset at 03:00 UTC, which takes the job away without anyone putting it right; the worker's log, kept thirty days, is then where the failure still is. A message that nothing takes out leaves the queue after fourteen days. infra/README.md has the commands under this alarm's name."

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  namespace   = "AWS/SQS"
  metric_name = "ApproximateNumberOfMessagesVisible"
  dimensions  = { QueueName = aws_sqs_queue.jobs_failed.name }
  statistic   = "Maximum"
  period      = 60

  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  # A queue that nothing has touched for hours reports nothing, and holds nothing.
  treat_missing_data = "notBreaching"
}

# Goes off when the oldest message on the jobs' queue has waited for more than ten minutes: a
# worker that runs and is stuck, one that stopped with a message still on the queue, or one that
# works and has more jobs than it gets through. A worker that stopped with the queue empty does not
# set it off: the worker is also what sends the messages, so new jobs then wait in the database and
# nothing reaches the queue; the worker's alarm in telemetry.tf, vehicle-catalog-worker-health,
# notices a stopped worker. A message whose job fails is delivered three times, two minutes apart,
# and has left the queue well before that. Like the alarm above, it emails the maintainer when it
# goes off and when it clears, and SQS reports what it watches at no charge.
resource "aws_cloudwatch_metric_alarm" "jobs_waiting" {
  alarm_name        = "vehicle-catalog-jobs-waiting"
  alarm_description = "A message has been on the jobs' queue for more than ten minutes. That is a worker that runs and is stuck, one that stopped with a message still on the queue, or one that works and has more jobs than it gets through. A worker that stopped with the queue empty does not set it off: new jobs then wait in the database and nothing reaches the queue, and the alarm vehicle-catalog-worker-health notices the worker. First, see whether the worker runs and what its log says, under the stream worker of the log group /vehicle-catalog/backend, then how many messages wait on the queue. It clears by itself once no message on the queue is that old. infra/README.md has the commands under this alarm's name."

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  namespace   = "AWS/SQS"
  metric_name = "ApproximateAgeOfOldestMessage"
  dimensions  = { QueueName = aws_sqs_queue.jobs.name }
  statistic   = "Maximum"
  period      = 60

  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 10 * 60
  # A queue that nothing has touched for hours reports nothing, and holds nothing.
  treat_missing_data = "notBreaching"
}

# What working the jobs off takes, on these two queues and on no other.
resource "aws_iam_role_policy" "host_jobs" {
  name = "jobs"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
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
  })
}
