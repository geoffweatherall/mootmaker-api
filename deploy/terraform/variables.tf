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

variable "log_retention_days" {
  type        = number
  default     = 120
  description = <<-EOT
    How long this environment's Lambda and AppSync logs are kept (Decision 11). 120 days
    deliberately, not forever: permanence was never the goal, staying inside scale-to-zero was.
    Without this, Lambda and AppSync auto-create their groups with never-expire retention.
  EOT
}
