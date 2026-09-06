# database-reset and database-repair: merged into this repo from mootmaker-admin-tools (see
# ../../../mootmaker/designs/archive/admin-tools-into-api.md). Each is its own Lambda function, built from
# the same shaded jar as the resolvers/post-confirmation functions (see lambda.tf), but - unlike
# those - each gets its own narrowly-scoped IAM role rather than the shared aws_iam_role.lambda_exec
# every resolver uses, matching the least-privilege role each tool already had as a standalone
# Lambda before this move. Neither gets an aws_lambda_alias/SnapStart: nothing invokes either
# frequently enough for cold-start latency to matter.

# --- database-reset ---

data "aws_iam_policy_document" "database_reset_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "database_reset_exec" {
  name               = "${local.resource_prefix}-database-reset-exec"
  assume_role_policy = data.aws_iam_policy_document.database_reset_assume_role.json
}

resource "aws_iam_role_policy_attachment" "database_reset_basic_execution" {
  role       = aws_iam_role.database_reset_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Scoped to exactly what reset does: Scan+DeleteItem on the four tables it clears, and
# ListUsers+AdminDeleteUser on the pool for the Cognito wipe. The wipe itself is skipped in
# production (see ALLOW_COGNITO_WIPE below), but that's reset's own runtime guard, not an IAM
# boundary - the permission grant doesn't vary by environment.
data "aws_iam_policy_document" "database_reset_access" {
  statement {
    sid     = "DatabaseResetTableAccess"
    actions = ["dynamodb:Scan", "dynamodb:DeleteItem"]
    resources = [
      aws_dynamodb_table.rooms.arn,
      aws_dynamodb_table.people.arn,
      aws_dynamodb_table.meetings.arn,
      aws_dynamodb_table.meeting_participants.arn,
    ]
  }

  statement {
    sid       = "DatabaseResetCognitoAccess"
    actions   = ["cognito-idp:ListUsers", "cognito-idp:AdminDeleteUser"]
    resources = [aws_cognito_user_pool.this.arn]
  }
}

resource "aws_iam_role_policy" "database_reset_access" {
  name   = "${local.resource_prefix}-database-reset-access"
  role   = aws_iam_role.database_reset_exec.id
  policy = data.aws_iam_policy_document.database_reset_access.json
}

resource "aws_lambda_function" "database_reset" {
  # Matches the deterministic name this repo's own verify.sh and mootmaker-demo-data's acceptance
  # suite already compute (<environment>-mootmaker-database-reset) - neither needed to change when
  # this Lambda moved here from mootmaker-admin-tools. Note mootmaker-demo-data itself never
  # invokes this: only its test harness does, to clear an environment before seeding it.
  function_name    = "${local.resource_prefix}-database-reset"
  role             = aws_iam_role.database_reset_exec.arn
  handler          = "com.mootmaker.handler.DatabaseResetHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  # The AWS maximum, not a smaller guessed number (mootmaker-admin-tools configured 300s) - see
  # designs/admin-tools-into-api.md. ConcurrencyUtils.runInParallel (bounded at 8) already exists
  # specifically to stay inside this ceiling as stored data volume grows, so the ceiling itself is
  # the number worth configuring against. Costs nothing extra - Lambda bills for actual duration,
  # not this setting. Every caller's own client-side timeout has to be raised to match, or a
  # legitimately-long run gets reported as a failure while this Lambda keeps running regardless.
  timeout = 900

  # AWS_REGION is deliberately not set here - Lambda sets it automatically to wherever the
  # function is actually deployed.
  environment {
    variables = merge(local.lambda_env_vars, {
      COGNITO_USER_POOL_ID = aws_cognito_user_pool.this.id
      # Computed once at deploy time from which environment this is, not read from the invoke
      # payload - see designs/admin-tools-into-api.md's "Choices you had me make". Structurally
      # impossible to override per-invocation, the same way deploy.sh itself decides
      # production-ness from the environment argument rather than trusting the caller.
      ALLOW_COGNITO_WIPE      = tostring(var.environment != "production")
      RESERVED_ACCOUNT_EMAILS = local.reserved_account_emails
    })
  }

  # The log group must exist BEFORE this function can be invoked. SnapStart
  # publishes a version by executing the function's init, and that invocation would
  # otherwise make Lambda auto-create the group - which then collides with
  # Terraform's own create. See logs.tf for the full reasoning.
  # time_sleep.iam_role_propagation: IAM is eventually consistent and this function's role may not
  # be assumable yet - see iam.tf and mootmaker-api#26.
  depends_on = [aws_cloudwatch_log_group.lambda, time_sleep.iam_role_propagation]
}

# --- database-repair ---

data "aws_iam_policy_document" "database_repair_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "database_repair_exec" {
  name               = "${local.resource_prefix}-database-repair-exec"
  assume_role_policy = data.aws_iam_policy_document.database_repair_assume_role.json
}

resource "aws_iam_role_policy_attachment" "database_repair_basic_execution" {
  role       = aws_iam_role.database_repair_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "database_repair_access" {
  statement {
    sid       = "DatabaseRepairListCognitoUsers"
    actions   = ["cognito-idp:ListUsers"]
    resources = [aws_cognito_user_pool.this.arn]
  }

  statement {
    sid     = "DatabaseRepairPeopleTableAccess"
    actions = ["dynamodb:Query", "dynamodb:PutItem"]
    resources = [
      aws_dynamodb_table.people.arn,
      # cognitoSub-index is a separate resource from the table itself as far as IAM is concerned.
      "${aws_dynamodb_table.people.arn}/index/*",
    ]
  }

  statement {
    sid       = "DatabaseRepairMeetingsTableRead"
    actions   = ["dynamodb:Scan"]
    resources = [aws_dynamodb_table.meetings.arn]
  }

  statement {
    sid       = "DatabaseRepairMeetingParticipantsTableAccess"
    actions   = ["dynamodb:Scan", "dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [aws_dynamodb_table.meeting_participants.arn]
  }
}

resource "aws_iam_role_policy" "database_repair_access" {
  name   = "${local.resource_prefix}-database-repair-access"
  role   = aws_iam_role.database_repair_exec.id
  policy = data.aws_iam_policy_document.database_repair_access.json
}

resource "aws_lambda_function" "database_repair" {
  # Matches the deterministic name this repo's own verify.sh/README would compute
  # (<environment>-mootmaker-database-repair) - unchanged from mootmaker-admin-tools.
  function_name    = "${local.resource_prefix}-database-repair"
  role             = aws_iam_role.database_repair_exec.arn
  handler          = "com.mootmaker.handler.DatabaseRepairHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  # The AWS maximum - see the equivalent comment on database_reset above.
  timeout = 900

  environment {
    variables = {
      COGNITO_USER_POOL_ID            = aws_cognito_user_pool.this.id
      PEOPLE_TABLE_NAME               = aws_dynamodb_table.people.name
      MEETINGS_TABLE_NAME             = aws_dynamodb_table.meetings.name
      MEETING_PARTICIPANTS_TABLE_NAME = aws_dynamodb_table.meeting_participants.name
    }
  }

  # The log group must exist BEFORE this function can be invoked. SnapStart
  # publishes a version by executing the function's init, and that invocation would
  # otherwise make Lambda auto-create the group - which then collides with
  # Terraform's own create. See logs.tf for the full reasoning.
  # time_sleep.iam_role_propagation: IAM is eventually consistent and this function's role may not
  # be assumable yet - see iam.tf and mootmaker-api#26.
  depends_on = [aws_cloudwatch_log_group.lambda, time_sleep.iam_role_propagation]
}
