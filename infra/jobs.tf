# The queue that carries the messages of the backend's jobs, from the worker that sends them to the
# worker that does them, and the queue that takes the messages no one could handle. The worker runs
# on the host beside the API; docs/adr/0011 says why it alone talks to the queue.

# Where a message goes once it has been received three times and was not deleted. It is kept
# there for fourteen days, which is as long as SQS keeps anything.
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
