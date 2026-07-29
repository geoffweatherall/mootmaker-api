resource "aws_appsync_graphql_api" "this" {
  name                = "${local.resource_prefix}-api"
  authentication_type = "AMAZON_COGNITO_USER_POOLS"
  schema              = file("${path.module}/../../api/mootmaker.graphql")

  user_pool_config {
    user_pool_id   = aws_cognito_user_pool.this.id
    aws_region     = var.aws_region
    default_action = "ALLOW"
  }
}

resource "aws_appsync_datasource" "list_rooms" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "ListRoomsDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.list_rooms.arn
  }
}

resource "aws_appsync_datasource" "list_people" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "ListPeopleDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.list_people.arn
  }
}

resource "aws_appsync_datasource" "create_room" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "CreateRoomDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.create_room.arn
  }
}

resource "aws_appsync_datasource" "update_room" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "UpdateRoomDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.update_room.arn
  }
}

resource "aws_appsync_datasource" "create_person" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "CreatePersonDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.create_person.arn
  }
}

resource "aws_appsync_datasource" "update_person" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "UpdatePersonDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.update_person.arn
  }
}

resource "aws_appsync_datasource" "my_person" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "MyPersonDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.my_person.arn
  }
}

resource "aws_appsync_datasource" "list_meetings" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "ListMeetingsDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.list_meetings.arn
  }
}

resource "aws_appsync_datasource" "create_meeting" {
  api_id           = aws_appsync_graphql_api.this.id
  name             = "CreateMeetingDataSource"
  type             = "AWS_LAMBDA"
  service_role_arn = aws_iam_role.appsync_lambda_invoke.arn

  lambda_config {
    function_arn = aws_lambda_function.create_meeting.arn
  }
}

locals {
  direct_lambda_request_template  = "{\"version\":\"2018-05-29\",\"operation\":\"Invoke\",\"payload\":$util.toJson($ctx)}"
  direct_lambda_response_template = "$util.toJson($ctx.result)"
}

resource "aws_appsync_resolver" "rooms" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "rooms"
  data_source       = aws_appsync_datasource.list_rooms.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "people" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "people"
  data_source       = aws_appsync_datasource.list_people.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_room" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createRoom"
  data_source       = aws_appsync_datasource.create_room.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "update_room" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "updateRoom"
  data_source       = aws_appsync_datasource.update_room.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_person" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createPerson"
  data_source       = aws_appsync_datasource.create_person.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "update_person" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "updatePerson"
  data_source       = aws_appsync_datasource.update_person.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "my_person" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "myPerson"
  data_source       = aws_appsync_datasource.my_person.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "meetings" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Query"
  field             = "meetings"
  data_source       = aws_appsync_datasource.list_meetings.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}

resource "aws_appsync_resolver" "create_meeting" {
  api_id            = aws_appsync_graphql_api.this.id
  type              = "Mutation"
  field             = "createMeeting"
  data_source       = aws_appsync_datasource.create_meeting.name
  request_template  = local.direct_lambda_request_template
  response_template = local.direct_lambda_response_template
}
