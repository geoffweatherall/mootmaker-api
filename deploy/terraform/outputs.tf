# Outputs for people reading a deploy's result. Nothing reads these programmatically except
# deploy/import-log-groups.sh (graphql_api_id): other components look this API up in SSM Parameter
# Store instead - see published-config.tf (mootmaker-api#94).

output "graphql_api_url" {
  description = "The GraphQL endpoint URL for the mootmaker API, via its custom domain (see domain.tf). Also published to SSM as /mootmaker/<environment>/api/graphql-url, which is what other components read (published-config.tf)."
  value       = "https://${local.api_domain}/graphql"
}

output "graphql_api_id" {
  description = "AppSync API id. Needed by deploy/import-log-groups.sh, because AppSync's log group is named /aws/appsync/apis/<id> and that id is only knowable from deployed state."
  value       = aws_appsync_graphql_api.this.id
}

output "avatars_base_url" {
  description = "Origin that person avatars are served from, e.g. https://avatars.mootmaker.com in production and https://avatars.<environment>.mootmaker.com elsewhere (see avatars.tf). Person.avatarUrl already arrives fully resolved, so the webapp never needs this - it exists for acceptance tests and tooling that want to assert on the host itself, or fetch an object directly."
  value       = local.avatars_base_url
}

output "avatars_bucket_name" {
  description = "S3 bucket holding person avatars: staged uploads under uploads/, served images under avatars/ (see avatars.tf). Exposed for acceptance tests that need to assert on what was actually stored, rather than on what the API said it stored."
  value       = aws_s3_bucket.avatars.bucket
}
