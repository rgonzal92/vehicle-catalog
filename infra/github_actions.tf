# GitHub Actions reaches AWS without stored keys. A workflow run presents a token signed by GitHub,
# and AWS exchanges it for the short-lived credentials of a role that trusts that token's subject.

locals {
  # How the subject of every token for this repository's workflows begins. It carries the ids of
  # the owner and the repository beside their names, so that another account or repository that
  # later takes one of those names is not trusted.
  github_subject = "repo:rgonzal92@25215651/vehicle-catalog@1400933585"

  # Where the state is kept, as in backend.tf, which cannot refer to a value.
  state_bucket = "rgonz-vehicle-catalog-terraform-state"
  state_key    = "terraform.tfstate"
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# Shows a plan on pull requests. Its subject is the one GitHub gives this repository's pull
# requests, from a branch and from a fork alike. What keeps a fork out is GitHub, which gives a
# fork's pull request no permission to ask for a token while the repository's settings send forks
# no write tokens, and they send none.
resource "aws_iam_role" "plan" {
  name        = "vehicle-catalog-plan"
  description = "Reads what Terraform manages, to show a plan on a pull request."

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "${local.github_subject}:pull_request"
        }
      }
    }]
  })
}

# A plan compares what is written here with what exists, so the role reads what is managed and
# does nothing else. Whatever adds a kind of resource adds the actions that read it to this policy,
# where tests/github_actions.tftest.hcl fails on one that is not a read.
resource "aws_iam_role_policy" "plan" {
  name = "read"
  role = aws_iam_role.plan.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ReadWhatIsManaged"
        Effect = "Allow"
        Action = [
          "acm:DescribeCertificate",
          "acm:ListTagsForCertificate",
          "budgets:ListTagsForResource",
          "budgets:ViewBudget",
          "cloudfront:DescribeFunction",
          "cloudfront:GetDistribution",
          "cloudfront:GetFunction",
          "cloudfront:GetOriginAccessControl",
          "cloudfront:ListTagsForResource",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:GetDashboard",
          "cloudwatch:ListTagsForResource",
          "cognito-idp:AdminGetUser",
          "cognito-idp:AdminListGroupsForUser",
          "cognito-idp:DescribeManagedLoginBranding",
          "cognito-idp:DescribeManagedLoginBrandingByClient",
          "cognito-idp:DescribeUserPool",
          "cognito-idp:DescribeUserPoolClient",
          "cognito-idp:DescribeUserPoolDomain",
          "cognito-idp:GetGroup",
          "cognito-idp:GetUserPoolMfaConfig",
          "cognito-idp:ListUserPoolClients",
          "ec2:DescribeAddresses",
          "ec2:DescribeAddressesAttribute",
          "ec2:DescribeInstanceAttribute",
          "ec2:DescribeInstanceCreditSpecifications",
          "ec2:DescribeInstanceTypes",
          "ec2:DescribeInstances",
          "ec2:DescribeManagedPrefixLists",
          "ec2:DescribeSecurityGroupRules",
          "ec2:DescribeSecurityGroups",
          "ec2:DescribeSubnets",
          "ec2:DescribeTags",
          "ec2:DescribeVolumes",
          "ec2:DescribeVpcs",
          "ecr:DescribeRepositories",
          "ecr:GetLifecyclePolicy",
          "ecr:ListTagsForResource",
          "iam:GetInstanceProfile",
          "iam:GetOpenIDConnectProvider",
          "iam:GetRole",
          "iam:GetRolePolicy",
          "iam:ListAttachedRolePolicies",
          "iam:ListRolePolicies",
          "logs:DescribeLogGroups",
          "logs:DescribeResourcePolicies",
          "logs:ListTagsForResource",
          "ssm:DescribeAssociation",
          "ssm:DescribeDocument",
          "ssm:DescribeDocumentPermission",
          "ssm:DescribeParameters",
          "ssm:GetDocument",
          "ssm:ListTagsForResource",
          "xray:GetTraceSegmentDestination",
        ]
        Resource = "*"
      },
      {
        # Terraform compares each secret it keeps with the one in Parameter Store, so the role
        # reads those parameters and no other. The secrets are in the state as well, which the
        # role also reads.
        Sid    = "ReadTheSecretsTerraformKeeps"
        Effect = "Allow"
        Action = ["ssm:GetParameter"]
        Resource = [
          aws_ssm_parameter.origin_secret.arn,
          aws_ssm_parameter.login_client_secret.arn,
        ]
      },
      {
        # How each bucket is set up, and that it is there. Without the listing, Terraform takes a
        # bucket for gone and plans to make it again. What is in a bucket is not read.
        Sid    = "ReadTheBuckets"
        Effect = "Allow"
        Action = [
          "s3:GetAccelerateConfiguration",
          "s3:GetBucketAcl",
          "s3:GetBucketCORS",
          "s3:GetBucketLogging",
          "s3:GetBucketObjectLockConfiguration",
          "s3:GetBucketPolicy",
          "s3:GetBucketPublicAccessBlock",
          "s3:GetBucketRequestPayment",
          "s3:GetBucketVersioning",
          "s3:GetBucketWebsite",
          "s3:GetEncryptionConfiguration",
          "s3:GetLifecycleConfiguration",
          "s3:GetReplicationConfiguration",
          "s3:ListBucket",
          "s3:ListTagsForResource",
        ]
        Resource = [
          aws_s3_bucket.frontend.arn,
          aws_s3_bucket.backups.arn,
          aws_s3_bucket.exports.arn,
          aws_s3_bucket.documents.arn,
        ]
      },
      {
        # How each of the jobs' two queues is set up. What is on a queue is not read.
        Sid      = "ReadTheQueues"
        Effect   = "Allow"
        Action   = ["sqs:GetQueueAttributes", "sqs:ListQueueTags"]
        Resource = [aws_sqs_queue.jobs.arn, aws_sqs_queue.jobs_failed.arn]
      },
      {
        Sid      = "ReadTheState"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "arn:aws:s3:::${local.state_bucket}/${local.state_key}"
      },
      {
        Sid      = "FindTheState"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = "arn:aws:s3:::${local.state_bucket}"
      },
    ]
  })
}

# That policy is all the plan role has. An apply removes any other policy given to it, in a file
# here or by hand.
resource "aws_iam_role_policies_exclusive" "plan" {
  role_name    = aws_iam_role.plan.name
  policy_names = [aws_iam_role_policy.plan.name]
}

resource "aws_iam_role_policy_attachments_exclusive" "plan" {
  role_name   = aws_iam_role.plan.name
  policy_arns = []
}

# Deploys from the main branch, and from nowhere else. Whatever deploys something grants this role
# what that needs, in a policy of its own beside what it deploys.
resource "aws_iam_role" "deploy" {
  name        = "vehicle-catalog-deploy"
  description = "Deploys from the main branch."

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "${local.github_subject}:ref:refs/heads/main"
        }
      }
    }]
  })
}

# Those policies are all the deploy role has. An apply removes any other policy given to it, in a
# file here or by hand.
resource "aws_iam_role_policies_exclusive" "deploy" {
  role_name    = aws_iam_role.deploy.name
  policy_names = [aws_iam_role_policy.deploy_frontend.name, aws_iam_role_policy.deploy_backend.name]
}

resource "aws_iam_role_policy_attachments_exclusive" "deploy" {
  role_name   = aws_iam_role.deploy.name
  policy_arns = []
}
