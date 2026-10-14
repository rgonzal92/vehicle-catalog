# Where the backups of the database are kept, how long, and what the host may do with them.
# Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "the_backups_are_private_and_kept_for_thirty_days" {
  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.backups.block_public_acls,
      aws_s3_bucket_public_access_block.backups.block_public_policy,
      aws_s3_bucket_public_access_block.backups.ignore_public_acls,
      aws_s3_bucket_public_access_block.backups.restrict_public_buckets,
    ])
    error_message = "Nothing can make the bucket or a backup in it public."
  }

  assert {
    condition     = aws_s3_bucket_versioning.backups.versioning_configuration[0].status == "Enabled"
    error_message = "A backup written over another leaves the earlier one in place."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.backups.rule) == 1
      && aws_s3_bucket_lifecycle_configuration.backups.rule[0].status == "Enabled"
      && aws_s3_bucket_lifecycle_configuration.backups.rule[0].expiration[0].days == 30
      && aws_s3_bucket_lifecycle_configuration.backups.rule[0].noncurrent_version_expiration[0].noncurrent_days == 30
    )
    error_message = "A backup goes after thirty days, and so does one that another was written over."
  }
}

run "the_host_writes_backups_and_does_nothing_else_with_them" {
  assert {
    condition = jsondecode(aws_iam_role_policy.host_back_up.policy).Statement == [{
      Sid      = "WriteBackups"
      Effect   = "Allow"
      Action   = ["s3:PutObject"]
      Resource = "${aws_s3_bucket.backups.arn}/*"
    }]
    error_message = "The host writes into the backups bucket, and neither reads, lists, nor removes what is there."
  }
}

run "the_host_backs_the_database_up_before_the_demo_reset" {
  assert {
    condition     = strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/backup.sh"))
    error_message = "The host is given the backup script as it is in deploy/."
  }

  assert {
    condition = strcontains(
      aws_ssm_association.host_stack.parameters.commands,
      "ExecStart=/opt/vehicle-catalog/backup.sh ${aws_s3_bucket.backups.bucket}\n",
    )
    error_message = "The backup is sent to the bucket that is made for it."
  }

  assert {
    # The demo reset is at 03:00 UTC, which backend/src/main/resources/application.properties sets.
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, "OnCalendar=*-*-* 02:30:00 UTC\n")
      && strcontains(file("../backend/src/main/resources/application.properties"), "app.demo-reset.cron=0 0 3 * * *")
    )
    error_message = "The backup is made half an hour before the demo reset, so it holds the day's work."
  }
}

run "each_backup_reports_its_size_where_the_host_may_publish" {
  assert {
    # The host's role publishes under the namespace the agent's file names and under no other, so
    # a size reported under another name would be refused.
    condition = (
      regex("aws cloudwatch put-metric-data --namespace ([^ ]+) --metric-name backup\\.size --value ", file("../deploy/backup.sh"))[0] == jsondecode(file("../deploy/agent.json")).metrics.namespace
      && one([
        for statement in jsondecode(aws_iam_role_policy.host_report.policy).Statement : statement
        if contains(flatten([statement.Action]), "cloudwatch:PutMetricData")
      ]).Condition == { StringEquals = { "cloudwatch:namespace" = jsondecode(file("../deploy/agent.json")).metrics.namespace } }
    )
    error_message = "The backup script reports a backup's size under the namespace the agent's file names, which is the one the host may publish under."
  }
}

run "a_night_without_a_backup_sets_off_an_alarm" {
  assert {
    # The size is named outright, in the namespace the script reports under, which is the agent's:
    # nothing adds labels to it, so no query by name is needed.
    condition = (
      one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].namespace
      == regex("aws cloudwatch put-metric-data --namespace ([^ ]+) --metric-name backup\\.size --value ", file("../deploy/backup.sh"))[0]
      && one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].namespace == jsondecode(file("../deploy/agent.json")).metrics.namespace
      && one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].metric_name == "backup.size"
      && one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].dimensions == null
      && one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).return_data == false
    )
    error_message = "The alarm watches backup.size in the namespace the script reports under, by the metric's name and nothing else."
  }

  assert {
    # An hour in which no size arrived is a zero, which FILL was seen to give for every hour of a
    # window with no value at all. The alarm goes off when all 26 hours are zero.
    condition = (
      one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].period == 3600
      && one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].stat == "Maximum"
      && [for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query.expression if query.return_data] == ["FILL(size, 0)"]
      && aws_cloudwatch_metric_alarm.backup_missing.comparison_operator == "LessThanOrEqualToThreshold"
      && aws_cloudwatch_metric_alarm.backup_missing.threshold == 0
      && aws_cloudwatch_metric_alarm.backup_missing.evaluation_periods == 26
      && aws_cloudwatch_metric_alarm.backup_missing.datapoints_to_alarm == 26
      && aws_cloudwatch_metric_alarm.backup_missing.treat_missing_data == "breaching"
    )
    error_message = "The alarm goes off when no hour of the last 26 has a size in it: a night without a backup, and not a backup that is an hour late."
  }

  assert {
    # A backup is due every 24 hours; 26 lets one be late by an hour and still be found in the
    # window, and is within the seven days an alarm may look back over.
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, "OnCalendar=*-*-* 02:30:00 UTC\n")
      && aws_cloudwatch_metric_alarm.backup_missing.evaluation_periods * one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].period > 25 * 3600
      && aws_cloudwatch_metric_alarm.backup_missing.evaluation_periods * one([for query in aws_cloudwatch_metric_alarm.backup_missing.metric_query : query if query.id == "size"]).metric[0].period <= 7 * 24 * 3600
    )
    error_message = "The alarm's window outlasts a day by more than the hour a nightly backup may run late."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.backup_missing.alarm_actions == toset([aws_sns_topic.alarms.arn])
      && aws_cloudwatch_metric_alarm.backup_missing.ok_actions == toset([aws_sns_topic.alarms.arn])
      && aws_cloudwatch_metric_alarm.backup_missing.insufficient_data_actions == null
    )
    error_message = "The alarm tells the topic when it goes off and when it clears, and nothing for want of data."
  }
}
