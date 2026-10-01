#!/usr/bin/env bash
# Builds and runs the /verify acceptance tests (mvn verify) against the deployed mootmaker API.
# Most tests reset the database immediately before they act, via the database-reset Lambda
# (invoked directly with AWS credentials, not through GraphQL - see DatabaseReset.java and the
# README's "Authentication in end-to-end tests" section) - part of this repo's own Terraform, so
# deploying this environment (./deploy.sh <environment>) is all that's needed first.
#
# Everything the tests need is looked up in SSM Parameter Store, where deploy.sh publishes it
# (deploy/terraform/published-config.tf, mootmaker-api#94), and handed to the test JVM as
# environment variables. Nothing is sourced into the calling shell.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./verify.sh <environment>   (an ephemeral environment name)" >&2
  exit 1
fi

ssm_value() {
  local value
  if ! value="$(aws ssm get-parameter --name "/mootmaker/${environment}/api/$1" --with-decryption --query Parameter.Value --output text 2>&1)"; then
    echo "Could not read /mootmaker/${environment}/api/$1 - has '${environment}' been deployed (./deploy.sh ${environment})?" >&2
    echo "${value}" >&2
    exit 1
  fi
  printf '%s' "${value}"
}

GRAPHQL_API_URL="$(ssm_value graphql-url)"
AWS_REGION="$(ssm_value region)"
COGNITO_USER_POOL_ID="$(ssm_value cognito/user-pool-id)"
COGNITO_TOKEN_URL="$(ssm_value m2m-client/token-url)"
COGNITO_TEST_CLIENT_ID="$(ssm_value m2m-client/client-id)"
COGNITO_TEST_CLIENT_SECRET="$(ssm_value m2m-client/client-secret)"
COGNITO_TEST_SCOPE="$(ssm_value m2m-client/scope)"
DEMO_USER_EMAIL="$(ssm_value demo-user/email)"
# Test fixtures are published in ephemeral environments only - which is the only kind this suite
# runs against.
E2E_USER_EMAIL="$(ssm_value test-fixtures/users/standard/email)"
DATABASE_RESET_FUNCTION_NAME="$(ssm_value database-reset/function-name)"
HISTORY_CLEANUP_FUNCTION_NAME="$(ssm_value history-cleanup/function-name)"
export GRAPHQL_API_URL AWS_REGION COGNITO_USER_POOL_ID COGNITO_TOKEN_URL COGNITO_TEST_CLIENT_ID \
  COGNITO_TEST_CLIENT_SECRET COGNITO_TEST_SCOPE DEMO_USER_EMAIL E2E_USER_EMAIL \
  DATABASE_RESET_FUNCTION_NAME HISTORY_CLEANUP_FUNCTION_NAME

mvn -f verify/pom.xml clean verify
