#!/usr/bin/env bash
# Builds the Lambda jar and deploys the mootmaker API to AWS via Terraform,
# into the given environment (e.g. an ephemeral name, "production", or a developer's own
# name for a personal sandbox - see the mootmaker project README for the
# full multi-environment how-to).
# NOTE: `terraform apply -auto-approve` creates real AWS resources in whatever
# account/credentials are active. Run this deliberately, not from automation.
#
# --skip-build deploys the jar already sitting in impl/target/ instead of rebuilding it.
# This is what makes Decision 8 of mootmaker/designs/ci-cd-pipeline.md ("build once, promote
# the same artifact") actually true: the release pipeline builds the jar once, then deploys
# that identical file to test and then production. Rebuilding per environment would produce
# two different jars and promote nothing. Not useful interactively - omit it and this script
# behaves exactly as it always has.
set -euo pipefail
cd "$(dirname "$0")"

skip_build=0
args=()
for arg in "$@"; do
  if [[ "${arg}" == "--skip-build" ]]; then
    skip_build=1
  else
    args+=("${arg}")
  fi
done
set -- "${args[@]+"${args[@]}"}"

environment="${1:-}"
if [[ -z "${environment}" ]]; then
  echo "Usage: ./deploy.sh <environment>   (e.g. an ephemeral name, or production)" >&2
  exit 1
fi
if [[ ! "${environment}" =~ ^[a-z0-9-]+$ ]]; then
  echo "environment must contain only lowercase letters, digits, and hyphens: '${environment}'" >&2
  exit 1
fi
# "production" deploys to api.mootmaker.com (see deploy/terraform/domain.tf);
# every other environment gets api.<environment>.mootmaker.com. Anything
# starting with "prod" but not exactly "production" is refused outright,
# rather than silently deploying to a subdomain that looks production-like
# but isn't.
if [[ "${environment}" == prod* && "${environment}" != "production" ]]; then
  echo "environment '${environment}' starts with 'prod' but isn't exactly 'production' - refusing, to avoid confusion with the real production environment." >&2
  exit 1
fi

echo "Deploying mootmaker-api to '${environment}'..."

# Isolates this environment's Terraform provider cache/backend pointer from
# other environments, so deploying two different environments from the same
# checkout (even concurrently) can't cross-contaminate each other.
export TF_DATA_DIR=".terraform-${environment}"

jar_path="impl/target/mootmaker-api.jar"
if [[ "${skip_build}" == "1" ]]; then
  if [[ ! -f "${jar_path}" ]]; then
    echo "--skip-build given but ${jar_path} does not exist - nothing to deploy." >&2
    exit 1
  fi
  echo "Skipping build; deploying the existing ${jar_path}."
else
  mvn -f impl/pom.xml clean package
fi

terraform -chdir=deploy/terraform init -backend-config=backend.hcl -backend-config="key=${environment}/mootmaker-api/terraform.tfstate"
terraform -chdir=deploy/terraform apply -auto-approve -var="environment=${environment}"
