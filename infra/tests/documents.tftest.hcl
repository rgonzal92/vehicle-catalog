# Where the documents that admins upload are kept, and what the host may do with them.
# Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "an_uploaded_document_is_private_and_stays_until_it_is_deleted" {
  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.documents.block_public_acls,
      aws_s3_bucket_public_access_block.documents.block_public_policy,
      aws_s3_bucket_public_access_block.documents.ignore_public_acls,
      aws_s3_bucket_public_access_block.documents.restrict_public_buckets,
    ])
    error_message = "Nothing can make the bucket or a file in it public."
  }

  assert {
    condition = alltrue([
      for rule in aws_s3_bucket_lifecycle_configuration.documents.rule :
      length(rule.expiration) == 0 && rule.abort_incomplete_multipart_upload[0].days_after_initiation == 1
    ]) && length(aws_s3_bucket_lifecycle_configuration.documents.rule) == 1
    error_message = "No file is removed for its age: only an upload that was never finished is cleared away."
  }
}

run "the_host_keeps_the_documents_and_does_nothing_else_with_the_bucket" {
  assert {
    condition = jsondecode(aws_iam_role_policy.host_documents.policy).Statement == [
      {
        Sid      = "KeepUploadedDocuments"
        Effect   = "Allow"
        Action   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.documents.arn}/*"
      },
      {
        Sid      = "ListThemToEmptyTheBucket"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.documents.arn
      },
    ]
    error_message = "The host writes, reads, deletes, and lists the files in the bucket, and does nothing else with it or with any other."
  }

  assert {
    condition     = contains(aws_iam_role_policies_exclusive.host.policy_names, aws_iam_role_policy.host_documents.name)
    error_message = "The policy is among the ones the host's role is held to."
  }

  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, "\nDOCUMENTS_BUCKET=${aws_s3_bucket.documents.bucket}\n")
      && strcontains(file("../backend/src/main/resources/application.properties"), "app.documents.bucket=$${DOCUMENTS_BUCKET:}\n")
    )
    error_message = "The host is told which bucket keeps the documents, and the backend reads it."
  }
}

run "the_pipeline_plans_with_the_bucket_in_place" {
  assert {
    condition = contains(one([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement
      if statement.Sid == "ReadTheBuckets"
    ]).Resource, aws_s3_bucket.documents.arn)
    error_message = "The plan role reads how the bucket is set up, which a plan compares it with."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      !contains(statement.Action, "s3:GetObject") || !strcontains(jsonencode(statement.Resource), aws_s3_bucket.documents.bucket)
    ])
    error_message = "The plan role reads no document."
  }
}
