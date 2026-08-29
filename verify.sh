#!/usr/bin/env bash
# Builds and runs the /verify acceptance tests (mvn verify) against the deployed mootmaker API.
# Most tests reset the database immediately before they act, via the mootmaker-admin-tools/database-reset
# Lambda (invoked directly with AWS credentials, not through GraphQL - see DatabaseReset.java and
# the README's "Authentication in end-to-end tests" section) - that Lambda must already be deployed
# for this environment (mootmaker-admin-tools/database-reset/deploy.sh <environment>).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ -z "${1:-}" ]]; then
  echo "Usage: ./verify.sh <environment>   (e.g. an ephemeral name, or production)" >&2
  exit 1
fi

source ./authenticate.sh "$1"

# Deterministic name computed the same way mootmaker-admin-tools/database-reset's own run.sh does -
# see that project's README for why this is computed rather than looked up.
export DATABASE_RESET_FUNCTION_NAME="$1-mootmaker-database-reset"

mvn -f verify/pom.xml clean verify
