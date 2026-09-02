#!/usr/bin/env bash
# Builds and runs the /verify acceptance tests (mvn verify) against the deployed mootmaker API.
# Most tests reset the database immediately before they act, via the database-reset Lambda
# (invoked directly with AWS credentials, not through GraphQL - see DatabaseReset.java and the
# README's "Authentication in end-to-end tests" section) - part of this repo's own Terraform, so
# deploying this environment (./deploy.sh <environment>) is all that's needed first.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ -z "${1:-}" ]]; then
  echo "Usage: ./verify.sh <environment>   (e.g. an ephemeral name, or production)" >&2
  exit 1
fi

source ./authenticate.sh "$1"

# Deterministic name computed the same way database-reset's own Terraform names it (see
# deploy/terraform/admin-tools.tf) - computed rather than looked up via a Terraform output, the
# same reasoning that applies everywhere else this pattern is used.
export DATABASE_RESET_FUNCTION_NAME="$1-mootmaker-database-reset"

mvn -f verify/pom.xml clean verify
