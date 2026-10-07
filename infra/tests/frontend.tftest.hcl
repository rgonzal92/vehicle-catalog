# Who reads the bucket that holds the Angular build, and what the deploy role may do to publish
# one. Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "only_the_distribution_reads_the_bucket" {
  assert {
    condition     = length(jsondecode(aws_s3_bucket_policy.frontend.policy).Statement) == 1
    error_message = "The bucket is opened in one way only."
  }

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.frontend.policy).Statement[0].Principal == { Service = "cloudfront.amazonaws.com" }
    error_message = "The bucket is opened to CloudFront and to no one else."
  }

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.frontend.policy).Statement[0].Condition == { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.site.arn } }
    error_message = "Of all of CloudFront, only this distribution reads the bucket."
  }

  assert {
    condition     = toset(jsondecode(aws_s3_bucket_policy.frontend.policy).Statement[0].Action) == toset(["s3:GetObject", "s3:ListBucket"])
    error_message = "The distribution reads and lists the bucket and does nothing else to it."
  }

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.frontend.block_public_acls,
      aws_s3_bucket_public_access_block.frontend.block_public_policy,
      aws_s3_bucket_public_access_block.frontend.ignore_public_acls,
      aws_s3_bucket_public_access_block.frontend.restrict_public_buckets,
    ])
    error_message = "Nothing can make the bucket or a file in it public."
  }
}

# The distribution may list the bucket, and the bucket's own address is what lists it. Both of
# these keep that address from ever being asked for.
run "a_visitor_cannot_list_the_bucket" {
  assert {
    condition     = aws_cloudfront_distribution.site.default_root_object == "index.html"
    error_message = "The site's own address is answered with the app's page."
  }

  assert {
    condition = one([
      for association in aws_cloudfront_distribution.site.default_cache_behavior[0].function_association :
      association.function_arn if association.event_type == "viewer-request"
    ]) == aws_cloudfront_function.app_routes.arn
    error_message = "Every request passes the function that turns a path without a file into the app's page."
  }
}

run "the_deploy_role_publishes_and_nothing_more" {
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.deploy_frontend.policy).Statement :
      statement.Action == ["cloudfront:ListDistributions"] ? statement.Resource == "*" : contains([
        aws_s3_bucket.frontend.arn,
        "${aws_s3_bucket.frontend.arn}/*",
        aws_cloudfront_distribution.site.arn,
      ], statement.Resource)
    ])
    error_message = "Only finding the distribution reaches beyond this bucket and this distribution."
  }

  assert {
    condition     = aws_iam_role_policies_exclusive.deploy.policy_names == toset(["publish-frontend", "release-backend"])
    error_message = "The deploy role has the policy that publishes the site, the one that releases the backend, and no other."
  }

  assert {
    condition     = length(aws_iam_role_policy_attachments_exclusive.deploy.policy_arns) == 0
    error_message = "The deploy role has no managed policy."
  }

  assert {
    condition = toset(flatten([
      for statement in jsondecode(aws_iam_role_policy.deploy_frontend.policy).Statement : statement.Action
      ])) == toset([
      "cloudfront:CreateInvalidation", "cloudfront:ListDistributions",
      "s3:DeleteObject", "s3:ListBucket", "s3:PutObject",
    ])
    error_message = "Publishing a build takes these five actions and no other."
  }
}
