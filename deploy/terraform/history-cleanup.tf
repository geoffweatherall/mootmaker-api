# Weekly deletion of meeting history older than the retention window.
#
# This exists because principles.md requires the bill to stay flat under steady usage, and meetings
# were the one thing that grew with time rather than with use. See
# ../../../mootmaker/designs/graphql-schema-and-caching.md's "Retention" section for why this is an
# explicit job rather than a DynamoDB TTL - the short version is that TTL bakes the policy into every
# item, cannot delete a day and its pointers atomically, and does not even act as a read boundary,
# since expired-but-not-yet-deleted items are still returned by reads.

data "aws_iam_policy_document" "history_cleanup_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "history_cleanup_exec" {
  name               = "${local.resource_prefix}-history-cleanup-exec"
  assume_role_policy = data.aws_iam_policy_document.history_cleanup_assume_role.json
}

resource "aws_iam_role_policy_attachment" "history_cleanup_basic_execution" {
  role       = aws_iam_role.history_cleanup_exec.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# The meetings table and nothing else. Unlike mootmaker-demo-data this job talks to DynamoDB rather
# than to the API, so it needs no SSM parameters, no M2M credentials and no Cognito token endpoint -
# the whole dependency list is this one grant.
data "aws_iam_policy_document" "history_cleanup_access" {
  statement {
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:Scan",
      "dynamodb:DeleteItem",
      "dynamodb:TransactWriteItems",
    ]
    resources = [aws_dynamodb_table.meetings.arn]
  }
}

resource "aws_iam_role_policy" "history_cleanup_access" {
  name   = "${local.resource_prefix}-history-cleanup-access"
  role   = aws_iam_role.history_cleanup_exec.id
  policy = data.aws_iam_policy_document.history_cleanup_access.json
}

resource "aws_lambda_function" "history_cleanup" {
  function_name    = "${local.resource_prefix}-history-cleanup"
  role             = aws_iam_role.history_cleanup_exec.arn
  handler          = "com.mootmaker.handler.HistoryCleanupHandler::handleRequest"
  runtime          = "java25"
  filename         = local.lambda_jar_path
  source_code_hash = local.lambda_jar_hash
  memory_size      = 512
  # Not the 900s the admin tools use: this job's work is bounded by construction. The horizon and
  # retention cap the table at 217 day items, so there is no input that makes it run long, and a
  # lower timeout means a hang surfaces as a failure rather than as fifteen quiet minutes.
  timeout = 300

  environment {
    variables = {
      MEETINGS_TABLE_NAME = aws_dynamodb_table.meetings.name
    }
  }

  depends_on = [aws_cloudwatch_log_group.lambda, time_sleep.iam_role_propagation]
}

# Weekly, pairing with the Monday-aligned boundary: one run, one Monday, one week of data.
#
# Costs nothing. Scheduled rules targeting an AWS service directly are not billed - only publishing
# custom events is - and the invocation falls inside Lambda's always-free tier. Confirmed against the
# bill: EventBridge does not appear as a line item at all despite demo-data running daily.
resource "aws_cloudwatch_event_rule" "history_cleanup" {
  name                = "${local.resource_prefix}-history-cleanup"
  description         = "Deletes meeting history older than the retention window"
  schedule_expression = "cron(17 14 ? * MON *)"

  # DISABLED in ephemeral environments, and not only to save money: an ephemeral environment never
  # lives long enough to accumulate anything, and a schedule firing mid-run would make its acceptance
  # tests nondeterministic. The function is still deployed everywhere, so tests invoke it directly -
  # exactly as they already do for database-reset.
  state = contains(["test", "production"], var.environment) ? "ENABLED" : "DISABLED"
}

resource "aws_cloudwatch_event_target" "history_cleanup" {
  rule = aws_cloudwatch_event_rule.history_cleanup.name
  arn  = aws_lambda_function.history_cleanup.arn

  # Explicit rather than letting EventBridge send its own event envelope, so the handler's payload is
  # a contract rather than an accident - and so a scheduled run and a hand-invoked one are identical.
  input = jsonencode({})
}

resource "aws_lambda_permission" "history_cleanup_events" {
  statement_id  = "AllowEventBridgeInvokeHistoryCleanup"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.history_cleanup.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.history_cleanup.arn
}
