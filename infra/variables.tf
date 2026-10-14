variable "budget_notification_email" {
  type        = string
  description = "The address the budget alert and the alarms' emails are sent to. It is not kept in the repository."
  sensitive   = true

  validation {
    condition     = strcontains(var.budget_notification_email, "@")
    error_message = "Give the address the budget alert and the alarms' emails are sent to."
  }
}

variable "restore_machine" {
  type        = bool
  description = "Whether the clean machine a backup is put back on exists. Off unless it is turned on for one plan, on the command line and in no file: `terraform plan -var restore_machine=true`. restore.tf says what it makes."
  default     = true
}
