# Where the exported spreadsheets are kept, for how long, and what the host may do with them.
# Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "an_exported_spreadsheet_is_private_and_gone_after_a_day" {
  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.exports.block_public_acls,
      aws_s3_bucket_public_access_block.exports.block_public_policy,
      aws_s3_bucket_public_access_block.exports.ignore_public_acls,
      aws_s3_bucket_public_access_block.exports.restrict_public_buckets,
    ])
    error_message = "Nothing can make the bucket or a file in it public."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.exports.rule) == 1
      && aws_s3_bucket_lifecycle_configuration.exports.rule[0].status == "Enabled"
      && aws_s3_bucket_lifecycle_configuration.exports.rule[0].expiration[0].days == 1
    )
    error_message = "A file is gone a day after it was written."
  }
}

run "the_host_keeps_the_spreadsheets_and_does_nothing_else_with_the_bucket" {
  assert {
    condition = jsondecode(aws_iam_role_policy.host_exports.policy).Statement == [
      {
        Sid      = "KeepExportedSpreadsheets"
        Effect   = "Allow"
        Action   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.exports.arn}/*"
      },
      {
        Sid      = "ListThemToEmptyTheBucket"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.exports.arn
      },
    ]
    error_message = "The host writes, reads, deletes, and lists the files in the bucket, and does nothing else with it or with any other."
  }

  assert {
    condition     = contains(aws_iam_role_policies_exclusive.host.policy_names, aws_iam_role_policy.host_exports.name)
    error_message = "The policy is among the ones the host's role is held to."
  }

  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, "\nEXPORTS_BUCKET=${aws_s3_bucket.exports.bucket}\n")
      && strcontains(aws_ssm_association.host_stack.parameters.commands, "\nAWS_REGION=${data.aws_region.current.region}\n")
      && strcontains(file("../backend/src/main/resources/application.properties"), "app.exports.bucket=$${EXPORTS_BUCKET:}\n")
    )
    error_message = "The host is told which bucket keeps the spreadsheets and in which region, and the backend reads it."
  }
}

run "the_pipeline_plans_with_the_bucket_in_place" {
  assert {
    condition = contains(one([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if statement.Sid == "ReadTheBuckets"
    ]).Resource, aws_s3_bucket.exports.arn)
    error_message = "The plan role reads how the bucket is set up, which a plan compares it with."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      !contains(statement.Action, "s3:GetObject") || !strcontains(jsonencode(statement.Resource), aws_s3_bucket.exports.bucket)
    ])
    error_message = "The plan role reads no spreadsheet."
  }
}
