variable "aws_region" {
  description = "AWS region to deploy the mootmaker API into."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Prefix used to name AWS resources for this project."
  type        = string
  default     = "mootmaker"
}

variable "environment" {
  description = "Name of the environment to deploy (e.g. \"test\", \"production\", or a developer's name for a personal sandbox). Combined with project_name to keep multiple environments' AWS resources from colliding in the same account. Required - no default, so an environment is always chosen deliberately."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.environment))
    error_message = "environment must contain only lowercase letters, digits, and hyphens (it's used in AWS resource names and S3 state keys)."
  }
}

# Defaults to false, deliberately NOT following is_ephemeral automatically yet: this account's SCP
# doesn't allow the kms service (see kms.tf), so the KMS key/DynamoDB table/Lambda trigger this
# gates would fail to create on every single claude-*/e2e-* deploy if this defaulted to
# `local.is_ephemeral` directly - real deployment testing during this feature's own rollout found
# exactly that (see testing-strategy.md's "Email verification code bypass" section). Once the SCP
# is updated, flip this default to `true` (or pass -var="enable_test_email_bypass=true" per deploy)
# to restore the original design intent - self-enabled for ephemeral environments with no caller
# needing to know it exists.
variable "enable_test_email_bypass" {
  description = "Whether to create the email verification-code bypass resources (kms.tf, the test_email_codes table, the CustomEmailSender trigger) for an is_ephemeral environment. Ignored (always off) for a non-ephemeral one. Defaults to false because this account's SCP doesn't allow kms yet - see testing-strategy.md."
  type        = bool
  default     = false
}
