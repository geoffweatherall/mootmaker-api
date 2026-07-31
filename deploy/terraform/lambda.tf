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
  # resource server would be a circular dependency for that one function. Only the four
  # admin-gated handlers below get this extra variable, via their own merge().
  admin_gated_env_vars = merge(local.lambda_env_vars, {
    COGNITO_ADMIN_SCOPE = "${aws_cognito_resource_server.api.identifier}/admin"
  })
}

resource "aws_lambda_function" "list_rooms" {
  function_name    = "${local.resource_prefix}-list-rooms"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.ListRoomsHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}

resource "aws_lambda_function" "list_people" {
  function_name    = "${local.resource_prefix}-list-people"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.ListPeopleHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}

resource "aws_lambda_function" "suggest_room" {
  function_name    = "${local.resource_prefix}-suggest-room"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.SuggestRoomHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}

resource "aws_lambda_function" "create_room" {
  function_name    = "${local.resource_prefix}-create-room"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.CreateRoomHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.admin_gated_env_vars
  }
}

resource "aws_lambda_function" "update_room" {
  function_name    = "${local.resource_prefix}-update-room"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.UpdateRoomHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.admin_gated_env_vars
  }
}

resource "aws_lambda_function" "create_person" {
  function_name    = "${local.resource_prefix}-create-person"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.CreatePersonHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.admin_gated_env_vars
  }
}

resource "aws_lambda_function" "update_person" {
  function_name    = "${local.resource_prefix}-update-person"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.UpdatePersonHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  # Also needs COGNITO_USER_POOL_ID (to propagate a rename to Cognito's own name attribute).
  # Unlike PostConfirmationCreatePersonHandler (which reads userPoolId from its own Cognito
  # trigger event instead - see that handler's comment - specifically to avoid a circular
  # dependency), this function's own AppSync event has no such field, and it isn't itself
  # referenced by aws_cognito_user_pool.this, so adding this here is safe.
  environment {
    variables = merge(local.admin_gated_env_vars, {
      COGNITO_USER_POOL_ID = aws_cognito_user_pool.this.id
    })
  }
}

resource "aws_lambda_function" "post_confirmation_create_person" {
  function_name    = "${local.resource_prefix}-post-confirmation-create-person"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.PostConfirmationCreatePersonHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}

resource "aws_lambda_function" "my_person" {
  function_name    = "${local.resource_prefix}-my-person"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.MyPersonHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}

resource "aws_lambda_function" "list_meetings" {
  function_name    = "${local.resource_prefix}-list-meetings"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.ListMeetingsHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}

resource "aws_lambda_function" "create_meeting" {
  function_name    = "${local.resource_prefix}-create-meeting"
  role             = aws_iam_role.lambda_exec.arn
  handler          = "com.mootmaker.handler.CreateMeetingHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  timeout          = 15

  environment {
    variables = local.lambda_env_vars
  }
}
