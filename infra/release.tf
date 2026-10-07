# How a backend image reaches the host. The pipeline builds one for every commit that reaches main,
# keeps it here under that commit's name, and has the host release it: fetch it, start it, and go
# back to the image before it if it does not come up healthy. docs/adr/0008 says why the pipeline
# names an image and does nothing else on the host.

data "aws_caller_identity" "current" {}

resource "aws_ecr_repository" "backend" {
  name = "vehicle-catalog-backend"

  # A commit's image is built once, and its name never comes to stand for another.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last ten images."
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

# What the pipeline can have the host do: release the image of one commit. The commit's name is all
# it passes, and nothing but forty hexadecimal digits is taken for one, since Systems Manager
# writes it into the commands as it is.
resource "aws_ssm_document" "release" {
  name            = "vehicle-catalog-release"
  document_type   = "Command"
  document_format = "YAML"

  content = yamlencode({
    schemaVersion = "2.2"
    description   = "Releases the backend image of one commit to the host, and goes back to the image before it if it does not come up healthy."
    parameters = {
      ImageTag = {
        type           = "String"
        description    = "The commit the image was built from."
        allowedPattern = "^[0-9a-f]{40}$"
      }
    }
    mainSteps = [{
      name   = "release"
      action = "aws:runShellScript"
      inputs = {
        timeoutSeconds = "900"
        runCommand = [
          "set -euo pipefail",
          "export AWS_DEFAULT_REGION=${data.aws_region.current.region}",
          # The host signs in to the registry for the length of this run.
          "trap 'docker logout ${local.registry} >/dev/null' EXIT",
          "aws ecr get-login-password | docker login --username AWS --password-stdin ${local.registry}",
          "/opt/vehicle-catalog/release.sh ${aws_ecr_repository.backend.repository_url}:{{ ImageTag }}",
        ]
      }
    }]
  })
}

locals {
  registry = split("/", aws_ecr_repository.backend.repository_url)[0]
}

# The host fetches the backend's images and no others.
resource "aws_iam_role_policy" "host_fetch_backend" {
  name = "fetch-backend"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Signing in to the registry is not something a policy can limit to one repository.
        Sid      = "SignInToTheRegistry"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "FetchTheBackendsImages"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
        ]
        Resource = aws_ecr_repository.backend.arn
      },
    ]
  })
}

# What releasing the backend takes: putting an image in the repository, and having the host run
# the one document above.
resource "aws_iam_role_policy" "deploy_backend" {
  name = "release-backend"
  role = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "SignInToTheRegistry"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "PutAnImageInTheRepository"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
        ]
        Resource = aws_ecr_repository.backend.arn
      },
      {
        Sid      = "RunTheRelease"
        Effect   = "Allow"
        Action   = ["ssm:SendCommand"]
        Resource = aws_ssm_document.release.arn
      },
      {
        # The pipeline names the host by its tag, so it is by that tag that the host is the one
        # machine the document may be run on.
        Sid      = "OnTheHost"
        Effect   = "Allow"
        Action   = ["ssm:SendCommand"]
        Resource = "arn:aws:ec2:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:instance/*"
        Condition = {
          StringEquals = { "ssm:resourceTag/Name" = aws_instance.host.tags.Name }
        }
      },
      {
        # How the release went, which is not something a policy can limit to one command.
        Sid      = "FollowTheRelease"
        Effect   = "Allow"
        Action   = ["ssm:ListCommandInvocations"]
        Resource = "*"
      },
    ]
  })
}
