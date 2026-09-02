#!/usr/bin/env bash
# Exports the deployed API's endpoint and Cognito authentication settings (read
# from Terraform outputs) into the current shell, for use with the /verify
# acceptance tests, api/requests.http, and the webapp's deploy/e2e tests.
#
# Takes the environment to read from (e.g. an ephemeral name, "production", or a
# developer's own name), matching whatever was passed to deploy.sh.
#
# Exported variables:
#   GRAPHQL_API_URL            GraphQL endpoint
#   COGNITO_USER_POOL_ID       Cognito user pool id
#   COGNITO_WEBAPP_CLIENT_ID   Public app client id for the browser webapp
#   COGNITO_TOKEN_URL          OAuth2 token endpoint (client_credentials flow)
#   COGNITO_TEST_CLIENT_ID     Acceptance-test app client id
#   COGNITO_TEST_CLIENT_SECRET Acceptance-test app client secret
#   COGNITO_TEST_SCOPE         OAuth2 scope for acceptance-test tokens
#   E2E_USER_EMAIL             Pre-confirmed user for webapp Playwright tests
#   E2E_USER_PASSWORD          Password for that user
#   DEMO_USER_EMAIL            Pre-confirmed, publicly-known demo user shown on the webapp home page
#   DEMO_USER_PASSWORD         Password for that user (not a secret - it's shown in the webapp UI)
#   AWS_REGION                 Region this environment is deployed into
#
# Must be SOURCED, not executed, so the exports persist in your shell:
#   source authenticate.sh <environment>
#   . ./authenticate.sh <environment>

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "authenticate.sh must be sourced, not executed, so the exports persist in your shell." >&2
  echo "Run: source authenticate.sh <environment>" >&2
  exit 1
fi

if [[ -z "${1:-}" ]]; then
  echo "Usage: source authenticate.sh <environment>   (e.g. an ephemeral name, or production)" >&2
  return 1
fi

_authenticate_environment="$1"
_authenticate_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_authenticate_terraform_dir="${_authenticate_script_dir}/deploy/terraform"
# Isolated per environment so this doesn't collide with a concurrent deploy.sh
# run (or the state cache of a different environment) in the same checkout.
_authenticate_tf_data_dir="${_authenticate_terraform_dir}/.terraform-${_authenticate_environment}"
_authenticate_failed=""

if ! TF_DATA_DIR="${_authenticate_tf_data_dir}" terraform -chdir="${_authenticate_terraform_dir}" init \
  -backend-config=backend.hcl \
  -backend-config="key=${_authenticate_environment}/mootmaker-api/terraform.tfstate" \
  -input=false >/dev/null; then
  echo "Failed to initialize Terraform for environment '${_authenticate_environment}'." >&2
  _authenticate_failed="true"
fi

_authenticate_read_output() {
  local var_name="$1" output_name="$2" value
  # `terraform output -raw` on a name with no value (e.g. an environment that
  # was never deployed, so the state has no outputs) exits 0 with an empty
  # string rather than failing, so an empty result must be treated as failure
  # too - none of these outputs are legitimately empty.
  if ! value="$(TF_DATA_DIR="${_authenticate_tf_data_dir}" terraform -chdir="${_authenticate_terraform_dir}" output -raw "${output_name}" 2>/dev/null)" || [[ -z "${value}" ]]; then
    echo "Failed to read ${output_name} from Terraform. Has '${_authenticate_environment}' been deployed (deploy.sh ${_authenticate_environment})?" >&2
    _authenticate_failed="true"
    return 1
  fi
  export "${var_name}=${value}"
}

if [[ -z "${_authenticate_failed}" ]]; then
  _authenticate_read_output GRAPHQL_API_URL graphql_api_url &&
    _authenticate_read_output COGNITO_USER_POOL_ID cognito_user_pool_id &&
    _authenticate_read_output COGNITO_WEBAPP_CLIENT_ID cognito_webapp_client_id &&
    _authenticate_read_output COGNITO_TOKEN_URL cognito_token_url &&
    _authenticate_read_output COGNITO_TEST_CLIENT_ID cognito_test_client_id &&
    _authenticate_read_output COGNITO_TEST_CLIENT_SECRET cognito_test_client_secret &&
    _authenticate_read_output COGNITO_TEST_SCOPE cognito_test_scope &&
    _authenticate_read_output E2E_USER_EMAIL e2e_user_email &&
    _authenticate_read_output E2E_USER_PASSWORD e2e_user_password &&
    _authenticate_read_output DEMO_USER_EMAIL demo_user_email &&
    _authenticate_read_output DEMO_USER_PASSWORD demo_user_password &&
    _authenticate_read_output AWS_REGION aws_region
fi

if [[ -z "${_authenticate_failed}" ]]; then
  echo "Exported GRAPHQL_API_URL, the COGNITO_*/E2E_*/DEMO_* authentication variables, and AWS_REGION for '${_authenticate_environment}'."
fi

unset -f _authenticate_read_output
unset _authenticate_script_dir _authenticate_terraform_dir _authenticate_tf_data_dir _authenticate_environment

[[ -z "${_authenticate_failed}" ]] || { unset _authenticate_failed; return 1; }
unset _authenticate_failed
