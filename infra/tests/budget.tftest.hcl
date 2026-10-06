# The budget alert has somewhere to go. Nothing here reaches AWS: the provider is a stand-in.
mock_provider "aws" {}

run "the_alert_needs_an_address" {
  command = plan

  variables {
    budget_notification_email = ""
  }

  expect_failures = [var.budget_notification_email]
}
