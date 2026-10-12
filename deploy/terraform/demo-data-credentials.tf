# Credentials for mootmaker-demo-data (see ../../../mootmaker/designs/archive/demo-data-component.md).
#
# demo-data has its own app client, not the acceptance tests', so each can be rotated or revoked
# alone and CloudTrail tells them apart. The id and secret are published to SSM Parameter Store at
# deterministic paths that demo-data reads at RUNTIME, so the secret is never in demo-data's state or
# in a Lambda environment variable (where anyone with lambda:GetFunctionConfiguration could read it).
# The deterministic path is the same loose coupling used for the database-reset function name and
# the SES/Route53 data sources: neither project reads the other's state.

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
# environment would break this project's scale-to-zero cost profile.
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

# Persons that mootmaker-demo-data must give at least one meeting on every work day in its window.
#
# Without this the demo user's calendar is populated only by luck: demo-data picks organisers and
# attendees at random from every Person, so the published account the signed-out home page hands
# every visitor can show an empty calendar.
#
# A LIST, not a "demo user" flag, and this project decides what goes in it. demo-data honours the
# ids without needing a concept of which account is special - adding the e2e Person later is a
# value change here, not a code change there.
#
# The id is the same deterministic uuidv5 written into the demo user's custom:personId claim and
# into aws_dynamodb_table_item.demo_person, so all three agree by construction rather than by
# convention. Deliberately NOT recomputed in demo-data's own Terraform: that would duplicate a
# derivation whose determinism is load-bearing (see cognito.tf), and a later change to the seed
# string would leave demo-data guaranteeing meetings for a Person that does not exist.
#
# StringList rather than String: SSM's own type for this, and the SDK returns it comma-joined
# either way, so the type is documentation that costs nothing.
resource "aws_ssm_parameter" "demo_data_guaranteed_person_ids" {
  name        = "/mootmaker/${var.environment}/demo-data/guaranteed-person-ids"
  description = "Person ids demo-data guarantees at least one meeting per work day. See mootmaker-demo-data#29."
  type        = "StringList"
  value       = join(",", [local.demo_person_id])
}
