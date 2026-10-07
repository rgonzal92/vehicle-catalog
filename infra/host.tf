# The host: one machine that answers everything under /api. Nothing but CloudFront reaches its
# HTTPS port, and it answers only what carries a secret that this distribution alone sends.
# docs/adr/0007 says why it is built this way.

locals {
  # The name the distribution reaches the host by, and that the host gets its certificate for.
  # Visitors use the site's name, not this one.
  host_name = "origin-catalog.rgonz.dev"

  origin_secret_name = "/vehicle-catalog/origin-secret"
}

data "aws_region" "current" {}

# A subnet the account already has, in which a machine gets a public address.
data "aws_subnet" "host" {
  availability_zone = "${data.aws_region.current.region}a"
  default_for_az    = true
}

# The addresses CloudFront reaches origins from, which AWS keeps in a list of its own.
data "aws_ec2_managed_prefix_lists" "cloudfront" {
  filter {
    name   = "prefix-list-name"
    values = ["com.amazonaws.global.cloudfront.origin-facing"]
  }
}

resource "aws_security_group" "host" {
  name        = "vehicle-catalog-host"
  description = "What reaches the host: CloudFront over HTTPS, and anyone on port 80, where only the certificate authority is answered."
  vpc_id      = data.aws_subnet.host.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "host_https" {
  security_group_id = aws_security_group.host.id
  description       = "HTTPS, from CloudFront alone"
  prefix_list_id    = one(data.aws_ec2_managed_prefix_lists.cloudfront.ids)
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# Let's Encrypt checks that the host holds its name by asking it on port 80, from addresses it
# does not publish. Caddy listens there only during such a check.
resource "aws_vpc_security_group_ingress_rule" "host_certificate_check" {
  security_group_id = aws_security_group.host.id
  description       = "Port 80, where the certificate authority checks"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

# The host fetches its software and its certificate and reports to Systems Manager.
resource "aws_vpc_security_group_egress_rule" "host" {
  security_group_id = aws_security_group.host.id
  description       = "Anything the host asks for"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# Those rules are all there are. An apply removes any other rule given to the group, in a file
# here or by hand.
resource "aws_vpc_security_group_rules_exclusive" "host" {
  security_group_id = aws_security_group.host.id
  ingress_rule_ids = [
    aws_vpc_security_group_ingress_rule.host_https.id,
    aws_vpc_security_group_ingress_rule.host_certificate_check.id,
  ]
  egress_rule_ids = [aws_vpc_security_group_egress_rule.host.id]
}

# What the host may do in AWS. There is no SSH: a shell on the host is opened through Systems
# Manager, which this role lets reach it.
resource "aws_iam_role" "host" {
  name        = "vehicle-catalog-host"
  description = "What the host may do in AWS."

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

# Besides letting Systems Manager reach the host, this lets the host read Parameter Store, which
# is where its secrets are.
# ponytail: the policy is AWS's own and lets the host read every parameter the account has. The
# account holds this app's parameters and no others; one that the host should not read calls for
# a policy written here.
resource "aws_iam_role_policy_attachment" "host_systems_manager" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# That policy and the one that lets the host fetch the backend's images are all its role has. An
# apply removes any other policy given to it, in a file here or by hand.
resource "aws_iam_role_policies_exclusive" "host" {
  role_name    = aws_iam_role.host.name
  policy_names = [aws_iam_role_policy.host_fetch_backend.name]
}

resource "aws_iam_role_policy_attachments_exclusive" "host" {
  role_name   = aws_iam_role.host.name
  policy_arns = [aws_iam_role_policy_attachment.host_systems_manager.policy_arn]
}

resource "aws_iam_instance_profile" "host" {
  name = "vehicle-catalog-host"
  role = aws_iam_role.host.name
}

resource "aws_instance" "host" {
  # Amazon Linux 2023 for arm64: al2023-ami-2023.12.20260930.0-kernel-6.18-arm64.
  ami                    = "ami-065b1b834d2a83a7a"
  instance_type          = "t4g.small"
  subnet_id              = data.aws_subnet.host.id
  vpc_security_group_ids = [aws_security_group.host.id]
  iam_instance_profile   = aws_iam_instance_profile.host.name

  # The role's credentials are given only to a caller on the host itself that first asked for a
  # token, which a request passed on by something on the host cannot do.
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  # The host pays for CPU with time it saved while idle. Once that is used up it slows to a fifth
  # of each processor, and the bill stays what it was; "unlimited" would bill the extra time.
  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  tags = {
    Name = "vehicle-catalog"
  }

  # The host reports to Systems Manager as it starts, which the policy has to be there for.
  depends_on = [aws_iam_role_policy_attachment.host_systems_manager]
}

# The host's address is its own thing, so it stays the same when the host is replaced, and the DNS
# record that names it does too. It is not given up while that record may still name it: whoever
# is given the address next could get a certificate for the host's name and be sent the secret.
resource "aws_eip" "host" {
  domain = "vpc"

  tags = {
    Name = "vehicle-catalog"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_eip_association" "host" {
  allocation_id = aws_eip.host.id
  instance_id   = aws_instance.host.id
}

# The secret the distribution sends with every request, which Caddy asks for. It is made here,
# so it is in the state, and it is in the distribution's settings; anything that reads either
# can read it.
resource "random_password" "origin_secret" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "origin_secret" {
  name        = local.origin_secret_name
  description = "The secret the distribution sends the host with every request."
  type        = "SecureString"
  value       = random_password.origin_secret.result
}

# Has the host run the stack as deploy/ has it, with the backend image released last. It runs when
# the host first reports to Systems Manager and again whenever what it runs changes, so a change to
# deploy/ or to the secret reaches the host with an apply and without a new host. The apply that
# creates this waits for the run and fails if it does. A later apply does not wait; infra/README.md
# says how to see how a run went.
resource "aws_ssm_association" "host_stack" {
  name             = "AWS-RunShellScript"
  association_name = "vehicle-catalog-host-stack"

  targets {
    key    = "InstanceIds"
    values = [aws_instance.host.id]
  }

  parameters = {
    commands = templatefile("${path.module}/templates/host_stack.sh.tftpl", {
      # Docker Compose, by the release and the checksum Docker published for its arm64 build.
      compose_version = "v5.6.0"
      compose_sha256  = "733ec76717ceb59052a9609b9dadfb523b2df8eab57a54212872d10a58078ea2"
      compose_file    = chomp(file("${path.module}/../deploy/compose.yaml"))
      caddy_file      = chomp(file("${path.module}/../deploy/Caddyfile"))
      release_script  = chomp(file("${path.module}/../deploy/release.sh"))
      secret_version  = aws_ssm_parameter.origin_secret.version
      region          = data.aws_region.current.region
    })
  }

  wait_for_success_timeout_seconds = 900

  # The stack is started with the host's fixed address already in place, where the certificate
  # authority looks for it.
  depends_on = [aws_eip_association.host]
}
