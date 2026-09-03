#!/usr/bin/env bash
# Destroys all AWS resources created by deploy.sh for the given environment:
# the AppSync API, all Lambda functions, and the DynamoDB tables (including
# any stored rooms/people/meetings data).
#
# NOTE: this is DESTRUCTIVE and IRREVERSIBLE. Terraform prompts for interactive confirmation
# before deleting anything; this script intentionally does not pass -auto-approve by default.
#
# --yes passes -auto-approve, for automation that has no stdin to answer the prompt with (the
# release pipeline's ephemeral acceptance environments, and the scheduled ephemeral sweep - see
# mootmaker/designs/ci-cd-pipeline.md Rollout steps 6 and 11).
#
# Non-interactive mode is deliberately NARROWER than interactive mode, not just quieter. Run by
# hand, this script will destroy "production" if you confirm the prompt - the prompt is the
# safeguard. With --yes there is no prompt, so "production" and "test" are refused outright
# instead: those two change through the release pipeline (Decision 6), never through an
# unattended undeploy.
set -euo pipefail
cd "$(dirname "$0")"

assume_yes=0
args=()
for arg in "$@"; do
  if [[ "${arg}" == "--yes" ]]; then
    assume_yes=1
  else
    args+=("${arg}")
  fi
done
set -- "${args[@]+"${args[@]}"}"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./undeploy.sh <environment> [--yes]   (e.g. an ephemeral name, or production)" >&2
  exit 1
fi
if [[ "${assume_yes}" == "1" && ( "${environment}" == "production" || "${environment}" == "test" ) ]]; then
  echo "Refusing to undeploy '${environment}' with --yes: standing environments are never destroyed unattended. Re-run without --yes and confirm the prompt if you really mean it." >&2
  exit 1
fi
# See deploy.sh for why anything starting with "prod" but not exactly
# "production" is refused outright.
if [[ "${environment}" == prod* && "${environment}" != "production" ]]; then
  echo "environment '${environment}' starts with 'prod' but isn't exactly 'production' - refusing, to avoid confusion with the real production environment." >&2
  exit 1
fi

echo "Undeploying mootmaker-api environment '${environment}'..."

export TF_DATA_DIR=".terraform-${environment}"

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-api/terraform.tfstate"
destroy_args=(-var="environment=${environment}")
if [[ "${assume_yes}" == "1" ]]; then
  destroy_args+=(-auto-approve)
fi
terraform -chdir=deploy/terraform destroy "${destroy_args[@]}"
