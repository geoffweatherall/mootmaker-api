data "aws_caller_identity" "current" {}

resource "aws_cognito_user_pool" "this" {
  name = "${local.resource_prefix}-users"

  # Users sign in with their email address; Cognito emails a verification code
  # on sign-up and for password resets (COGNITO_DEFAULT sender, fine at demo
  # volumes).
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  # Deliberately loose: this is a demo system, not a real business, and the whole point is to let
  # anyone try it out via the publicly-known demo user below (see aws_cognito_user.demo) without
  # needing to sign up first. Still requires a lowercase letter and a number at 10+ characters -
  # exactly what that demo user's randomly-generated password satisfies - just not the
  # upper-case/symbol mixing a real product would want.
  password_policy {
    minimum_length    = 10
    require_lowercase = true
    require_uppercase = false
    require_numbers   = true
    require_symbols   = false
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  # Backs the standard/admin user class: read into the ID token as the custom:class claim (see
  # webapp client's read_attributes below), set to "standard" server-side for every new sign-up by
  # PostConfirmationCreatePersonHandler, and never client-writable (see write_attributes below) so
  # a user can never self-promote to admin. Adding a new custom attribute to an existing user pool
  # is a supported in-place AddCustomAttributes operation, not a replacement.
  schema {
    name                = "class"
    attribute_data_type = "String"
    mutable             = true
    required            = false

    string_attribute_constraints {
      min_length = 1
      max_length = 20
    }
  }

  # Creates the Person record for a user once their email is confirmed - see
  # PostConfirmationCreatePersonHandler for why this runs post-confirmation rather than
  # pre-sign-up (email isn't verified yet at that point).
  # Points at the "live" alias (see lambda.tf) rather than the function directly, since SnapStart
  # only ever applies to a published version, never $LATEST.
  lambda_config {
    post_confirmation = aws_lambda_alias.post_confirmation_create_person_live.arn
  }
}

# Cognito invokes triggers directly via a resource-based Lambda permission (unlike AppSync's
# datasources, which assume an IAM role), scoped to just this user pool. The qualifier scopes the
# grant to the "live" alias specifically, matching lambda_config above.
resource "aws_lambda_permission" "cognito_invoke_post_confirmation" {
  statement_id  = "AllowCognitoInvokePostConfirmation"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.post_confirmation_create_person.function_name
  qualifier     = aws_lambda_alias.post_confirmation_create_person_live.name
  principal     = "cognito-idp.amazonaws.com"
  source_arn    = aws_cognito_user_pool.this.arn
}

# Public (no secret) client used by the mootmaker-webapp browser SPA.
resource "aws_cognito_user_pool_client" "webapp" {
  name         = "${local.resource_prefix}-webapp"
  user_pool_id = aws_cognito_user_pool.this.id

  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  prevent_user_existence_errors = "ENABLED"

  # custom:class only appears in a user's ID token if the client reading it has explicit read
  # permission - unlike standard attributes, which are readable by default. This list isn't
  # additive over Cognito's default, so it has to restate email/name too (already relied on by
  # AuthProvider's currentUserEmail()/currentUserName()) alongside the new custom:class.
  read_attributes = ["email", "email_verified", "name", "custom:class"]
  # Deliberately excludes custom:class: a user must never be able to set their own class by
  # calling Cognito's UpdateUserAttributes from the browser SDK - only
  # PostConfirmationCreatePersonHandler/UpdatePersonHandler may set it, via the Admin API.
  write_attributes = ["name"]
}

# Hosted domain for the user pool - only needed for the OAuth2 token endpoint
# (https://<domain>.auth.<region>.amazoncognito.com/oauth2/token) that the
# acceptance tests use. The account id makes the prefix globally unique.
resource "aws_cognito_user_pool_domain" "this" {
  domain       = "${local.resource_prefix}-${data.aws_caller_identity.current.account_id}"
  user_pool_id = aws_cognito_user_pool.this.id
}

# Resource server defining the custom scopes granted to client_credentials tokens.
resource "aws_cognito_resource_server" "api" {
  identifier   = "${local.resource_prefix}-api"
  name         = "${local.resource_prefix}-api"
  user_pool_id = aws_cognito_user_pool.this.id

  scope {
    scope_name        = "execute"
    scope_description = "Full access to the mootmaker GraphQL API"
  }

  # M2M tooling (sample-data-generator, the acceptance tests below) has no Cognito user behind it
  # at all, so it can never carry a custom:class claim the way a real signed-in user's ID token
  # does - this scope is Identity.requireAdmin's equivalent for that caller. See
  # mootmaker-tools/sample-data-generator and this project's README for why that tool still needs
  # to create rooms/people now that those mutations are admin-only.
  scope {
    scope_name        = "admin"
    scope_description = "Admin-equivalent access (room/person maintenance) for M2M tooling"
  }
}

# Confidential (secret-holding) client for the /verify acceptance tests: the
# OAuth2 client_credentials flow exchanges the id/secret for a JWT access token
# without any human user or password being involved.
resource "aws_cognito_user_pool_client" "acceptance_tests" {
  name            = "${local.resource_prefix}-acceptance-tests"
  user_pool_id    = aws_cognito_user_pool.this.id
  generate_secret = true

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes = [
    "${aws_cognito_resource_server.api.identifier}/execute",
    "${aws_cognito_resource_server.api.identifier}/admin",
  ]
  supported_identity_providers = ["COGNITO"]
}

# Pre-confirmed user for the webapp's Playwright end-to-end tests, which sign
# in through the real UI (the browser sign-in flow inherently needs a user,
# unlike the API acceptance tests which use client_credentials above).
resource "random_password" "e2e_user" {
  length           = 20
  min_lower        = 2
  min_upper        = 2
  min_numeric      = 2
  min_special      = 2
  override_special = "!@#$%^&*()-_=+"
}

resource "aws_cognito_user" "e2e" {
  user_pool_id = aws_cognito_user_pool.this.id
  username     = "e2e-tests@example.com"
  password     = random_password.e2e_user.result

  attributes = {
    email          = "e2e-tests@example.com"
    email_verified = "true"
    # Created directly rather than through sign-up, so it skips PostConfirmationCreatePersonHandler
    # (the same reason it has no Person - see below) and would otherwise have no class at all; set
    # explicitly here for parity with a real signed-up user, who always gets one.
    "custom:class" = "standard"
  }
}

# Password for the demo user below: random (like random_password.e2e_user above) rather than a
# fixed, guessable word - an earlier fixed value ("demo1234") turned out to be on Google's list of
# known-compromised passwords, which Cognito doesn't check for but is still worth avoiding.
# Restricted to lowercase letters and digits (no uppercase/symbols) purely so it's easy to read
# and type by hand; it's shown in the clear on the webapp's home page regardless, so there's no
# security reason to make it harder to type.
resource "random_password" "demo_user" {
  length      = 10
  min_lower   = 1
  min_numeric = 1
  upper       = false
  special     = false
}

# Pre-confirmed, publicly-known demo user: this is a demo system rather than a real business, so
# anyone can sign in as this user - no sign-up needed - to try out the app. The webapp's home page
# fetches these credentials at deploy time (see mootmaker-webapp's deploy.sh) and displays them
# to signed-out visitors, offering a one-click sign-in. Deliberately NOT gated by environment name
# (e.g. unlike reset/sample-data-generator) - this demo user is meant to exist even in a
# "production" deployment, since making the app easy to try is the point.
resource "aws_cognito_user" "demo" {
  user_pool_id = aws_cognito_user_pool.this.id
  username     = "demo@mootmaker.com"
  password     = random_password.demo_user.result

  attributes = {
    email          = "demo@mootmaker.com"
    email_verified = "true"
    # The demo user is the one always-present admin, so there's something to sign in as and
    # exercise room/person maintenance without needing a real sign-up first.
    "custom:class" = "admin"
  }
}

# The demo user above is created directly by Terraform rather than through the sign-up/confirm
# API calls, so it never fires PostConfirmationCreatePersonHandler (see "Sign-up creates a linked
# Person" in the README) and would otherwise have no Person - showing up nameless in the webapp
# and being wiped by every mootmaker-tools/database-reset run, since it only preserves people with
# a cognitoSub. This writes one directly, in the same shape as Person.toItem(), linked via
# cognitoSub to aws_cognito_user.demo's sub.
resource "random_uuid" "demo_person_id" {}

resource "aws_dynamodb_table_item" "demo_person" {
  table_name = aws_dynamodb_table.people.name
  hash_key   = aws_dynamodb_table.people.hash_key

  item = jsonencode({
    id         = { S = random_uuid.demo_person_id.result }
    name       = { S = "Demo Strater" }
    cognitoSub = { S = aws_cognito_user.demo.sub }
  })
}
