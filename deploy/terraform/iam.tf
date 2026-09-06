data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "lambda_exec" {
  name               = "${local.resource_prefix}-lambda-exec"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy_attachment" "lambda_basic_execution" {
  role       = aws_iam_role.lambda_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

data "aws_iam_policy_document" "lambda_dynamodb_access" {
  statement {
    actions = [
      "dynamodb:GetItem",
      "dynamodb:BatchGetItem",
      "dynamodb:PutItem",
      "dynamodb:Scan",
      "dynamodb:Query",
      # CreateMeetingHandler writes a meeting and its meeting-participants rows atomically so the
      # two can never drift under normal operation; DeleteMyAccountHandler's meeting-cancellation
      # cascade uses the same transactional Delete/Put pattern.
      "dynamodb:TransactWriteItems",
      # DeleteMyAccountHandler deletes the caller's own Person item directly (not via
      # TransactWriteItems, since it isn't part of any meeting cascade) - re-added after having been
      # removed when ResetHandler (its only prior user) moved out to mootmaker-admin-tools/database-reset
      # (its own Lambda, with its own narrowly-scoped role) - see the README's "Reset and real user
      # accounts" section.
      "dynamodb:DeleteItem",
      # Metadata-only, no items read/written - used by DynamoDbClientProvider's SnapStart
      # afterRestore hook purely to re-establish the DynamoDB connection/credentials before the
      # first real request reaches the handler.
      "dynamodb:DescribeTable",
    ]
    resources = [
      aws_dynamodb_table.rooms.arn,
      aws_dynamodb_table.people.arn,
      aws_dynamodb_table.meetings.arn,
      aws_dynamodb_table.meeting_participants.arn,
      # DynamoDB treats a table's GSIs as separate resources from the table itself, so querying
      # them needs its own grant even though the handler already has access to the table: the
      # people table's cognitoSub-index (see PostConfirmationCreatePersonHandler), and the
      # meetings table's bucket-startTime-index and roomId-startTime-index (ListMeetingsHandler's
      # filter and CreateMeetingHandler's overlap check, respectively).
      "${aws_dynamodb_table.people.arn}/index/*",
      "${aws_dynamodb_table.meetings.arn}/index/*",
    ]
  }
}

resource "aws_iam_role_policy" "lambda_dynamodb_access" {
  name   = "${local.resource_prefix}-lambda-dynamodb-access"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.lambda_dynamodb_access.json
}

# PostConfirmationCreatePersonHandler (sets a new sign-up's default class) and UpdatePersonHandler
# (propagates a Person rename to Cognito's own name attribute) both call AdminUpdateUserAttributes;
# DeleteMyAccountHandler calls AdminDeleteUser to remove the caller's own Cognito user entirely.
data "aws_iam_policy_document" "lambda_cognito_access" {
  statement {
    actions   = ["cognito-idp:AdminUpdateUserAttributes", "cognito-idp:AdminDeleteUser"]
    resources = [aws_cognito_user_pool.this.arn]
  }
}

resource "aws_iam_role_policy" "lambda_cognito_access" {
  name   = "${local.resource_prefix}-lambda-cognito-access"
  role   = aws_iam_role.lambda_exec.id
  policy = data.aws_iam_policy_document.lambda_cognito_access.json
}

data "aws_iam_policy_document" "appsync_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["appsync.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "appsync_lambda_invoke" {
  name               = "${local.resource_prefix}-appsync-lambda-invoke"
  assume_role_policy = data.aws_iam_policy_document.appsync_assume_role.json
}

data "aws_iam_policy_document" "appsync_invoke_lambda" {
  statement {
    actions = ["lambda:InvokeFunction"]
    # The "live" alias ARN, not the function ARN - AppSync's data source invokes through the
    # alias (see appsync.tf), and IAM requires an exact resource match, so a grant on the function
    # ARN alone wouldn't cover invoking the alias.
    resources = [
      aws_lambda_alias.resolvers_live.arn,
    ]
  }
}

resource "aws_iam_role_policy" "appsync_invoke_lambda" {
  name   = "${local.resource_prefix}-appsync-invoke-lambda"
  role   = aws_iam_role.appsync_lambda_invoke.id
  policy = data.aws_iam_policy_document.appsync_invoke_lambda.json
}

# --- IAM propagation gate -------------------------------------------------------------------
#
# IAM is eventually consistent: a role can exist, and be entirely correct, before Lambda is able to
# assume it. `CreateFunction` then fails with
#
#     InvalidParameterValueException: The role defined for the function cannot be assumed by Lambda
#
# which is what broke the first real release standing up `test` (mootmaker-api#26). The AWS provider
# does retry that error, but its window is not always enough against a genuinely fresh account-side
# role, and a failure here fails the whole release for a reason unrelated to the change being
# released.
#
# This is deliberately NOT a fixed cost on every deploy, which is how the issue originally framed
# the trade. `time_sleep` is re-created only when its triggers change, and the triggers here are the
# role ARNs - so applying to an existing environment waits for nothing, and only creating a new one
# pays the pause. That is exactly the case with the race: every release stands up three fresh
# ephemeral environments for acceptance, so this runs several times per release and never on an
# update to `test` or `production`.
#
# Triggers rather than a bare depends_on, so that a role which is ever replaced re-arms the wait
# instead of silently skipping it.
resource "time_sleep" "iam_role_propagation" {
  create_duration = "30s"

  triggers = {
    lambda_exec     = aws_iam_role.lambda_exec.arn
    database_reset  = aws_iam_role.database_reset_exec.arn
    database_repair = aws_iam_role.database_repair_exec.arn
  }

  # Only the managed-policy attachments, deliberately. The inline aws_iam_role_policy resources are
  # NOT listed: their policy documents reference the Cognito pool, which in turn references the
  # post-confirmation function, so waiting on them here forms a dependency cycle.
  #
  # Excluding them is correct rather than merely convenient. The race is `CreateFunction` rejecting
  # a role it cannot yet assume, which is entirely about the role's own trust policy. Permission
  # policies are needed at invoke time, not create time, and every invocation happens long after
  # `terraform apply` has returned.
  depends_on = [
    aws_iam_role_policy_attachment.lambda_basic_execution,
    aws_iam_role_policy_attachment.database_reset_basic_execution,
    aws_iam_role_policy_attachment.database_repair_basic_execution,
  ]
}
