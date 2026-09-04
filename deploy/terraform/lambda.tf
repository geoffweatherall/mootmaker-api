locals {
  lambda_jar_path = "${path.module}/../../impl/target/mootmaker-api.jar"
  # `terraform destroy` still evaluates this expression even though the jar's contents are
  # irrelevant when only deleting resources, so fall back to null when the jar hasn't been built
  # (e.g. undeploy.sh without ever having run deploy.sh) instead of erroring out.
  lambda_jar_hash = fileexists(local.lambda_jar_path) ? filebase64sha256(local.lambda_jar_path) : null
  lambda_env_vars = {
    ROOMS_TABLE_NAME                = aws_dynamodb_table.rooms.name
    PEOPLE_TABLE_NAME               = aws_dynamodb_table.people.name
    MEETINGS_TABLE_NAME             = aws_dynamodb_table.meetings.name
    MEETING_PARTICIPANTS_TABLE_NAME = aws_dynamodb_table.meeting_participants.name
  }

  # COGNITO_ADMIN_SCOPE (read by Identity.requireAdmin to recognise the M2M tooling client as
  # admin-equivalent - see cognito.tf's resource server) can't live in the plain lambda_env_vars
  # map above: aws_cognito_resource_server.api itself depends on aws_cognito_user_pool.this, and
  # post_confirmation_create_person's Lambda (which uses lambda_env_vars) is in turn referenced by
  # that same user pool's own lambda_config - so anything in the shared map that touches the
  # resource server would be a circular dependency for that one function.
  admin_gated_env_vars = merge(local.lambda_env_vars, {
    COGNITO_ADMIN_SCOPE = "${aws_cognito_resource_server.api.identifier}/admin"
  })

  # The two Terraform-managed reserved accounts - DeleteMyAccountHandler refuses to let either
  # self-delete, and database-reset (see admin-tools.tf) preserves exactly these two, and nothing
  # else, when it wipes the Cognito pool. One local so both consumers can never disagree about
  # which accounts are reserved.
  reserved_account_emails = "${aws_cognito_user.demo.username},${aws_cognito_user.e2e.username}"

  # ResolverDispatchHandler (see impl/src/main/java/com/mootmaker/handler/ResolverDispatchHandler.java)
  # is the single entry point for every AppSync direct-Lambda resolver, so it needs the union of
  # every env var any individual resolver handler used to need - including COGNITO_USER_POOL_ID
  # (previously only update_person's own function got this). Unlike post_confirmation_create_person,
  # this function is never itself referenced by aws_cognito_user_pool.this's lambda_config, so the
  # circular-dependency concern above doesn't apply here.
  resolver_lambda_env_vars = merge(local.admin_gated_env_vars, {
    COGNITO_USER_POOL_ID    = aws_cognito_user_pool.this.id
    RESERVED_ACCOUNT_EMAILS = local.reserved_account_emails
  })
}

# One Lambda function behind every AppSync direct-Lambda resolver (see appsync.tf): AppSync's
# $context.info (fieldName/parentTypeName) - already forwarded today via the shared pass-through
# request template - tells ResolverDispatchHandler which of the 10 GraphQL fields to run, so a
# user's burst of calls across several fields can land on the same already-restored SnapStart
# execution environment instead of each field independently paying its own restore.
resource "aws_lambda_function" "resolvers" {
  function_name    = "${local.resource_prefix}-resolvers"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.ResolverDispatchHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15
  # A published version is required for SnapStart (it never applies to $LATEST); the "live" alias
  # below is what AppSync actually invokes, so each deploy's new version becomes live only once
  # Terraform has finished applying, and SnapStart's snapshot is taken from this published version
  # rather than the mutable $LATEST.
  publish = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = local.resolver_lambda_env_vars
  }

  # The log group must exist BEFORE this function can be invoked. SnapStart
  # publishes a version by executing the function's init, and that invocation would
  # otherwise make Lambda auto-create the group - which then collides with
  # Terraform's own create. See logs.tf for the full reasoning.
  depends_on = [aws_cloudwatch_log_group.lambda]
}

resource "aws_lambda_alias" "resolvers_live" {
  name             = "live"
  function_name    = aws_lambda_function.resolvers.function_name
  function_version = aws_lambda_function.resolvers.version
}

# Cognito's PostConfirmation trigger, not an AppSync resolver - a different event shape (a Cognito
# trigger event, not an AppSync $ctx), firing once per sign-up rather than as part of an
# interactive multi-field GraphQL burst, so it stays a function of its own rather than being folded
# into the resolvers dispatcher above.
resource "aws_lambda_function" "post_confirmation_create_person" {
  function_name    = "${local.resource_prefix}-post-confirmation-create-person"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.PostConfirmationCreatePersonHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15
  # A published version is required for SnapStart (it never applies to $LATEST); the "live"
  # alias below is what Cognito actually invokes, so each deploy's new version becomes live only
  # once Terraform has finished applying, and SnapStart's snapshot is taken from this published
  # version rather than the mutable $LATEST.
  publish = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = local.lambda_env_vars
  }

  # The log group must exist BEFORE this function can be invoked. SnapStart
  # publishes a version by executing the function's init, and that invocation would
  # otherwise make Lambda auto-create the group - which then collides with
  # Terraform's own create. See logs.tf for the full reasoning.
  depends_on = [aws_cloudwatch_log_group.lambda]
}

resource "aws_lambda_alias" "post_confirmation_create_person_live" {
  name             = "live"
  function_name    = aws_lambda_function.post_confirmation_create_person.function_name
  function_version = aws_lambda_function.post_confirmation_create_person.version
}
