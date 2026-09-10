resource "aws_appsync_graphql_api" "this" {
  name                = "${local.resource_prefix}-api"
  authentication_type = "AMAZON_COGNITO_USER_POOLS"
  schema              = file("${path.module}/../../api/mootmaker.graphql")

  user_pool_config {
    user_pool_id = aws_cognito_user_pool.this.id
    aws_region   = var.aws_region
    # ALLOW is not a preference here, it is the only legal value: AppSync rejects the API outright
    # with "Additional authentication providers cannot be specified when setting DENY for top level
    # user pool authentication type". Verified by trying it. So this looks decorative and is not -
    # do not "tidy" it to DENY on the assumption that it tightens anything.
    default_action = "ALLOW"
  }

  # The publish path for subscriptions. Mutation.publishDaysInvalidated is @aws_iam-only, which is
  # a stronger boundary than any other field has: AppSync refuses the call before a resolver runs,
  # so no signed-in user can reach it whatever the resolver would have done. Adding IAM here is
  # what makes that directive mean something.
  additional_authentication_provider {
    authentication_type = "AWS_IAM"
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

  # $ctx.error is checked FIRST, and that check is the whole point of this not being a one-liner.
  #
  # Without it, a resolver that throws produces a null $ctx.result, and the only thing the client
  # ever sees is AppSync's own nullability complaint:
  #
  #     Cannot return null for non-nullable type: 'Workspace' within parent 'Query' (/workspace)
  #
  # The handler's actual message - "Too many dates requested: 51 exceeds the limit of 42." - is
  # dropped entirely. Every server-side limit, every bad request, and every genuine internal fault
  # arrives at the client as the same sentence, which names the schema rather than the problem.
  #
  # Note this does NOT affect the ordinary validation path: MeetingError, RoomError and PersonError
  # come back in typed `errors` arrays as normal RETURN values, not exceptions, and were always
  # visible. What was invisible is exactly the class of failure a developer most needs to read.
  direct_lambda_response_template = <<-EOT
    #if($ctx.error)
      $util.error($ctx.error.message, $ctx.error.type)
    #end
    $util.toJson($ctx.result)
  EOT
}

# One resolver for the composite entry point, replacing the four that served Query.rooms,
# Query.people, Query.myPerson and Query.meetings. Every field still shares one Lambda and one
# request template; what changed is that a page load now reaches it once rather than four times.
resource "aws_appsync_resolver" "workspace" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "workspace"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "meeting" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "meeting"
  data_source       = aws_appsync_datasource.resolvers.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_meetings" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createMeetings"
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

# ─── Subscriptions ───────────────────────────────────────────────────────────
#
# publishDaysInvalidated exists because @aws_subscribe pushes the MUTATION'S return value, and
# requires the subscription field's type to match it exactly. Both halves of that were verified
# empirically rather than taken from documentation (see the design's "Verified: AppSync
# subscription behaviour"):
#
#   - A mismatched type is rejected at schema creation, loudly: "The subscription has an invalid
#     output type." That one fails safely.
#   - Everything else about subscriptions fails SILENTLY. A rejected createMeeting - which returns
#     successfully with a typed errors array - broadcasts exactly like a success, so subscribing
#     to createMeeting directly would push bookings that never happened.
#
# NONE rather than a Lambda: this mutation computes nothing. It takes dates and returns them so
# AppSync has a payload to broadcast. A Lambda here would be an invocation, a cold start and a
# failure mode in exchange for nothing.
resource "aws_appsync_datasource" "publish" {
  api_id = aws_appsync_graphql_api.this.id
  name   = "PublishDataSource"
  type   = "NONE"
}

resource "aws_appsync_resolver" "publish_days_invalidated" {
  api_id      = aws_appsync_graphql_api.this.id
  type        = "Mutation"
  field       = "publishDaysInvalidated"
  data_source = aws_appsync_datasource.publish.name

  # Echoes the arguments back as the payload: {"dates": [...]} is already the shape Invalidation
  # needs, so there is nothing to map.
  request_template = <<-EOT
    {
      "version": "2018-05-29",
      "payload": $util.toJson($context.arguments)
    }
  EOT

  response_template = "$util.toJson($context.result)"
}
