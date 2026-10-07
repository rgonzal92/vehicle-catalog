# Who signs in. Amazon Cognito keeps the accounts and their passwords and shows the sign-in page.
# The backend learns from it who signed in and which role's group they are in.

locals {
  site_url = "https://${local.site_name}"

  # The demo accounts, one for each role, named after it. Their passwords are public on purpose:
  # the landing page shows them.
  demo_accounts = {
    author  = { display_name = "Demo Author", password = "Catalog-author-1" }
    manager = { display_name = "Demo Manager", password = "Catalog-manager-1" }
    admin   = { display_name = "Demo Admin", password = "Catalog-admin-1" }
  }

  login_url = "https://${aws_cognito_user_pool_domain.login.domain}.auth.${data.aws_region.current.region}.amazoncognito.com"

  # What the backend is told about signing in, none of it secret: where the login provider is, and
  # who each demo account is to that provider. The host keeps it in a file beside the stack's.
  login_settings = join("\n", concat(
    [
      "OIDC_CLIENT_ID=${aws_cognito_user_pool_client.app.id}",
      "SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_COGNITO_ISSUER_URI=https://${aws_cognito_user_pool.people.endpoint}",
      "LOGOUT_URL=${local.login_url}/logout",
    ],
    flatten([
      for index, role in keys(local.demo_accounts) : [
        "APP_DEMOACCOUNTS_${index}_ROLE=${role}",
        "APP_DEMOACCOUNTS_${index}_USERNAME=${aws_cognito_user.demo[role].username}",
        "APP_DEMOACCOUNTS_${index}_PASSWORD=${local.demo_accounts[role].password}",
        "APP_DEMOACCOUNTS_${index}_SUBJECT=${aws_cognito_user.demo[role].sub}",
        "APP_DEMOACCOUNTS_${index}_DISPLAYNAME=${local.demo_accounts[role].display_name}",
      ]
    ]),
    [
      # Whose roles the backend reads and changes, and in which user pool.
      "COGNITO_USER_POOL_ID=${aws_cognito_user_pool.people.id}",
      "APP_SANDBOXACCOUNTS_0_SUBJECT=${aws_cognito_user.visitor.sub}",
      "APP_SANDBOXACCOUNTS_0_USERNAME=${aws_cognito_user.visitor.username}",
      "APP_SANDBOXACCOUNTS_0_EMAIL=${aws_cognito_user.visitor.attributes.email}",
      "APP_SANDBOXACCOUNTS_0_ROLE=${aws_cognito_user_in_group.visitor.group_name}",
      "APP_PROTECTEDACCOUNTS_0=${aws_cognito_user.operator.sub}",
    ],
  ))
}

resource "aws_cognito_user_pool" "people" {
  name                = "vehicle-catalog"
  deletion_protection = "ACTIVE"

  # Nobody signs up: an account is made here, by an apply.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  # Nobody resets a password by themselves, so a visitor cannot take a demo account from the
  # others who use it.
  account_recovery_setting {
    recovery_mechanism {
      name     = "admin_only"
      priority = 1
    }
  }
}

# Where the sign-in page is.
resource "aws_cognito_user_pool_domain" "login" {
  domain                = "rgonz-vehicle-catalog"
  user_pool_id          = aws_cognito_user_pool.people.id
  managed_login_version = 2
}

# The backend, as the login provider knows it. It proves itself with a secret, is given a code that
# only it can exchange, and asks for nothing that would let a signed-in visitor change an account:
# that takes the scope aws.cognito.signin.user.admin, which is not among these.
resource "aws_cognito_user_pool_client" "app" {
  name            = "vehicle-catalog"
  user_pool_id    = aws_cognito_user_pool.people.id
  generate_secret = true

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["email", "openid", "profile"]
  supported_identity_providers         = ["COGNITO"]

  # The two addresses the provider sends a browser back to: after signing in, and after signing out.
  callback_urls = ["${local.site_url}/api/login/oauth2/code/cognito"]
  logout_urls   = ["${local.site_url}/"]

  # A wrong username is answered like a wrong password.
  prevent_user_existence_errors = "ENABLED"
}

# The sign-in page as Cognito draws it. Without a style of some kind it shows nothing.
resource "aws_cognito_managed_login_branding" "app" {
  client_id                   = aws_cognito_user_pool_client.app.id
  user_pool_id                = aws_cognito_user_pool.people.id
  use_cognito_provided_values = true
}

# A group for each role. The groups someone is in come with their sign-in, and the backend takes
# their role from them.
resource "aws_cognito_user_group" "role" {
  for_each = local.demo_accounts

  name         = each.key
  user_pool_id = aws_cognito_user_pool.people.id
}

# The addresses receive no mail, and no message is sent to them.
resource "aws_cognito_user" "demo" {
  for_each = local.demo_accounts

  user_pool_id   = aws_cognito_user_pool.people.id
  username       = each.key
  password       = each.value.password
  message_action = "SUPPRESS"

  attributes = {
    name  = each.value.display_name
    email = "${each.key}@example.com"
  }
}

resource "aws_cognito_user_in_group" "demo" {
  for_each = local.demo_accounts

  user_pool_id = aws_cognito_user_pool.people.id
  group_name   = aws_cognito_user_group.role[each.key].name
  username     = aws_cognito_user.demo[each.key].username
}

# A sandbox account that is not protected: an admin on the public demo can change its role, and
# the demo reset puts it back in this group. Its password is public like the demo accounts'.
resource "aws_cognito_user" "visitor" {
  user_pool_id   = aws_cognito_user_pool.people.id
  username       = "visitor"
  password       = "Catalog-visitor-1"
  message_action = "SUPPRESS"

  attributes = {
    name  = "Sandbox Visitor"
    email = "visitor@example.com"
  }
}

resource "aws_cognito_user_in_group" "visitor" {
  user_pool_id = aws_cognito_user_pool.people.id
  group_name   = aws_cognito_user_group.role["author"].name
  username     = aws_cognito_user.visitor.username
}

# The operator account: the maintainer's own login, an admin outside the sandbox whose role the
# app does not change. Cognito sends a temporary password to the maintainer's address, and the
# maintainer sets their own at the first sign-in, so no password of this account is kept here.
resource "aws_cognito_user" "operator" {
  user_pool_id = aws_cognito_user_pool.people.id
  username     = "operator-rg"

  attributes = {
    name           = "Operator"
    email          = var.budget_notification_email
    email_verified = "true"
  }
}

resource "aws_cognito_user_in_group" "operator" {
  user_pool_id = aws_cognito_user_pool.people.id
  group_name   = aws_cognito_user_group.role["admin"].name
  username     = aws_cognito_user.operator.username
}

# What changing a role takes: the host's backend reads the accounts and their groups in this user
# pool and moves an account from one group to another, and does nothing else there.
resource "aws_iam_role_policy" "host_administer_roles" {
  name = "administer-roles"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "ReadAndChangeRoles"
      Effect = "Allow"
      Action = [
        "cognito-idp:AdminAddUserToGroup",
        "cognito-idp:AdminGetUser",
        "cognito-idp:AdminListGroupsForUser",
        "cognito-idp:AdminRemoveUserFromGroup",
        "cognito-idp:ListUsers",
        "cognito-idp:ListUsersInGroup",
      ]
      Resource = aws_cognito_user_pool.people.arn
    }]
  })
}

# The secret the backend proves itself with. Cognito makes it, so it is in the state as well; the
# host reads it from here.
resource "aws_ssm_parameter" "login_client_secret" {
  name        = "/vehicle-catalog/login-client-secret"
  description = "The secret the backend proves itself to the login provider with."
  type        = "SecureString"
  value       = aws_cognito_user_pool_client.app.client_secret
}
