# What this component publishes for everything else to look up, by environment name, instead of
# reading this repository's Terraform outputs from a sibling checkout (mootmaker-api#94, which
# replaced authenticate.sh). Layout: /mootmaker/<environment>/api/<key>.
#
# Secrets are SecureString with the account's default aws/ssm key; everything else is String.
# Consumers: mootmaker-webapp's deploy.sh and its acceptance/e2e runners, this repo's verify.sh,
# mootmaker-demo-data's verify.sh, and mootmaker-release's smoke tests.
#
# demo-data's own runtime credentials stay under /mootmaker/<environment>/demo-data/ (see
# demo-data-credentials.tf) - that client is demo-data's, not a published interface.

locals {
  config_prefix = "/mootmaker/${var.environment}/api"

  # Test fixtures exist to be used by acceptance suites, which only ever run against ephemeral
  # environments. Nothing that runs against test or production reads them, so they are not
  # published there (mootmaker-api#95 removes the accounts themselves from those environments).
  is_ephemeral = !contains(["test", "production"], var.environment)

  published_config = {
    "graphql-url"                   = "https://${local.api_domain}/graphql"
    "region"                        = var.aws_region
    "cognito/user-pool-id"          = aws_cognito_user_pool.this.id
    "cognito/webapp-client-id"      = aws_cognito_user_pool_client.webapp.id
    "demo-user/email"               = aws_cognito_user.demo.username
    "database-reset/function-name"  = aws_lambda_function.database_reset.function_name
    "history-cleanup/function-name" = aws_lambda_function.history_cleanup.function_name
    # The API's machine-to-machine client. Not only a test fixture: mootmaker-webapp's deploy uses
    # it to introspect the deployed schema before every deploy, in every environment.
    "m2m-client/client-id" = aws_cognito_user_pool_client.acceptance_tests.id
    "m2m-client/token-url" = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${var.aws_region}.amazoncognito.com/oauth2/token"
    "m2m-client/scope"     = "${aws_cognito_resource_server.api.identifier}/execute ${aws_cognito_resource_server.api.identifier}/admin"
  }
}

resource "aws_ssm_parameter" "published" {
  for_each = local.published_config

  name  = "${local.config_prefix}/${each.key}"
  type  = "String"
  value = each.value
}

# Public by design - the webapp shows it on the home page - but a random_password result is always
# marked sensitive, and for_each keys cannot be derived from sensitive values, hence its own block.
resource "aws_ssm_parameter" "demo_user_password" {
  name  = "${local.config_prefix}/demo-user/password"
  type  = "String"
  value = nonsensitive(random_password.demo_user.result)
}

resource "aws_ssm_parameter" "m2m_client_secret" {
  name  = "${local.config_prefix}/m2m-client/client-secret"
  type  = "SecureString"
  value = aws_cognito_user_pool_client.acceptance_tests.client_secret
}

# Ephemeral environments only. The existing e2e user is the suites' standard (non-admin) user,
# so it is published as such; mootmaker-api#95 adds the admin user and moves both to real sign-ups.
resource "aws_ssm_parameter" "standard_user_email" {
  count = local.is_ephemeral ? 1 : 0
  name  = "${local.config_prefix}/test-fixtures/users/standard/email"
  type  = "String"
  value = aws_cognito_user.e2e.username
}

resource "aws_ssm_parameter" "standard_user_password" {
  count = local.is_ephemeral ? 1 : 0
  name  = "${local.config_prefix}/test-fixtures/users/standard/password"
  type  = "SecureString"
  value = random_password.e2e_user.result
}

resource "aws_ssm_parameter" "no_person_user_email" {
  count = local.is_ephemeral ? 1 : 0
  name  = "${local.config_prefix}/test-fixtures/users/no-person/email"
  type  = "String"
  value = aws_cognito_user.no_person[0].username
}

resource "aws_ssm_parameter" "no_person_user_password" {
  count = local.is_ephemeral ? 1 : 0
  name  = "${local.config_prefix}/test-fixtures/users/no-person/password"
  type  = "SecureString"
  value = random_password.no_person_user[0].result
}
