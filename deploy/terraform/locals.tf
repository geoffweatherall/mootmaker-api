locals {
  # Every AWS resource name derives from this instead of project_name directly,
  # so multiple environments can coexist in the same AWS account without
  # colliding (e.g. test-mootmaker-rooms vs production-mootmaker-rooms).
  resource_prefix = "${var.environment}-${var.project_name}"

  # Detects Claude's own ephemeral dev-session environments (claude-<timestamp>-<rand>) and
  # automated e2e test-run environments (e2e-<timestamp>-<rand>) purely from the environment name
  # - see mootmaker/testing-strategy.md's "Environments" section for the naming convention. Used to
  # self-enable the email verification-code bypass (see cognito.tf/dynamodb.tf/kms.tf) without any
  # caller of deploy.sh needing to know that variable exists or pass a flag for it: `test`,
  # `production`, and any developer's own personal-sandbox name (e.g. "bob") all evaluate to
  # false, so the bypass can never be active anywhere a real person's account might exist.
  is_ephemeral = can(regex("^(claude|e2e)-", var.environment))
}
