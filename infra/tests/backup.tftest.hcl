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
