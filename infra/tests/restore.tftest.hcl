# The clean machine a backup is put back on, which exists only while the switch is on, and what
# its role may do. Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "with_the_switch_off_nothing_of_the_machine_exists" {
  assert {
    condition     = !var.restore_machine
    error_message = "The switch is off unless it is turned on for one plan."
  }

  assert {
    condition = (
      length(aws_instance.restore) == 0
      && length(aws_ssm_association.restore_setup) == 0
      && length(aws_security_group.restore) == 0
      && length(aws_vpc_security_group_egress_rule.restore) == 0
      && length(aws_vpc_security_group_rules_exclusive.restore) == 0
      && length(aws_iam_instance_profile.restore) == 0
      && length(aws_iam_role.restore) == 0
      && length(aws_iam_role_policy_attachment.restore_systems_manager) == 0
      && length(aws_iam_role_policy.restore_fetch_backups) == 0
      && length(aws_iam_role_policy.restore_fetch_backend) == 0
      && length(aws_iam_role_policy.restore_no_parameters) == 0
      && length(aws_iam_role_policies_exclusive.restore) == 0
      && length(aws_iam_role_policy_attachments_exclusive.restore) == 0
    )
    error_message = "With the switch off, neither the machine nor its role nor anything of theirs is planned."
  }
}

run "with_the_switch_on_the_machine_is_like_the_host_but_for_six_things" {
  variables {
    restore_machine = true
  }

  assert {
    condition = (
      aws_instance.restore[0].ami == aws_instance.host.ami
      && aws_instance.restore[0].instance_type == aws_instance.host.instance_type
      && aws_instance.restore[0].credit_specification[0].cpu_credits == "standard"
      && aws_instance.restore[0].root_block_device[0].volume_type == aws_instance.host.root_block_device[0].volume_type
      && aws_instance.restore[0].root_block_device[0].volume_size == aws_instance.host.root_block_device[0].volume_size
      && aws_instance.restore[0].root_block_device[0].encrypted
    )
    error_message = "The machine has the host's image, size, standard processor credits, and disk, encrypted, so that what is timed on it is what a new host would take."
  }

  assert {
    condition     = aws_instance.restore[0].tags.Name != aws_instance.host.tags.Name
    error_message = "The machine's Name tag is not the host's, which the pipeline's release goes by."
  }

  assert {
    condition = (
      length(aws_vpc_security_group_rules_exclusive.restore[0].ingress_rule_ids) == 0
      && aws_vpc_security_group_rules_exclusive.restore[0].egress_rule_ids == toset([aws_vpc_security_group_egress_rule.restore[0].id])
      && aws_instance.restore[0].vpc_security_group_ids == toset([aws_security_group.restore[0].id])
    )
    error_message = "The machine's security group lets nothing in, and an apply removes any rule given to it."
  }

  assert {
    condition     = !strcontains(file("restore.tf"), "aws_eip") && aws_instance.restore[0].tags.Name == "vehicle-catalog-restore"
    error_message = "The machine has no fixed address: the only machine with one is the host, and it is reached by its tag."
  }

  assert {
    condition = (
      aws_instance.restore[0].metadata_options[0].http_tokens == "required"
      && aws_instance.restore[0].metadata_options[0].http_put_response_hop_limit == 1
    )
    error_message = "The role's credentials are given only to a caller that first asked for a token, on the machine itself and in no container on it."
  }

  assert {
    condition     = aws_instance.restore[0].monitoring
    error_message = "The machine's processor is read every minute, where the host's is read every five."
  }

  assert {
    # EC2 refuses a description with any other character in it, and does so only at an apply.
    condition = alltrue([
      for description in [
        aws_security_group.restore[0].description,
        aws_vpc_security_group_egress_rule.restore[0].description,
      ] : can(regex("^[a-zA-Z0-9 ._:/()#,@\\[\\]+=&;{}!$*-]{1,255}$", description))
    ])
    error_message = "A description of the group or of its rule uses only the characters EC2 takes."
  }
}

run "with_the_switch_on_the_machine_ends_itself" {
  variables {
    restore_machine = true
  }

  assert {
    condition = (
      strcontains(aws_instance.restore[0].user_data, "\nshutdown -h +180\n")
      && !can(regex("timestamp\\(|plantimestamp\\(|uuid\\(", file("restore.tf")))
    )
    error_message = "What the machine is given at its launch sets a shutdown 180 minutes on, so it holds even if Systems Manager never reaches the machine; it is a length of time and no hour of the clock, so that a plan made while it exists says no changes."
  }

  assert {
    condition = (
      aws_instance.restore[0].instance_initiated_shutdown_behavior == "terminate"
      && aws_instance.restore[0].root_block_device[0].delete_on_termination
    )
    error_message = "A shutdown from inside ends the machine, and its disk is deleted with it."
  }

}

run "with_the_switch_on_the_machine_is_set_up_to_put_a_backup_back" {
  variables {
    restore_machine = true
  }

  assert {
    condition     = strcontains(aws_ssm_association.restore_setup[0].parameters.commands, file("../deploy/restore.sh"))
    error_message = "The machine is given the restore script as it is in deploy/."
  }

  assert {
    condition = strcontains(
      aws_ssm_association.restore_setup[0].parameters.commands,
      "DATABASE_IMAGE=${yamldecode(file("../deploy/compose.yaml")).services.db.image}\n",
    )
    error_message = "The machine starts the database from the image the host's stack names, and from no other."
  }

  assert {
    condition = (
      strcontains(aws_ssm_association.restore_setup[0].parameters.commands, "./restore.sh ${aws_s3_bucket.backups.bucket} ")
      && strcontains(aws_ssm_association.restore_setup[0].parameters.commands, "${aws_ecr_repository.backend.repository_url}:$2")
    )
    error_message = "A command to the machine names a backup and a commit, and the machine itself knows the bucket and the registry."
  }

  assert {
    condition = (
      aws_ssm_association.restore_setup[0].wait_for_success_timeout_seconds == 900
      && one(one(aws_ssm_association.restore_setup[0].targets).values) == aws_instance.restore[0].id
    )
    error_message = "The apply that makes the machine waits for its set-up and fails if it does."
  }

  assert {
    condition     = !can(regex("set -[a-z]*x|xtrace", aws_ssm_association.restore_setup[0].parameters.commands))
    error_message = "The machine does not write down the commands it runs."
  }
}

run "with_the_switch_on_the_role_fetches_backups_and_the_backends_images_and_nothing_else" {
  variables {
    restore_machine = true
  }

  assert {
    condition = aws_iam_role_policy_attachments_exclusive.restore[0].policy_arns == toset([
      "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
    ])
    error_message = "The role has the policy that lets Systems Manager reach the machine, and no other of AWS's."
  }

  assert {
    condition = jsondecode(aws_iam_role_policy.restore_fetch_backups[0].policy).Statement == [{
      Sid      = "FetchBackups"
      Effect   = "Allow"
      Action   = ["s3:GetObject"]
      Resource = "${aws_s3_bucket.backups.arn}/*"
    }]
    error_message = "The role fetches what the backups bucket holds, and neither lists, writes, nor removes anything there."
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.restore_fetch_backend[0].policy).Statement == jsondecode(aws_iam_role_policy.host_fetch_backend.policy).Statement
    error_message = "The role signs in to the registry and fetches the backend's images, as the host's does."
  }

  assert {
    condition = jsondecode(aws_iam_role_policy.restore_no_parameters[0].policy).Statement == [{
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
    error_message = "The role is refused every parameter, whatever another policy allows, AWS's own among them."
  }

  assert {
    condition     = aws_iam_role_policies_exclusive.restore[0].policy_names == toset(["fetch-backend", "fetch-backups", "no-parameters"])
    error_message = "Those three are all the policies the role has of its own: nothing for the queue, another bucket, the login provider, a log, or a metric."
  }

  assert {
    condition = (
      aws_iam_instance_profile.restore[0].role == aws_iam_role.restore[0].name
      && aws_instance.restore[0].iam_instance_profile == aws_iam_instance_profile.restore[0].name
      && aws_iam_role.restore[0].name != aws_iam_role.host.name
    )
    error_message = "The machine runs as that role and not as the host's."
  }
}
