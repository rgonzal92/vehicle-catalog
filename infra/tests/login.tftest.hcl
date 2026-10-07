# Who can sign in, how the backend signs them in, and what the host is told about it. Nothing here
# reaches AWS: the provider is a stand-in.
mock_provider "aws" {
  source = "./tests/mocks"
}

variables {
  budget_notification_email = "someone@example.com"
}

run "nobody_signs_up_or_resets_a_password_alone" {
  assert {
    condition     = aws_cognito_user_pool.people.admin_create_user_config[0].allow_admin_create_user_only
    error_message = "Only an administrator makes an account."
  }

  assert {
    condition = [
      for mechanism in aws_cognito_user_pool.people.account_recovery_setting[0].recovery_mechanism : mechanism.name
    ] == ["admin_only"]
    error_message = "Only an administrator resets a password."
  }
}

run "the_backend_signs_people_in_with_a_code_and_a_secret" {
  assert {
    condition     = aws_cognito_user_pool_client.app.generate_secret
    error_message = "The backend proves itself to the login provider with a secret."
  }

  assert {
    condition = (
      aws_cognito_user_pool_client.app.allowed_oauth_flows == toset(["code"])
      && aws_cognito_user_pool_client.app.allowed_oauth_flows_user_pool_client
    )
    error_message = "Signing in goes by a code that the backend exchanges, and by no other way."
  }

  assert {
    condition     = aws_cognito_user_pool_client.app.allowed_oauth_scopes == toset(["email", "openid", "profile"])
    error_message = "The backend asks who signed in and for nothing more: not for aws.cognito.signin.user.admin, which would let a visitor change an account."
  }

  assert {
    condition = (
      aws_cognito_user_pool_client.app.callback_urls == toset(["https://catalog.rgonz.dev/api/login/oauth2/code/cognito"])
      && aws_cognito_user_pool_client.app.logout_urls == toset(["https://catalog.rgonz.dev/"])
    )
    error_message = "The login provider sends a browser back to the site, and to nowhere else."
  }
}

run "each_demo_account_is_in_the_group_of_its_role" {
  assert {
    condition     = toset(keys(aws_cognito_user.demo)) == toset(["admin", "author", "manager"])
    error_message = "There is one demo account for each role."
  }

  assert {
    condition = alltrue([
      for role, membership in aws_cognito_user_in_group.demo :
      membership.username == role && membership.group_name == role
    ])
    error_message = "Each demo account is named after its role and is in that role's group."
  }

  assert {
    condition = alltrue([
      for account in aws_cognito_user.demo :
      account.message_action == "SUPPRESS" && endswith(account.attributes.email, "@example.com")
    ])
    error_message = "A demo account's address receives no mail, and no message is sent to it."
  }
}

run "the_host_is_told_where_to_sign_people_in_and_who_the_demo_accounts_are" {
  assert {
    condition = strcontains(
      aws_ssm_association.host_stack.parameters.commands,
      "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_COGNITO_ISSUER_URI=https://${aws_cognito_user_pool.people.endpoint}\n",
    )
    error_message = "The backend is told the user pool's own address as the issuer."
  }

  assert {
    condition = alltrue([
      for index, role in sort(keys(aws_cognito_user.demo)) : can(regex(
        "APP_DEMOACCOUNTS_${index}_ROLE=${role}\nAPP_DEMOACCOUNTS_${index}_USERNAME=${role}\nAPP_DEMOACCOUNTS_${index}_PASSWORD=[^\n]+\nAPP_DEMOACCOUNTS_${index}_SUBJECT=${aws_cognito_user.demo[role].sub}\n",
        aws_ssm_association.host_stack.parameters.commands,
      ))
    ])
    error_message = "The backend is told each demo account's role, username, password, and the subject the user pool gave it."
  }

  assert {
    condition     = !strcontains(aws_ssm_association.host_stack.parameters.commands, aws_cognito_user_pool_client.app.client_secret)
    error_message = "What the host is told to run does not hold the client secret: the host fetches it."
  }

  assert {
    condition = (
      aws_ssm_parameter.login_client_secret.type == "SecureString"
      && aws_ssm_parameter.login_client_secret.value == aws_cognito_user_pool_client.app.client_secret
      && strcontains(file("../deploy/release.sh"), "\"$(parameter login-client-secret)\"")
    )
    error_message = "The client secret is kept encrypted in Parameter Store, where the release script reads it."
  }
}

run "the_backend_changes_roles_in_this_user_pool_and_nothing_else" {
  assert {
    condition = jsondecode(aws_iam_role_policy.host_administer_roles.policy).Statement == [{
      Sid    = "ReadAndChangeRoles"
      Effect = "Allow"
      Action = [
        "cognito-idp:AdminAddUserToGroup", "cognito-idp:AdminGetUser",
        "cognito-idp:AdminListGroupsForUser", "cognito-idp:AdminRemoveUserFromGroup",
        "cognito-idp:ListUsers", "cognito-idp:ListUsersInGroup",
      ]
      Resource = aws_cognito_user_pool.people.arn
    }]
    error_message = "The host reads accounts and moves them between groups, in this user pool alone."
  }

  assert {
    condition = (
      strcontains(aws_ssm_association.host_stack.parameters.commands, "APP_SANDBOXACCOUNTS_0_SUBJECT=${aws_cognito_user.visitor.sub}\n")
      && strcontains(aws_ssm_association.host_stack.parameters.commands, "APP_PROTECTEDACCOUNTS_0=${aws_cognito_user.operator.sub}\n")
    )
    error_message = "The backend is told that the visitor is a sandbox account and that the operator is protected."
  }

  assert {
    condition     = aws_cognito_user.operator.password == null && aws_cognito_user.operator.temporary_password == null
    error_message = "No password of the operator account is kept here."
  }
}
