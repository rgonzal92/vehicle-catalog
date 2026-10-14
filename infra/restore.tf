# The clean machine a backup is put back on: one that holds nothing else of the app's, on which
# deploy/restore.sh starts an empty database, puts a backup back into it, and starts the API on it.
# It exists only while var.restore_machine is on, which it is for the plan that makes it and the
# hours of the restore; the plan after, with the switch off, removes everything here. With the
# switch off nothing here is planned, so no pull request's plan comment says anything of it.
#
# It is the host's machine but for six things: another Name tag, which keeps the pipeline's
# release away; a security group that lets nothing in; no fixed address; a hop limit of 1, so that
# its role's credentials reach the machine itself and no container on it; its processor read every
# minute; and it ends itself three hours after its first start, whatever becomes of the sitting.

resource "aws_security_group" "restore" {
  count = var.restore_machine ? 1 : 0

  name        = "vehicle-catalog-restore"
  description = "What reaches the machine a backup is put back on: nothing."
  vpc_id      = data.aws_subnet.host.vpc_id
}

# The machine fetches Docker, a backup, and the backend's image, and reports to Systems Manager.
resource "aws_vpc_security_group_egress_rule" "restore" {
  count = var.restore_machine ? 1 : 0

  security_group_id = aws_security_group.restore[0].id
  description       = "Anything the machine asks for"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# No rule lets anything in, and an apply removes any rule given to the group by hand.
resource "aws_vpc_security_group_rules_exclusive" "restore" {
  count = var.restore_machine ? 1 : 0

  security_group_id = aws_security_group.restore[0].id
  ingress_rule_ids  = []
  egress_rule_ids   = [aws_vpc_security_group_egress_rule.restore[0].id]
}

# What the machine may do in AWS: be reached by Systems Manager, fetch a backup, and fetch the
# backend's images. Nothing for a parameter, the queue, another bucket, the login provider, a log,
# or a metric.
resource "aws_iam_role" "restore" {
  count = var.restore_machine ? 1 : 0

  name        = "vehicle-catalog-restore"
  description = "What the machine a backup is put back on may do in AWS."

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

# AWS's own policy, which lets Systems Manager reach the machine and would let it read every
# parameter the account has, which the policy after it takes back.
resource "aws_iam_role_policy_attachment" "restore_systems_manager" {
  count = var.restore_machine ? 1 : 0

  role       = aws_iam_role.restore[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# The machine reads none of the app's secrets: it has its own database, with a password made up
# on the spot, and an API told of nothing live.
resource "aws_iam_role_policy" "restore_no_parameters" {
  count = var.restore_machine ? 1 : 0

  name = "no-parameters"
  role = aws_iam_role.restore[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "ReadNoParameter"
      Effect = "Deny"
      Action = [
        "ssm:GetParameter",
        "ssm:GetParameterHistory",
        "ssm:GetParameters",
        "ssm:GetParametersByPath",
      ]
      Resource = "*"
    }]
  })
}

# The one role Terraform makes that may read a backup. It lists none, writes none, and removes
# none, and it exists only while the switch is on.
resource "aws_iam_role_policy" "restore_fetch_backups" {
  count = var.restore_machine ? 1 : 0

  name = "fetch-backups"
  role = aws_iam_role.restore[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "FetchBackups"
      Effect   = "Allow"
      Action   = ["s3:GetObject"]
      Resource = "${aws_s3_bucket.backups.arn}/*"
    }]
  })
}

# The machine fetches the backend's images as the host does, so that the API it starts is the one
# the host runs.
resource "aws_iam_role_policy" "restore_fetch_backend" {
  count = var.restore_machine ? 1 : 0

  name   = "fetch-backend"
  role   = aws_iam_role.restore[0].id
  policy = aws_iam_role_policy.host_fetch_backend.policy
}

# Those three are all its policies, and that one of AWS's. An apply removes any other given to it.
resource "aws_iam_role_policies_exclusive" "restore" {
  count = var.restore_machine ? 1 : 0

  role_name = aws_iam_role.restore[0].name
  policy_names = [
    aws_iam_role_policy.restore_no_parameters[0].name,
    aws_iam_role_policy.restore_fetch_backups[0].name,
    aws_iam_role_policy.restore_fetch_backend[0].name,
  ]
}

resource "aws_iam_role_policy_attachments_exclusive" "restore" {
  count = var.restore_machine ? 1 : 0

  role_name   = aws_iam_role.restore[0].name
  policy_arns = [aws_iam_role_policy_attachment.restore_systems_manager[0].policy_arn]
}

resource "aws_iam_instance_profile" "restore" {
  count = var.restore_machine ? 1 : 0

  name = "vehicle-catalog-restore"
  role = aws_iam_role.restore[0].name
}

resource "aws_instance" "restore" {
  count = var.restore_machine ? 1 : 0

  # The host's image and size, so that what is timed here is what a new host would take.
  ami                    = aws_instance.host.ami
  instance_type          = aws_instance.host.instance_type
  subnet_id              = data.aws_subnet.host.id
  vpc_security_group_ids = [aws_security_group.restore[0].id]
  iam_instance_profile   = aws_iam_instance_profile.restore[0].name

  # The role's credentials are given only to a caller that first asked for a token, and only on
  # the machine itself: one hop, where the host allows two for its backend's container.
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  # Standard credits, as the host has: a machine given none at its launch may run slowed from its
  # first minute, and that is what is timed.
  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_type           = aws_instance.host.root_block_device[0].volume_type
    volume_size           = aws_instance.host.root_block_device[0].volume_size
    encrypted             = true
    delete_on_termination = true
  }

  # The processor is read every minute, for the picture of the restore.
  monitoring = true

  # The machine ends itself three hours after its first start, set at its launch so that it holds
  # even if Systems Manager never reaches it. A shutdown from inside ends the instance, where AWS
  # would otherwise only stop it and keep its disk. It is a length of time and no hour of the
  # clock, so that a plan made while the machine exists says no changes.
  user_data                            = "#!/bin/bash\nshutdown -h +180\n"
  instance_initiated_shutdown_behavior = "terminate"

  tags = {
    Name = "vehicle-catalog-restore"
  }

  # The machine reports to Systems Manager as it starts, which the policy has to be there for.
  depends_on = [aws_iam_role_policy_attachment.restore_systems_manager]
}

# Has the machine set itself up to put a backup back: Docker, deploy/restore.sh as the repository
# has it, the database's image as deploy/compose.yaml names it, the bucket, and the registry. The
# apply is given fifteen minutes for this, yet on 2026-10-14 it ended four seconds after making
# the association, before the machine had reported in, so infra/README.md says how the set-up is
# waited for. A command to the machine then names a backup and a commit, sent as the README says.
resource "aws_ssm_association" "restore_setup" {
  count = var.restore_machine ? 1 : 0

  name             = "AWS-RunShellScript"
  association_name = "vehicle-catalog-restore-setup"

  targets {
    key    = "InstanceIds"
    values = [aws_instance.restore[0].id]
  }

  parameters = {
    commands = templatefile("${path.module}/templates/restore_machine.sh.tftpl", {
      restore_script     = chomp(file("${path.module}/../deploy/restore.sh"))
      database_image     = yamldecode(file("${path.module}/../deploy/compose.yaml")).services.db.image
      backup_bucket      = aws_s3_bucket.backups.bucket
      registry           = local.registry
      backend_repository = aws_ecr_repository.backend.repository_url
      region             = data.aws_region.current.region
    })
  }

  wait_for_success_timeout_seconds = 900

  depends_on = [
    aws_iam_role_policy.restore_fetch_backups,
    aws_iam_role_policy.restore_fetch_backend,
    aws_iam_role_policy.restore_no_parameters,
  ]
}
