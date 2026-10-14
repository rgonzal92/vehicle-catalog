variable "budget_notification_email" {
  type        = string
  description = "The address the budget alert and the alarms' emails are sent to. It is not kept in the repository."
  sensitive   = true

  validation {
    condition     = strcontains(var.budget_notification_email, "@")
    error_message = "Give the address the budget alert and the alarms' emails are sent to."
  }
}
