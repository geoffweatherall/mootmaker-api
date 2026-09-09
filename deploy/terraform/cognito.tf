data "aws_caller_identity" "current" {}

# Verified in mootmaker-domain (deploy/terraform/ses.tf) - referenced here via a `data` source
# rather than a hard remote-state dependency, the same loose-coupling pattern
# mootmaker-email-testing's ses.tf already uses for the same identity (its own receiving side), and
# that mootmaker-api/mootmaker-webapp already use to find mootmaker-domain's hosted zone (data
# "aws_route53_zone"). Same-account reference, so no cross-account SES identity policy is needed.
data "aws_ses_domain_identity" "mail" {
  domain = "mail.mootmaker.com"
}

resource "aws_cognito_user_pool" "this" {
  name = "${local.resource_prefix}-users"

  # Users sign in with their email address; Cognito emails a verification code on sign-up and for
  # password resets. Sent via the verified mail.mootmaker.com SES identity (email_configuration
  # below) rather than Cognito's own COGNITO_DEFAULT sender - that built-in sender has a low,
  # undocumented daily cap shared account-wide across every user pool, which real usage plus the
  # acceptance suite's own account creation started hitting (see testing-strategy.md). SES's own
  # quota is governed by ../../../mootmaker-domain and this AWS account's own SES sending limits
  # instead, which are far higher.
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  email_configuration {
    email_sending_account = "DEVELOPER"
    source_arn            = data.aws_ses_domain_identity.mail.arn
    from_email_address    = "MootMaker <noreply@mail.mootmaker.com>"
  }

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

  # The caller's own Person id, read into the ID token as the custom:personId claim. Set
  # server-side by PostConfirmationCreatePersonHandler and never client-writable (see
  # write_attributes below) - and that exclusion is a sharper control than custom:class's.
  # Self-writing your class escalates you to admin; self-writing your personId makes you BECOME
  # another person: their preferences, their rename, bookings as them, and deleteMyAccount on
  # their account. Immutable in practice, since a Person id never changes for the life of the
  # person, but declared mutable because the trigger has to set it after confirmation.
  schema {
    name                = "personId"
    attribute_data_type = "String"
    mutable             = true
    required            = false

    string_attribute_constraints {
      min_length = 36
      max_length = 36
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
  read_attributes = ["email", "email_verified", "name", "custom:class", "custom:personId"]
  # Deliberately excludes BOTH custom:class and custom:personId. A user must never set their own
  # class by calling Cognito's UpdateUserAttributes from the browser SDK - only the Admin API may.
  # custom:personId matters more: every resolver trusts it to identify the caller, so a user able
  # to write it could point at anyone else's Person and act as them completely. That trust is only
  # justified by this list.
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

  # M2M tooling (the acceptance-test client below, and mootmaker-demo-data's own client in
  # demo-data-credentials.tf) has no Cognito user behind it at all, so it can never carry a
  # custom:class claim the way a real signed-in user's ID token does - this scope is
  # Identity.requireAdmin's equivalent for that caller. See this project's README for why
  # mootmaker-demo-data still needs to create rooms/people now that those mutations are admin-only.
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

# DETERMINISTIC, not random, and that is load-bearing rather than tidiness.
#
# random_uuid's result is unknown at plan time, and putting an unknown value inside
# aws_cognito_user.attributes makes the provider plan the whole map as null and then fail the apply
# with "produced an invalid new value for .attributes: was null, but now ...". uuidv5 is computed
# from its inputs, so it is known during planning and the map plans concretely.
#
# It also makes the id stable across a destroy-and-rebuild of an environment, which is a small gain
# on its own: the demo and e2e Persons keep their identity when the environment is recreated.
locals {
  e2e_person_id  = uuidv5("dns", "e2e-person.${var.environment}.mootmaker")
  demo_person_id = uuidv5("dns", "demo-person.${var.environment}.mootmaker")
}

resource "aws_cognito_user" "e2e" {
  user_pool_id = aws_cognito_user_pool.this.id
  username     = "e2e-tests@example.com"
  password     = random_password.e2e_user.result

  attributes = {
    email          = "e2e-tests@example.com"
    email_verified = "true"
    # Created directly rather than through sign-up, so PostConfirmationCreatePersonHandler never
    # runs and neither the Person nor this claim would otherwise exist. Until now the e2e user had
    # no Person at all, which meant the identity the whole acceptance suite runs as exercised the
    # "no linked Person" path rather than the one every real user takes.
    "custom:personId" = local.e2e_person_id
    # Created directly rather than through sign-up, so it skips PostConfirmationCreatePersonHandler
    # (the same reason it has no Person - see below) and would otherwise have no class at all; set
    # explicitly here for parity with a real signed-up user, who always gets one.
    "custom:class" = "standard"
  }
  # Terraform sets these at CREATE and must never touch them again.
  #
  # aws_cognito_user cannot hold custom:* attributes across an update. State stores them with the
  # "custom:" prefix stripped, so every plan computes "delete the old key, add the new one" - and
  # since Cognito resolves both names to the same attribute, the add and the delete cancel. Measured
  # on a FRESH environment: the create is correct, the very next apply of an unchanged configuration
  # wipes both users' custom attributes entirely. On a long-lived environment it alternates, fixing
  # one user and wiping the other on each run.
  #
  # ignore_changes only suppresses updates, never creation, so the attributes are still set exactly
  # once, correctly, when the user is made. The cost is that a genuine change to these values needs
  # the user replaced rather than updated - acceptable for two Terraform-managed fixtures, and the
  # alternative is silent wrongness. See mootmaker-api#39.
  lifecycle {
    ignore_changes = [attributes]
  }
}

resource "aws_dynamodb_table_item" "e2e_person" {
  table_name = aws_dynamodb_table.people.name
  hash_key   = aws_dynamodb_table.people.hash_key

  item = jsonencode({
    id          = { S = local.e2e_person_id }
    name        = { S = "E2E Tester" }
    cognitoSubs = { L = [{ S = aws_cognito_user.e2e.sub }] }
  })
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
# (e.g. unlike database-reset) - this demo user is meant to exist even in a
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
    # Created directly rather than through sign-up, so PostConfirmationCreatePersonHandler never
    # runs and this claim would otherwise never be set - leaving the demo login with a Person it
    # cannot resolve, and myPerson returning null for the account the signed-out home page hands
    # every visitor.
    "custom:personId" = local.demo_person_id
  }
  # Terraform sets these at CREATE and must never touch them again.
  #
  # aws_cognito_user cannot hold custom:* attributes across an update. State stores them with the
  # "custom:" prefix stripped, so every plan computes "delete the old key, add the new one" - and
  # since Cognito resolves both names to the same attribute, the add and the delete cancel. Measured
  # on a FRESH environment: the create is correct, the very next apply of an unchanged configuration
  # wipes both users' custom attributes entirely. On a long-lived environment it alternates, fixing
  # one user and wiping the other on each run.
  #
  # ignore_changes only suppresses updates, never creation, so the attributes are still set exactly
  # once, correctly, when the user is made. The cost is that a genuine change to these values needs
  # the user replaced rather than updated - acceptable for two Terraform-managed fixtures, and the
  # alternative is silent wrongness. See mootmaker-api#39.
  lifecycle {
    ignore_changes = [attributes]
  }
}

# The demo user above is created directly by Terraform rather than through the sign-up/confirm
# API calls, so it never fires PostConfirmationCreatePersonHandler (see "Sign-up creates a linked
# Person" in the README) and would otherwise have no Person - showing up nameless in the webapp,
# and (if one existed anyway with no link) getting deleted by the next database-reset run,
# since reset only ever preserves people linked to a Cognito account. This writes one directly, in
# the same shape as Person.toItem(), linked via cognitoSubs to aws_cognito_user.demo's sub - which
# is also what protects it from database-reset's Cognito-wipe pass (see admin-tools.tf's
# RESERVED_ACCOUNT_EMAILS), not just its DynamoDB-level survival rule.
resource "aws_dynamodb_table_item" "demo_person" {
  table_name = aws_dynamodb_table.people.name
  hash_key   = aws_dynamodb_table.people.hash_key

  item = jsonencode({
    id          = { S = local.demo_person_id }
    name        = { S = "Demo Strater" }
    cognitoSubs = { L = [{ S = aws_cognito_user.demo.sub }] }
  })
}
