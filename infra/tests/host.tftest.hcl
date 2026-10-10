# What reaches the host, what it runs, and how the distribution sends the API's requests to it.
# Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "only_cloudfront_reaches_the_hosts_https_port" {
  assert {
    condition     = aws_vpc_security_group_ingress_rule.host_https.prefix_list_id == "pl-cloudfront"
    error_message = "Port 443 is open to the list of addresses that was looked up."
  }

  assert {
    # The stand-in answers any lookup, so what is looked up is checked here.
    condition = one(data.aws_ec2_managed_prefix_lists.cloudfront.filter) == {
      name   = "prefix-list-name"
      values = toset(["com.amazonaws.global.cloudfront.origin-facing"])
    }
    error_message = "The list looked up is the one AWS keeps of the addresses CloudFront reaches origins from."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.host_https.ip_protocol == "tcp"
      && aws_vpc_security_group_ingress_rule.host_https.from_port == 443
      && aws_vpc_security_group_ingress_rule.host_https.to_port == 443
    )
    error_message = "What is open to CloudFront is port 443 alone."
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.host_certificate_check.cidr_ipv4 == "0.0.0.0/0"
      && aws_vpc_security_group_ingress_rule.host_certificate_check.ip_protocol == "tcp"
      && aws_vpc_security_group_ingress_rule.host_certificate_check.from_port == 80
      && aws_vpc_security_group_ingress_rule.host_certificate_check.to_port == 80
    )
    error_message = "What is open to everyone is port 80 alone, where the certificate authority checks."
  }

  assert {
    condition = (
      aws_vpc_security_group_rules_exclusive.host.ingress_rule_ids == toset([
        aws_vpc_security_group_ingress_rule.host_https.id,
        aws_vpc_security_group_ingress_rule.host_certificate_check.id,
      ])
      && aws_vpc_security_group_rules_exclusive.host.egress_rule_ids == toset([
        aws_vpc_security_group_egress_rule.host.id,
      ])
    )
    error_message = "Those two are all that is let in, and an apply removes any other rule."
  }

  assert {
    # EC2 refuses a description with any other character in it, and does so only at an apply.
    condition = alltrue([
      for description in [
        aws_security_group.host.description,
        aws_vpc_security_group_ingress_rule.host_https.description,
        aws_vpc_security_group_ingress_rule.host_certificate_check.description,
        aws_vpc_security_group_egress_rule.host.description,
      ] : can(regex("^[a-zA-Z0-9 ._:/()#,@\\[\\]+=&;{}!$*-]{1,255}$", description))
    ])
    error_message = "A description of the group or of a rule uses only the characters EC2 takes."
  }
}

run "the_host_is_reached_for_a_shell_through_systems_manager_alone" {
  assert {
    condition = (
      aws_instance.host.metadata_options[0].http_tokens == "required"
      && aws_instance.host.metadata_options[0].http_put_response_hop_limit == 2
    )
    error_message = "The role's credentials are given only to a caller that first asked for a token, on the host or in a container on it."
  }

  assert {
    condition = aws_iam_role_policy_attachments_exclusive.host.policy_arns == toset([
      "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
    ])
    error_message = "The host's role has the policy that lets Systems Manager reach it, and no other of AWS's."
  }

  assert {
    condition     = aws_iam_role_policies_exclusive.host.policy_names == toset(["administer-roles", "back-up", "documents", "exports", "fetch-backend", "jobs", "own-parameters", "report"])
    error_message = "The host's role has the policy that fetches the backend's images, the one that changes roles, the one that reports to CloudWatch, the one that writes backups, the one that works off the jobs, the one that keeps exported spreadsheets, the one that keeps uploaded documents, the one that holds it to its own parameters, and no other of its own."
  }
}

run "the_host_reads_its_own_parameters_and_no_other" {
  assert {
    condition = (
      length(jsondecode(aws_iam_role_policy.host_own_parameters.policy).Statement) == 1
      && alltrue([
        for statement in jsondecode(aws_iam_role_policy.host_own_parameters.policy).Statement :
        statement.Effect == "Deny"
        && toset(statement.Action) == toset([
          "ssm:GetParameter",
          "ssm:GetParameterHistory",
          "ssm:GetParameters",
          "ssm:GetParametersByPath",
        ])
        && toset(statement.NotResource) == toset([
          for name in ["database-password", "login-client-secret", "openai-api-key", "origin-secret"] :
          "arn:aws:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter/vehicle-catalog/${name}"
        ])
      ])
    )
    error_message = "The host's role is refused every parameter but the four it reads, whatever another policy allows, and none of the four."
  }

  assert {
    # The script names a parameter where it asks for one: "$(parameter origin-secret)". No other
    # script of the host's reads Parameter Store, so none reads a parameter this test does not see.
    condition = (
      toset(flatten(regexall("\\$\\(parameter ([a-z-]+)", file("../deploy/release.sh")))) == toset(local.host_parameters)
      && length(regexall("get-parameter", join("", [
        for script in fileset("../deploy", "*.sh") : file("../deploy/${script}")
      ]))) == 1
    )
    error_message = "The release script reads the parameters the host's role may read and no other, and it is the one script that reads any. A parameter that it comes to read is added to the role's list as well."
  }
}

run "the_host_runs_the_stack_the_repository_holds" {
  assert {
    condition     = strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/Caddyfile"))
    error_message = "The host is given the Caddyfile as it is in deploy/."
  }

  assert {
    condition     = strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/compose.yaml"))
    error_message = "The host is given the Compose file as it is in deploy/."
  }

  assert {
    condition     = !strcontains(aws_ssm_association.host_stack.parameters.commands, random_password.origin_secret.result)
    error_message = "What the host is told to run does not hold the secret: the host fetches it."
  }

  assert {
    condition     = strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/release.sh"))
    error_message = "The host is given the release script as it is in deploy/."
  }

  assert {
    condition = strcontains(
      aws_ssm_association.host_stack.parameters.commands,
      "the distribution's at ${aws_ssm_parameter.origin_secret.version}, the\n# login provider's at ${aws_ssm_parameter.login_client_secret.version}.",
    )
    error_message = "What the host runs names each secret's version, so that a new secret has the host start its stack anew."
  }

  assert {
    condition = (
      aws_ssm_parameter.origin_secret.name == "/vehicle-catalog/origin-secret"
      && strcontains(file("../deploy/release.sh"), "\"$(parameter origin-secret)\"")
    )
    error_message = "The release script reads the secret from the parameter that holds it."
  }

  assert {
    condition     = !can(regex("set -[a-z]*x|xtrace", aws_ssm_association.host_stack.parameters.commands))
    error_message = "The host does not write down the commands it runs: one of them holds the secret."
  }

  assert {
    condition = (
      aws_ssm_parameter.origin_secret.type == "SecureString"
      && aws_ssm_parameter.origin_secret.value == random_password.origin_secret.result
    )
    error_message = "The secret the host fetches is the one the distribution sends, kept encrypted in Parameter Store."
  }

  assert {
    # It is written into a file of settings and into the Caddyfile, where other characters have a
    # meaning of their own.
    condition     = can(regex("^[A-Za-z0-9]{48}$", random_password.origin_secret.result))
    error_message = "The secret is 48 letters and digits."
  }
}

run "the_plan_role_reads_the_secrets_terraform_keeps_and_no_other_parameter" {
  assert {
    condition = toset(flatten([
      for statement in jsondecode(aws_iam_role_policy.plan.policy).Statement : statement.Resource
      if contains(statement.Action, "ssm:GetParameter")
    ])) == toset([aws_ssm_parameter.origin_secret.arn, aws_ssm_parameter.login_client_secret.arn])
    error_message = "The plan role reads the two parameters Terraform manages and no other."
  }
}

run "the_distribution_sends_the_api_to_the_host_and_keeps_nothing_of_it" {
  assert {
    condition = one([
      for origin in aws_cloudfront_distribution.site.origin : origin if origin.origin_id == "host"
    ]).custom_origin_config[0].origin_protocol_policy == "https-only"
    error_message = "The distribution speaks HTTPS to the host and nothing else."
  }

  assert {
    condition = one(one([
      for origin in aws_cloudfront_distribution.site.origin : origin if origin.origin_id == "host"
      ]).custom_header) == {
      name  = "X-Origin-Secret"
      value = random_password.origin_secret.result
    }
    error_message = "The distribution sends the host the secret, in the header Caddy looks at."
  }

  assert {
    condition = strcontains(file("../deploy/compose.yaml"), "ORIGIN_NAME: ${one([
      for origin in aws_cloudfront_distribution.site.origin : origin if origin.origin_id == "host"
    ]).domain_name}\n")
    error_message = "The distribution reaches the host by the name the host gets its certificate for."
  }

  assert {
    condition = (
      length(aws_cloudfront_distribution.site.ordered_cache_behavior) == 1
      && aws_cloudfront_distribution.site.ordered_cache_behavior[0].path_pattern == "/api/*"
      && aws_cloudfront_distribution.site.ordered_cache_behavior[0].target_origin_id == "host"
      && aws_cloudfront_distribution.site.ordered_cache_behavior[0].viewer_protocol_policy == "https-only"
    )
    error_message = "Everything under /api goes to the host, and is asked for over HTTPS."
  }

  assert {
    # CachingDisabled and AllViewerExceptHostHeader, two policies that AWS manages.
    condition = (
      aws_cloudfront_distribution.site.ordered_cache_behavior[0].cache_policy_id == "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
      && aws_cloudfront_distribution.site.ordered_cache_behavior[0].origin_request_policy_id == "b689b0a8-53d0-40ab-baf2-68738e2966ac"
    )
    error_message = "No answer of the API is kept, and the host is sent everything of a request but the Host header."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.site.ordered_cache_behavior[0].allowed_methods) == 7
    error_message = "The API takes every method."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.site.ordered_cache_behavior[0].function_association) == 0
    error_message = "A path of the API is never turned into the app's page."
  }
}

run "the_key_for_the_language_model_reaches_the_stack_and_is_kept_out_of_terraform" {
  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/release.sh"))
      && strcontains(file("../deploy/release.sh"), "\"$(parameter openai-api-key 2>/dev/null)\"")
      && strcontains(file("../deploy/release.sh"), "OPENAI_API_KEY=%s")
    )
    error_message = "The release script the host is given reads the key when it is there, and writes it with the stack's other secrets."
  }

  assert {
    condition = alltrue([
      for service in ["backend", "worker"] :
      yamldecode(file("../deploy/compose.yaml")).services[service].environment.OPENAI_API_KEY == "$${OPENAI_API_KEY:-}"
    ]) && strcontains(aws_ssm_association.host_stack.parameters.commands, file("../deploy/compose.yaml"))
    error_message = "The stack gives the key to the API and to the worker, and starts without one."
  }

  assert {
    # Terraform names the key's parameter once, where it lists what the host's role may read.
    condition = sum([
      for file in fileset(path.module, "*.tf") : length(regexall("openai", file(file)))
    ]) == 1 && contains(local.host_parameters, "openai-api-key") && aws_ssm_association.host_stack.name == "AWS-RunShellScript"
    error_message = "Terraform neither makes the key's parameter nor reads it, so the key is in no state."
  }
}
