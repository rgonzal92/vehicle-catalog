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
# its message was delivered three times and its work failed each time. The worker takes a job's
# message out of that queue once the job has been retried and done, so the alarm is quiet again
# when no job is failed. It tells no one: it is there to be looked at, as the one on the backend's
# health is. SQS reports the queue's depth at no charge.
resource "aws_cloudwatch_metric_alarm" "jobs_failed" {
  alarm_name        = "vehicle-catalog-jobs-failed"
  alarm_description = "A job has failed for good: its message is in the dead-letter queue."

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

# Goes off when the oldest message on the jobs' queue has waited for more than ten minutes, which
# is what a worker that is stopped or stuck leads to. A message whose job fails is delivered three
# times, two minutes apart, and has left the queue well before that. Like the alarm above, it tells
# no one, and SQS reports what it watches at no charge.
resource "aws_cloudwatch_metric_alarm" "jobs_waiting" {
  alarm_name        = "vehicle-catalog-jobs-waiting"
  alarm_description = "The oldest message on the jobs' queue has waited for more than ten minutes: the worker is stopped or stuck."

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
