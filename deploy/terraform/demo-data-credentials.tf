# Credentials for mootmaker-demo-data, the third deployable component (see
# ../../../mootmaker/designs/demo-data-component.md).
#
# demo-data used to authenticate as aws_cognito_user_pool_client.acceptance_tests - the *tests'*
# client - with its secret read from this project's Terraform outputs at deploy time, passed
# through TF_VAR_, and written into demo-data's Lambda environment variables in the clear. That
# conflated two unrelated consumers into one credential (neither rotatable without breaking the
# other, indistinguishable in CloudTrail), put the secret somewhere any holder of
# lambda:GetFunctionConfiguration could read it, and forced demo-data's deploy to read this
# project's Terraform state.
#
# Instead: its own app client, with the id and secret published to SSM Parameter Store at
# deterministic paths that demo-data reads at RUNTIME. The secret then exists in this project's
# state (unavoidable - Terraform created it) and in Parameter Store, but never in demo-data's own
# state and never in a Lambda environment variable. The deterministic path is the same
# loose-coupling pattern used for the database-reset function name and the SES/Route53 data
# sources: neither project reads the other's state.

# Confidential client for mootmaker-demo-data. Same scopes as the acceptance-test client (both
# create rooms and people, which are admin-only mutations), but a separate identity.
resource "aws_cognito_user_pool_client" "demo_data" {
  name            = "${local.resource_prefix}-demo-data"
  user_pool_id    = aws_cognito_user_pool.this.id
  generate_secret = true

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes = [
    "${aws_cognito_resource_server.api.identifier}/execute",
    "${aws_cognito_resource_server.api.identifier}/admin",
  ]
  supported_identity_providers = ["COGNITO"]
}

# Standard-tier parameters with the AWS-managed alias/aws/ssm key: both free. Deliberately NOT a
# customer-managed KMS key, which is billed a flat $1/month per key regardless of use - one per
# environment would break this project's scale-to-zero cost profile, the same reasoning that
# killed the email-verification-bypass design (see testing-strategy.md).
#
# The paths are computed from the environment name alone, so demo-data can construct them without
# reading anything from this project.
resource "aws_ssm_parameter" "demo_data_client_id" {
  name        = "/mootmaker/${var.environment}/demo-data/client-id"
  description = "App client id mootmaker-demo-data uses for its client_credentials token."
  type        = "String"
  value       = aws_cognito_user_pool_client.demo_data.id
}

resource "aws_ssm_parameter" "demo_data_client_secret" {
  name        = "/mootmaker/${var.environment}/demo-data/client-secret"
  description = "App client secret for the demo-data client id above."
  type        = "SecureString"
  value       = aws_cognito_user_pool_client.demo_data.client_secret
}

# The token endpoint and GraphQL URL are not secret, but publishing them here means demo-data
# needs exactly one input - the environment name - rather than a Terraform-output hand-off.
resource "aws_ssm_parameter" "demo_data_token_url" {
  name        = "/mootmaker/${var.environment}/demo-data/token-url"
  description = "OAuth2 token endpoint for this environment's Cognito user pool."
  type        = "String"
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${var.aws_region}.amazoncognito.com/oauth2/token"
}

resource "aws_ssm_parameter" "demo_data_graphql_url" {
  name        = "/mootmaker/${var.environment}/demo-data/graphql-url"
  description = "GraphQL endpoint of this environment's AppSync API."
  type        = "String"
  # The custom-domain URL (see domain.tf), matching output.graphql_api_url rather than the
  # AWS-generated *.appsync-api.*.amazonaws.com one - every other consumer (webapp, acceptance
  # tests) goes through the custom domain, and demo-data should exercise the same path.
  value = "https://${local.api_domain}/graphql"
}

resource "aws_ssm_parameter" "demo_data_scope" {
  name        = "/mootmaker/${var.environment}/demo-data/scope"
  description = "Space-separated OAuth2 scopes demo-data requests (execute + admin)."
  type        = "String"
  value       = "${aws_cognito_resource_server.api.identifier}/execute ${aws_cognito_resource_server.api.identifier}/admin"
}
