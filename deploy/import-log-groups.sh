#!/usr/bin/env bash
# Imports any of this environment's log groups that already exist but are not in Terraform state,
# so an apply does not fail with ResourceAlreadyExistsException.
#
# Lambda and AppSync auto-create a log group on first invocation if none exists, with never-expire
# retention. Terraform normally creates the groups first (see deploy/terraform/logs.tf), so this is
# needed only if a group was ever created outside Terraform. It skips anything absent, so it is safe
# to run on any environment.
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
