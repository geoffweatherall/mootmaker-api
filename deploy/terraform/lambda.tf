locals {
  lambda_jar_path = "${path.module}/../../impl/target/mootmaker-api.jar"
  # `terraform destroy` still evaluates this expression even though the jar's contents are
  # irrelevant when only deleting resources, so fall back to null when the jar hasn't been built
  # (e.g. undeploy.sh without ever having run deploy.sh) instead of erroring out.
  lambda_jar_hash = fileexists(local.lambda_jar_path) ? filebase64sha256(local.lambda_jar_path) : null
  lambda_env_vars = {
    ROOMS_TABLE_NAME    = aws_dynamodb_table.rooms.name
    PEOPLE_TABLE_NAME   = aws_dynamodb_table.people.name
    MEETINGS_TABLE_NAME = aws_dynamodb_table.meetings.name
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

  # The Terraform-managed reserved accounts - DeleteMyAccountHandler refuses to let any of them
  # self-delete, and database-reset (see admin-tools.tf) preserves exactly these, and nothing else,
  # when it wipes the Cognito pool. One local so both consumers can never disagree about which
  # accounts are reserved.
  #
  # The personless account is spliced in through a splat rather than named directly, because it is
  # not created in production (see cognito.tf) - the splat is simply empty there. Leaving it out was
  # a real bug for exactly one acceptance run: Terraform created the account, the suite's own reset
  # deleted it as an unrecognised user seconds later, and all six tests that sign in as it failed
  # with "Incorrect username or password" rather than anything about a missing account.
  reserved_account_emails = join(",", concat(
    [aws_cognito_user.demo.username, aws_cognito_user.e2e.username],
    aws_cognito_user.no_person[*].username,
  ))

  # ResolverDispatchHandler (see impl/src/main/java/com/mootmaker/handler/ResolverDispatchHandler.java)
  # is the single entry point for every AppSync direct-Lambda resolver, so it needs the union of
  # every env var any individual resolver handler used to need - including COGNITO_USER_POOL_ID
  # (previously only update_person's own function got this). Unlike post_confirmation_create_person,
  # this function is never itself referenced by aws_cognito_user_pool.this's lambda_config, so the
  # circular-dependency concern above doesn't apply here.
  resolver_lambda_env_vars = merge(local.admin_gated_env_vars, {
    COGNITO_USER_POOL_ID    = aws_cognito_user_pool.this.id
    RESERVED_ACCOUNT_EMAILS = local.reserved_account_emails
    # The API calling its own publishDaysInvalidated mutation over IAM-signed HTTP after a write -
    # AppSync has no server-side publish API, so a broadcast IS a mutation call. Set only for this
    # function: database-reset and database-repair run the same jar and neither has the appsync
    # grant, so DaysInvalidatedPublisher.fromEnvironment() degrades to a no-op there rather than
    # failing. Deliberately the raw AppSync URL rather than the custom domain, so a broadcast does
    # not depend on DNS or the ACM certificate being healthy.
    GRAPHQL_ENDPOINT = aws_appsync_graphql_api.this.uris["GRAPHQL"]
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
  # time_sleep.iam_role_propagation: IAM is eventually consistent and this function's role may not
  # be assumable yet - see iam.tf and mootmaker-api#26.
  depends_on = [aws_cloudwatch_log_group.lambda, time_sleep.iam_role_propagation]
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
  # time_sleep.iam_role_propagation: IAM is eventually consistent and this function's role may not
  # be assumable yet - see iam.tf and mootmaker-api#26.
  depends_on = [aws_cloudwatch_log_group.lambda, time_sleep.iam_role_propagation]
}

resource "aws_lambda_alias" "post_confirmation_create_person_live" {
  name             = "live"
  function_name    = aws_lambda_function.post_confirmation_create_person.function_name
  function_version = aws_lambda_function.post_confirmation_create_person.version
}

# Cognito's PreSignUp trigger - a separate function from post_confirmation_create_person above,
# deliberately: different job (rejecting a collision outright, before any user exists, versus
# creating a Person for one that's just been confirmed) and PreSignUpNameCollisionHandler's own
# doc explains why PostConfirmation can't do this job itself (a thrown exception there is logged
# and swallowed, not surfaced - the account already exists by then).
resource "aws_lambda_function" "pre_sign_up_name_collision" {
  function_name    = "${local.resource_prefix}-pre-sign-up-name-collision"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.PreSignUpNameCollisionHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15
  # See post_confirmation_create_person's identical comment on publish/snap_start/the "live" alias.
  publish = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = local.lambda_env_vars
  }

  # See post_confirmation_create_person's identical comment on log-group-before-function ordering.
  depends_on = [aws_cloudwatch_log_group.lambda, time_sleep.iam_role_propagation]
}

resource "aws_lambda_alias" "pre_sign_up_name_collision_live" {
  name             = "live"
  function_name    = aws_lambda_function.pre_sign_up_name_collision.function_name
  function_version = aws_lambda_function.pre_sign_up_name_collision.version
}

# A freshly published SnapStart version needs its own extra time before it can actually be invoked -
# separate from, and in addition to, time_sleep.iam_role_propagation (defined in iam.tf) above. AWS
# builds the snapshot asynchronously after publish; invoking too soon fails with Lambda's own
# ResourceConflictException, which Cognito surfaces as "PreSignUp invocation failed due to error
# ResourceConflictException" - seen for real deploying a fresh ephemeral environment, where
# aws_cognito_user.e2e/demo below invoke this function within seconds of it being published.
# post_confirmation_create_person carries the identical risk (same SnapStart setup) but has never hit
# it: nothing in this apply invokes PostConfirmation synchronously - only a later, real sign-up does,
# by which time the snapshot is long since ready. PreSignUp has no such luxury.
#
# This is a null_resource + local-exec poll, not a time_sleep like iam_role_propagation above -
# deliberately, and only after a fixed sleep proved unreliable in practice. A first attempt used
# time_sleep with a 60s duration; it passed locally (twice) but still failed in mootmaker-release's
# CI with the identical ResourceConflictException, because that run deploys mootmaker-api three times
# in parallel (release.yml builds api/webapp/demo-data concurrently, and both webapp and demo-data
# also deploy api as a dependency - see mootmaker#41's identical theory about parallel SnapStart
# publishing under contention). Snapshot creation time isn't a constant; it scales with how much
# other SnapStart work the account is doing at the same moment, so a fixed guess can never be safe at
# every concurrency level, only lucky at whatever level it was tuned against. Polling the actual
# readiness signal (SnapStart.OptimizationStatus turning "On" - confirmed via `aws lambda
# get-function --qualifier <version>` against a real, ready function) waits exactly as long as
# needed and no longer, regardless of contention.
#
# Triggers on the published version, matching time_sleep.iam_role_propagation's own reasoning: an
# unchanged function on an existing environment re-applies without paying this again, only a fresh
# publish (a new environment, or a real code change) does.
resource "null_resource" "pre_sign_up_snapstart_ready" {
  triggers = {
    version = aws_lambda_function.pre_sign_up_name_collision.version
  }

  provisioner "local-exec" {
    # POSIX sh, not bash: Terraform's local-exec runs via /bin/sh -c on Unix, and GitHub's Ubuntu
    # runners point that at dash, which has no -o pipefail (confirmed for real: "set: Illegal
    # option -o pipefail" - passed locally only because this workstation's /bin/sh happens to be
    # bash). Dropped rather than switched to an explicit bash interpreter, since nothing here pipes
    # anyway - set -eu alone already covers every failure mode this script has.
    command = <<-EOT
      set -eu
      for i in $(seq 1 120); do
        status="$(aws lambda get-function-configuration \
          --function-name '${aws_lambda_function.pre_sign_up_name_collision.function_name}' \
          --qualifier '${aws_lambda_function.pre_sign_up_name_collision.version}' \
          --query 'SnapStart.OptimizationStatus' --output text)"
        if [ "$status" = "On" ]; then
          exit 0
        fi
        sleep 5
      done
      echo "Timed out after 10m waiting for pre-sign-up-name-collision's SnapStart snapshot to become ready (last status: $status)" >&2
      exit 1
    EOT
  }

  depends_on = [aws_lambda_alias.pre_sign_up_name_collision_live]
}
