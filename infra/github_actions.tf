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
          "budgets:ListTagsForResource",
          "budgets:ViewBudget",
          "iam:GetOpenIDConnectProvider",
          "iam:GetRole",
          "iam:GetRolePolicy",
          "iam:ListAttachedRolePolicies",
          "iam:ListRolePolicies",
        ]
        Resource = "*"
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

# Deploys from the main branch, and from nowhere else. It is allowed nothing: whatever deploys
# something grants this role what that needs.
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
