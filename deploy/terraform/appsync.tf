resource "aws_appsync_graphql_api" "this" {
  name                = "${local.resource_prefix}-api"
  authentication_type = "AMAZON_COGNITO_USER_POOLS"
  schema              = file("${path.module}/../../api/mootmaker.graphql")

  user_pool_config {
    user_pool_id   = aws_cognito_user_pool.this.id
    aws_region     = var.aws_region
    default_action = "ALLOW"
  }

  # Decision 11. Without this block AppSync logs NOTHING - a GraphQL error rejected before it ever
  # reaches a resolver leaves no trace anywhere, which is the gap this closes: the Definition of
  # done asks for AppSync's own request/resolver logs alongside the Lambda execution logs, not
  # just the latter.
  #
  # ERROR rather than ALL: ALL logs every request's full resolver trace, which on a demo system
  # refreshed daily by demo-data is a lot of volume for very little signal. Errors are what a
  # release troubleshooting session actually reads. Raise it temporarily if a specific
  # investigation needs the detail.
  log_config {
    cloudwatch_logs_role_arn = aws_iam_role.appsync_logging.arn
    field_log_level          = "ERROR"
    exclude_verbose_content  = true
  }
}

# One data source, shared by every resolver below - AppSync supports many resolvers pointing at
# the same Lambda data source, and ResolverDispatchHandler (see lambda.tf) is the single Lambda
# behind all of them, routing on $context.info.parentTypeName/fieldName.
resource "aws_appsync_datasource" "resolvers" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "ResolversDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_alias.resolvers_live.arn
  }
}

locals {
  # $util.toJson($ctx) already includes $ctx.info (fieldName/parentTypeName), which
  # ResolverDispatchHandler uses to route - so no per-resolver template is needed even though all
  # 10 fields now share one Lambda.
  direct_lambda_request_template  = "{\"version\":\"2018-05-29\",\"operation\":\"Invoke\",\"payload\":$util.toJson($ctx)}"
  direct_lambda_response_template = "$util.toJson($ctx.result)"
}

resource "aws_appsync_resolver" "rooms" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "rooms"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "people" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "people"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_room" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createRoom"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "update_room" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "updateRoom"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_person" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createPerson"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "update_person" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "updatePerson"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "update_my_preferences" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "updateMyPreferences"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "my_person" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "myPerson"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "meetings" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "meetings"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_meeting" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createMeeting"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "delete_my_account" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "deleteMyAccount"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "suggest_room" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "suggestRoom"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}
