# Where the backend's images are kept, what the pipeline can have the host do, and what each role
# may do for a release. Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "a_commits_image_is_kept_under_its_name_for_a_while" {
  assert {
    condition     = aws_ecr_repository.backend.image_tag_mutability == "IMMUTABLE"
    error_message = "A commit's name never comes to stand for another image."
  }

  assert {
    condition     = aws_ecr_repository.backend.image_scanning_configuration[0].scan_on_push
    error_message = "An image is scanned when it arrives."
  }

  assert {
    condition = jsondecode(aws_ecr_lifecycle_policy.backend.policy).rules == [{
      rulePriority = 1
      description  = "Keep the last ten images."
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
    error_message = "The last ten images are kept and older ones go."
  }
}

run "the_pipeline_names_a_commit_and_nothing_else" {
  assert {
    condition     = keys(yamldecode(aws_ssm_document.release.content).parameters) == ["ImageTag"]
    error_message = "The document takes the commit and nothing else."
  }

  assert {
    condition     = yamldecode(aws_ssm_document.release.content).parameters.ImageTag.allowedPattern == "^[0-9a-f]{40}$"
    error_message = "Nothing but forty hexadecimal digits is taken for a commit."
  }

  assert {
    # Systems Manager writes what it is passed into the commands, wherever two braces open.
    condition = length(regexall("\\{\\{", join("\n", yamldecode(aws_ssm_document.release.content).mainSteps[0].inputs.runCommand))) == 1 && endswith(
      yamldecode(aws_ssm_document.release.content).mainSteps[0].inputs.runCommand[4],
      "/opt/vehicle-catalog/release.sh ${aws_ecr_repository.backend.repository_url}:{{ ImageTag }}",
    )
    error_message = "The commit is written into the commands once, as the tag of an image in the backend's repository."
  }

  assert {
    condition     = length(yamldecode(aws_ssm_document.release.content).mainSteps) == 1 && yamldecode(aws_ssm_document.release.content).mainSteps[0].inputs.runCommand[0] == "set -euo pipefail"
    error_message = "The release is one step, which stops at the first command that fails."
  }
}

run "the_host_fetches_the_backends_images_and_no_others" {
  assert {
    condition = jsondecode(aws_iam_role_policy.host_fetch_backend.policy).Statement == [
      {
        Sid      = "SignInToTheRegistry"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid      = "FetchTheBackendsImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = aws_ecr_repository.backend.arn
      },
    ]
    error_message = "The host signs in to the registry and reads the backend's repository, and that is all."
  }
}

run "the_deploy_role_releases_the_backend_and_nothing_more" {
  assert {
    condition = toset(flatten([
      for statement in jsondecode(aws_iam_role_policy.deploy_backend.policy).Statement : statement.Action
      ])) == toset([
      "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload",
      "ecr:DescribeImages", "ecr:GetAuthorizationToken", "ecr:InitiateLayerUpload", "ecr:PutImage",
      "ecr:UploadLayerPart", "ssm:ListCommandInvocations", "ssm:SendCommand",
    ])
    error_message = "Releasing the backend takes these ten actions and no other."
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.deploy_backend.policy).Statement :
      statement.Resource == aws_ecr_repository.backend.arn
      if anytrue([for action in statement.Action : startswith(action, "ecr:") && action != "ecr:GetAuthorizationToken"])
    ])
    error_message = "What the deploy role does to images, it does in the backend's repository."
  }

  assert {
    condition = [
      for statement in jsondecode(aws_iam_role_policy.deploy_backend.policy).Statement :
      statement.Resource if statement.Action == ["ssm:SendCommand"] && !can(statement.Condition)
    ] == [aws_ssm_document.release.arn]
    error_message = "The one document the deploy role may run is the release."
  }

  assert {
    condition = [
      for statement in jsondecode(aws_iam_role_policy.deploy_backend.policy).Statement :
      statement.Condition if statement.Action == ["ssm:SendCommand"] && can(statement.Condition)
      ] == [{
        StringEquals = { "ssm:resourceTag/Name" = "vehicle-catalog" }
    }]
    error_message = "The machine it may run it on is the one tagged as the host."
  }

  assert {
    condition     = aws_instance.host.tags.Name == "vehicle-catalog"
    error_message = "The host carries the tag the pipeline names it by."
  }
}
