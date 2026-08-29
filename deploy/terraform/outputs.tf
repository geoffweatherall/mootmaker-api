output "graphql_api_url" {
  description = "The GraphQL endpoint URL for the mootmaker API, via its custom domain (see domain.tf). The AWS-generated *.appsync-api.*.amazonaws.com URL still works too, but everything downstream (webapp, tools, acceptance tests) reads this output via authenticate.sh, so they pick up the custom domain automatically."
  value       = "https://${local.api_domain}/graphql"
}

output "aws_region" {
  description = "AWS region this environment is deployed into - for tools that call AWS APIs directly (e.g. mootmaker-admin-tools/database-repair) rather than only through the GraphQL API."
  value       = var.aws_region
}

output "rooms_table_name" {
  description = "DynamoDB table name for Room records - for tools that need direct read/write access (e.g. mootmaker-admin-tools/database-reset), not exposed via the GraphQL API."
  value       = aws_dynamodb_table.rooms.name
}

output "people_table_name" {
  description = "DynamoDB table name for Person records - for tools that need direct read/write access (e.g. mootmaker-admin-tools/database-repair, mootmaker-admin-tools/database-reset), not exposed via the GraphQL API."
  value       = aws_dynamodb_table.people.name
}

output "meetings_table_name" {
  description = "DynamoDB table name for Meeting records - for tools that need direct read/write access (e.g. mootmaker-admin-tools/database-repair, mootmaker-admin-tools/database-reset), not exposed via the GraphQL API."
  value       = aws_dynamodb_table.meetings.name
}

output "meeting_participants_table_name" {
  description = "DynamoDB table name for the meeting-participants join index - for tools that need direct read/write access (e.g. mootmaker-admin-tools/database-repair's RebuildMeetingParticipantsRepair, mootmaker-admin-tools/database-reset), not exposed via the GraphQL API."
  value       = aws_dynamodb_table.meeting_participants.name
}

output "cognito_user_pool_id" {
  description = "Id of the Cognito user pool that authenticates API callers."
  value       = aws_cognito_user_pool.this.id
}

output "cognito_webapp_client_id" {
  description = "Public app client id used by the browser webapp to sign users in."
  value       = aws_cognito_user_pool_client.webapp.id
}

output "cognito_token_url" {
  description = "OAuth2 token endpoint used to obtain client_credentials access tokens."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${var.aws_region}.amazoncognito.com/oauth2/token"
}

output "cognito_test_client_id" {
  description = "App client id the acceptance tests use with the client_credentials flow."
  value       = aws_cognito_user_pool_client.acceptance_tests.id
}

output "cognito_test_client_secret" {
  description = "App client secret the acceptance tests use with the client_credentials flow."
  value       = aws_cognito_user_pool_client.acceptance_tests.client_secret
  sensitive   = true
}

output "cognito_test_scope" {
  description = "OAuth2 scope(s) requested by the acceptance-test client's client_credentials tokens, space-separated (the format the token endpoint's `scope` parameter expects for more than one). Includes both execute (GraphQL access) and admin (the M2M-tooling equivalent of custom:class=admin - see Identity.requireAdmin) - every M2M caller (mootmaker-api's own acceptance tests, mootmaker-demo-data/sample-data-generator) requests exactly this value, so both need it to keep creating rooms/people now that those mutations are admin-only."
  value       = "${aws_cognito_resource_server.api.identifier}/execute ${aws_cognito_resource_server.api.identifier}/admin"
}

output "e2e_user_email" {
  description = "Email of the pre-confirmed user the webapp Playwright tests sign in as."
  value       = aws_cognito_user.e2e.username
}

output "e2e_user_password" {
  description = "Password of the pre-confirmed user the webapp Playwright tests sign in as."
  value       = random_password.e2e_user.result
  sensitive   = true
}

output "demo_user_email" {
  description = "Email of the pre-confirmed, publicly-known demo user anyone can sign in as. Not sensitive - the webapp displays it directly on the home page."
  value       = aws_cognito_user.demo.username
}

output "demo_user_password" {
  description = "Password of the pre-confirmed, publicly-known demo user anyone can sign in as. Not sensitive - the webapp displays it directly on the home page."
  # random_password's .result is always marked sensitive by the provider itself; nonsensitive()
  # overrides that here since this one really is meant to be shown in the clear (unlike
  # e2e_user_password below, which stays sensitive).
  value = nonsensitive(random_password.demo_user.result)
}
