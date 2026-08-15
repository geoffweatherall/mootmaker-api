locals {
  # Every AWS resource name derives from this instead of project_name directly,
  # so multiple environments can coexist in the same AWS account without
  # colliding (e.g. test-mootmaker-rooms vs production-mootmaker-rooms).
  resource_prefix = "${var.environment}-${var.project_name}"

  # Detects Claude's own ephemeral dev-session environments (claude-<timestamp>-<rand>) and
  # automated e2e test-run environments (e2e-<timestamp>-<rand>) purely from the environment name
  # - see mootmaker/testing-strategy.md's "Environments" section for the naming convention. `test`,
  # `production`, and any developer's own personal-sandbox name (e.g. "bob") all evaluate to
  # false, so the bypass below can never be active anywhere a real person's account might exist.
  is_ephemeral = can(regex("^(claude|e2e)-", var.environment))

  # The actual gate used by cognito.tf/dynamodb.tf/kms.tf/lambda.tf's email verification-code
  # bypass resources. Originally just is_ephemeral alone (so no caller of deploy.sh would ever
  # need to know this exists or pass a flag for it) - now also requires the explicit
  # enable_test_email_bypass opt-in (see variables.tf) because this account's SCP doesn't allow
  # kms yet, and is_ephemeral alone would mean every claude-*/e2e-* deploy unconditionally tries
  # (and fails) to create a KMS key. Once the SCP is updated, flipping that variable's default to
  # true restores the original zero-flags design.
  test_email_bypass_enabled = local.is_ephemeral && var.enable_test_email_bypass
}
