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
      # two can never drift under normal operation.
      "dynamodb:TransactWriteItems",
      # No dynamodb:DeleteItem: that was only ever needed by ResetHandler, which moved out to
      # mootmaker-tools/database-reset (its own Lambda, with its own narrowly-scoped role) - see
      # the README's "Reset and real user accounts" section.
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
# (propagates a Person rename to Cognito's own name attribute) both call AdminUpdateUserAttributes.
data "aws_iam_policy_document" "lambda_cognito_access" {
  statement {
    actions   = ["cognito-idp:AdminUpdateUserAttributes"]
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
    resources = [
      aws_lambda_function.list_rooms.arn,
      aws_lambda_function.list_people.arn,
      aws_lambda_function.create_room.arn,
      aws_lambda_function.update_room.arn,
      aws_lambda_function.create_person.arn,
      aws_lambda_function.update_person.arn,
      aws_lambda_function.my_person.arn,
      aws_lambda_function.list_meetings.arn,
      aws_lambda_function.create_meeting.arn,
    ]
  }
}

resource "aws_iam_role_policy" "appsync_invoke_lambda" {
  name   = "${local.resource_prefix}-appsync-invoke-lambda"
  role   = aws_iam_role.appsync_lambda_invoke.id
  policy = data.aws_iam_policy_document.appsync_invoke_lambda.json
}
