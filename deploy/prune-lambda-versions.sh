#!/usr/bin/env bash
# Deletes old published versions of this environment's Lambda functions (mootmaker#77).
#
# WHY. `publish = true` plus SnapStart publishes a new version on every code change, and each
# version keeps its own copy of the code. Nothing removed them, so they grew with the number of
# deploys rather than with use - a direct breach of principles.md's "nothing accumulates without a
# bound". Run by deploy.sh after every apply, so the count can never drift upward.
#
# WHAT IS KEPT, per function:
#   - the version the `live` alias points at (what Cognito triggers and AppSync invoke);
#   - the newest KEEP numbered versions (default 3), aliased or not.
# $LATEST is never touched. Nothing here relies on older versions: rollback redeploys the previous
# release's code (mootmaker/designs/archive/ci-cd-pipeline.md Decision 10), it does not repoint an
# alias at an old version.
#
# A failure to delete is reported and skipped, not fatal: a version left behind costs nothing
# today, and a deploy that already succeeded should not be reported as failed because tidying up
# afterwards did not.
#
# Usage: deploy/prune-lambda-versions.sh <environment> [keep]
set -euo pipefail

environment="${1:?Usage: deploy/prune-lambda-versions.sh <environment> [keep]}"
keep="${2:-3}"
if [[ ! "${keep}" =~ ^[1-9][0-9]*$ ]]; then
  echo "keep must be a positive whole number, got '${keep}'." >&2
  exit 1
fi

prefix="${environment}-mootmaker-"
mapfile -t functions < <(
  aws lambda list-functions --query "Functions[?starts_with(FunctionName, '${prefix}')].FunctionName" \
    --output text | tr '\t' '\n' | sed '/^$/d'
)

alias_err="$(mktemp)"
trap 'rm -f "${alias_err}"' EXIT

deleted=0
for fn in "${functions[@]+"${functions[@]}"}"; do
  # The `live` alias, read directly: it is the only alias name this project's Terraform creates,
  # and the release pipeline's deploy role has lambda:GetAlias but not lambda:ListAliases. If the
  # alias cannot be read for any reason other than not existing, this function is skipped - a
  # version is only ever deleted when what protects it is known.
  aliased=()
  if alias_version="$(aws lambda get-alias --function-name "${fn}" --name live \
        --query FunctionVersion --output text 2>"${alias_err}")"; then
    aliased=("${alias_version}")
  elif ! grep -q ResourceNotFoundException "${alias_err}"; then
    echo "Could not read ${fn}'s live alias - skipping it: $(cat "${alias_err}")" >&2
    continue
  fi
  # Numbered versions only, newest first.
  mapfile -t versions < <(
    aws lambda list-versions-by-function --function-name "${fn}" --query 'Versions[].Version' \
      --output text | tr '\t' '\n' | grep -E '^[0-9]+$' | sort -rn
  )
  for version in "${versions[@]:${keep}}"; do
    if printf '%s\n' "${aliased[@]+"${aliased[@]}"}" | grep -qxF "${version}"; then
      continue
    fi
    if aws lambda delete-function --function-name "${fn}" --qualifier "${version}" >/dev/null; then
      deleted=$(( deleted + 1 ))
    else
      echo "Could not delete ${fn}:${version} - left in place." >&2
    fi
  done
done

echo "Pruned ${deleted} old Lambda version(s) in '${environment}' (kept aliased + newest ${keep})."
