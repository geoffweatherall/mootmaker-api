# Decision 11: bring this component's log groups under Terraform, tagged and retention-capped.
#
# Lambda and AppSync both auto-create their log groups on first invocation if none exists - but
# with NEVER-EXPIRE retention and no tags. That is the default this file exists to correct: an
# untagged group that grows forever is both a cost leak and unfindable when troubleshooting.
#
# IMPORTANT for test and production: these groups ALREADY EXIST there, auto-created and unmanaged,
# so the first apply that introduces these resources must be preceded by `terraform import` for
# each one - CloudWatch rejects creating a log group whose name is taken. A fresh ephemeral
# environment has no such problem, since nothing exists there yet. See the design's Rollout step 12
# and deploy/import-log-groups.sh.

locals {
  # Names are built from resource_prefix rather than read off the function resources, and that is
  # load-bearing rather than stylistic.
  #
  # Referencing aws_lambda_function.*.function_name makes each log group depend on its FUNCTION, so
  # Terraform creates the function first. SnapStart then publishes a version, which executes the
  # function's init to take its snapshot - and that invocation makes Lambda auto-create the log
  # group. Terraform's own create then fails with ResourceAlreadyExistsException, on a supposedly
  # empty environment. That is exactly how the first release carrying this file failed.
  #
  # Deriving the names independently inverts the dependency: the groups are created first, with the
  # retention and tags already set, and the functions below declare depends_on so Lambda finds a
  # group waiting rather than creating its own.
  lambda_log_groups = toset([
    "${local.resource_prefix}-resolvers",
    "${local.resource_prefix}-post-confirmation-create-person",
    "${local.resource_prefix}-database-reset",
    "${local.resource_prefix}-database-repair",
  ])
}

resource "aws_cloudwatch_log_group" "lambda" {
  for_each = local.lambda_log_groups

  # The name Lambda itself would use. Declaring it explicitly is what lets Terraform own the
  # retention and tags rather than inheriting the service's never-expire default.
  name              = "/aws/lambda/${each.value}"
  retention_in_days = var.log_retention_days

  tags = {
    Project     = var.project_name
    Component   = "mootmaker-api"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

# AppSync's own request/resolver logs - the other half of "the relevant Lambda execution logs AND
# AppSync's own logs alongside them" that the Definition of done asks for. Without log_config,
# AppSync logs nothing at all, so a GraphQL error that never reaches a resolver leaves no trace.
resource "aws_cloudwatch_log_group" "appsync" {
  name              = "/aws/appsync/apis/${aws_appsync_graphql_api.this.id}"
  retention_in_days = var.log_retention_days

  tags = {
    Project     = var.project_name
    Component   = "mootmaker-api"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

# Wildcard-scoped deliberately, to break a dependency cycle: the role must exist before the API is
# created (log_config references it), but the log group's name contains the API's own id, which
# does not exist until the API does. Scoping the role to that group would make the role depend on
# the API and the API depend on the role. The design calls this out as the accepted resolution.
resource "aws_iam_role" "appsync_logging" {
  name = "${local.resource_prefix}-appsync-logging"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "appsync.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Project     = var.project_name
    Component   = "mootmaker-api"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

resource "aws_iam_role_policy" "appsync_logging" {
  name = "appsync-logging"
  role = aws_iam_role.appsync_logging.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents",
      ]
      Resource = "arn:aws:logs:${var.aws_region}:*:log-group:/aws/appsync/*"
    }]
  })
}
