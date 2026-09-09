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
  # Built field by field rather than as $util.toJson($ctx), for one reason that is not optional and
  # one that is a bonus.
  #
  # The reason: $util.toJson($ctx) does NOT serialise info.selectionSetList. AWS documents that it
  # and selectionSetGraphQL "are not serialized by default", and decoding a live $ctx from this very
  # template confirmed it - the payload carried only fieldName, parentTypeName and variables. They
  # appear only when referenced explicitly, as below. Selection-aware resolving (see SelectionSet)
  # does not work at all without this, and fails SAFE without it: an absent selectionSetList makes
  # every lookup happen, which is exactly the old behaviour.
  #
  # The bonus: $ctx also carries $ctx.request.headers, so the old template shipped every CloudFront
  # request header to Lambda on every single call. Nothing ever read them.
  #
  # identity is emitted only when present. Building it unconditionally would hand Identity's
  # defence-in-depth check (see requireAuthenticated) a non-null identity on an unauthenticated
  # request, which is the one thing that check exists to catch.
  direct_lambda_request_template = <<-EOT
    {
      "version": "2018-05-29",
      "operation": "Invoke",
      "payload": {
        "info": {
          "fieldName": $util.toJson($ctx.info.fieldName),
          "parentTypeName": $util.toJson($ctx.info.parentTypeName),
          "selectionSetList": $util.toJson($ctx.info.selectionSetList)
        },
        "arguments": $util.toJson($ctx.arguments)
        #if( $ctx.identity )
        ,"identity": {
          "sub": $util.toJson($ctx.identity.sub),
          "claims": $util.toJson($ctx.identity.claims)
        }
        #end
      }
    }
  EOT

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
