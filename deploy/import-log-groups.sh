#!/usr/bin/env bash
# Imports this environment's ALREADY-EXISTING log groups into Terraform state, so the first apply
# that introduces deploy/terraform/logs.tf does not fail.
#
# Why this is needed (design Rollout step 12): Lambda and AppSync auto-create their log groups on
# first invocation, unmanaged and with never-expire retention. CloudWatch rejects creating a log
# group whose name is already taken, so on any environment that has ever run - test and production
# - a plain apply fails. Importing first hands Terraform the existing group instead.
#
# A FRESH ephemeral environment needs none of this: nothing exists there yet, so apply creates the
# groups normally. This script is safe to run there anyway - it skips anything that is absent.
#
# Idempotent: a group already in state is skipped, so re-running after a partial failure is fine.
#
# Usage: ./deploy/import-log-groups.sh <environment>
set -euo pipefail
cd "$(dirname "$0")/.."

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy/import-log-groups.sh <environment>" >&2
  exit 1
fi

export TF_DATA_DIR=".terraform-${environment}"
terraform -chdir=deploy/terraform init \
  -backend-config=backend.hcl \
  -backend-config="key=${environment}/mootmaker-api/terraform.tfstate" \
  -input=false >/dev/null

prefix="${environment}-mootmaker"

import_group() {
  local address="$1" name="$2"

  if terraform -chdir=deploy/terraform state show "${address}" >/dev/null 2>&1; then
    echo "already in state, skipping: ${name}"
    return 0
  fi

  if ! aws logs describe-log-groups --log-group-name-prefix "${name}" \
      --query "logGroups[?logGroupName=='${name}'] | length(@)" --output text | grep -q '^1$'; then
    echo "does not exist in AWS, nothing to import: ${name}"
    return 0
  fi

  echo "importing ${name}"
  terraform -chdir=deploy/terraform import \
    -var="environment=${environment}" \
    "${address}" "${name}"
}

for fn in resolvers post-confirmation-create-person database-reset database-repair; do
  import_group "aws_cloudwatch_log_group.lambda[\"${prefix}-${fn}\"]" "/aws/lambda/${prefix}-${fn}"
done

# AppSync's group is named after the API's id, which is only knowable from the deployed state.
api_id="$(terraform -chdir=deploy/terraform output -raw graphql_api_id 2>/dev/null || true)"
if [[ -n "${api_id}" ]]; then
  import_group "aws_cloudwatch_log_group.appsync" "/aws/appsync/apis/${api_id}"
else
  echo "no graphql_api_id output yet - AppSync group import skipped (fine on a new environment)"
fi

echo "Done. A plain ./deploy.sh ${environment} can now apply logs.tf safely."
