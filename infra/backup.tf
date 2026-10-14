# Where the backups of the database are kept. The host writes one every night, half an hour before
# the demo reset, so a backup holds the day's work. infra/README.md says how one is put back.

resource "aws_s3_bucket" "backups" {
  bucket = "rgonz-vehicle-catalog-backups"
}

# No setting on the bucket or on a file in it can make either public.
resource "aws_s3_bucket_public_access_block" "backups" {
  bucket = aws_s3_bucket.backups.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# A backup written over another of the same name leaves the earlier one in place.
resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id

  versioning_configuration {
    status = "Enabled"
  }
}

# A backup goes after thirty days, and so does one that another was written over.
resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    id     = "keep-thirty-days"
    status = "Enabled"

    filter {}

    expiration {
      days = 30
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.backups]
}

# The host writes backups and does nothing else with them: it reads none, lists none, and removes
# none. Whoever gets onto the host cannot take the backups or do away with them.
resource "aws_iam_role_policy" "host_back_up" {
  name = "back-up"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "WriteBackups"
      Effect   = "Allow"
      Action   = ["s3:PutObject"]
      Resource = "${aws_s3_bucket.backups.arn}/*"
    }]
  })
}

# Goes off when no hour of the last 26 has a backup's size in it, which is a night without a backup:
# deploy/backup.sh reports each backup's size once the bucket has it, and the host makes one every
# night at 02:30 UTC. The 26 hours move with every look the alarm takes, so it goes off about 26
# hours after the last size arrived: at about 04:30 UTC after a night without one, and a backup
# that runs an hour late sets nothing off. An hour with no size is a zero, which FILL was seen on
# 2026-10-14 to give for every hour of a window in which no size had arrived at all; the alarm
# goes off when all 26 are zero. It emails the maintainer through the topic in telemetry.tf when it
# goes off and when it clears, as every alarm does. A backup started by hand on the host clears it.
resource "aws_cloudwatch_metric_alarm" "backup_missing" {
  alarm_name        = "vehicle-catalog-backup-missing"
  alarm_description = "No backup of the database has reached the bucket in the last 26 hours: the size each backup reports, backup.size, was missing from every hour of them. The host writes a backup every night at 02:30 UTC, so a night without one sets this off at about 04:30 UTC, and a backup that runs an hour late sets nothing off. First, on the host, the journal of vehicle-catalog-backup.service and the timer that starts it. A journal that says the database is backed up and its size was not reported means the backup is in the bucket and only the report failed. One that says the database was not backed up means it is not: look at the database's container and at the room on the disk, and start the unit by hand once the cause is put right. It clears by itself once a backup has reported its size, one started by hand among them; how soon after that was not seen. infra/README.md has the commands under this alarm's name."

  alarm_actions = [aws_sns_topic.alarms.arn]
  ok_actions    = [aws_sns_topic.alarms.arn]

  evaluation_periods  = 26
  datapoints_to_alarm = 26
  comparison_operator = "LessThanOrEqualToThreshold"
  threshold           = 0
  # FILL gives every hour a value, so none is missing; one that were would count as an hour without
  # a backup, as the zero does.
  treat_missing_data = "breaching"

  metric_query {
    id          = "size"
    return_data = false

    metric {
      namespace   = local.telemetry_namespace
      metric_name = "backup.size"
      period      = 3600
      stat        = "Maximum"
    }
  }

  metric_query {
    id          = "hours"
    expression  = "FILL(size, 0)"
    label       = "the largest backup of the hour, in bytes, or 0"
    return_data = true
  }
}
