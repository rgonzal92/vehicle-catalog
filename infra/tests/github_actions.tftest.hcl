# Who may take on each of the pipeline's roles, and what the plan role may do. Nothing here reaches
# AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "the_plan_role_trusts_this_repositorys_pull_requests" {
  assert {
    condition     = length(jsondecode(aws_iam_role.plan.assume_role_policy).Statement) == 1
    error_message = "The plan role is trusted in one way only."
  }

  assert {
    condition     = join(",", keys(jsondecode(aws_iam_role.plan.assume_role_policy).Statement[0].Condition)) == "StringEquals"
    error_message = "The plan role compares the token's claims exactly, without patterns."
  }

  assert {
    condition = jsondecode(aws_iam_role.plan.assume_role_policy).Statement[0].Condition.StringEquals == {
      "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
      "token.actions.githubusercontent.com:sub" = "repo:rgonzal92@25215651/vehicle-catalog@1400933585:pull_request"
    }
    error_message = "The plan role is for pull requests of this repository, named with its ids."
  }
}

run "the_deploy_role_trusts_the_main_branch" {
  assert {
    condition     = length(jsondecode(aws_iam_role.deploy.assume_role_policy).Statement) == 1
    error_message = "The deploy role is trusted in one way only."
  }

  assert {
    condition     = join(",", keys(jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition)) == "StringEquals"
    error_message = "The deploy role compares the token's claims exactly, without patterns."
  }

  assert {
    condition = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals == {
      "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
      "token.actions.githubusercontent.com:sub" = "repo:rgonzal92@25215651/vehicle-catalog@1400933585:ref:refs/heads/main"
    }
    error_message = "The deploy role is for the main branch of this repository, named with its ids."
  }
}

run "the_plan_role_only_reads" {
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement.Effect == "Allow" && alltrue([
        for action in statement.Action : can(regex("^[a-z0-9-]+:(Admin)?(Describe|Get|List|View)", action))
      ])
    ])
    error_message = "Every action the plan role is allowed starts with Describe, Get, List, or View, after Admin or not."
  }

  assert {
    condition     = aws_iam_role_policies_exclusive.plan.policy_names == toset(["read"])
    error_message = "The plan role has that one policy of its own."
  }

  assert {
    condition     = length(aws_iam_role_policy_attachments_exclusive.plan.policy_arns) == 0
    error_message = "The plan role has no managed policy."
  }
}
