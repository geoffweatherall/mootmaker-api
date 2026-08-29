# Testing strategy

The overall cross-repo strategy (environments, the approach to reading Cognito's emails in tests,
and how "vibe coding" shapes all of this) is recorded in
[mootmaker/testing-strategy.md](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/testing-strategy.md).
This document covers what's specific to this repo.

## Layers

- **Unit tests** (`impl/src/test/`, run via `mvn -f impl/pom.xml clean package` — see
  [README.md](README.md#build-test-deploy)): fast, no AWS involved, covers the validation rules,
  DynamoDB query/index selection logic, and the other Java-level behaviour in the Lambda handlers.
- **Acceptance tests** (`verify/`, JUnit `*IT.java`, run via `./verify.sh <environment>` — see
  [README.md](README.md#directory-structure)): exercise the **deployed** API over real
  HTTP/AppSync, with real Cognito M2M (`client_credentials`) auth and real DynamoDB, resetting
  state via [mootmaker-admin-tools/database-reset](https://github.com/geoffweatherall/mootmaker-admin-tools/tree/main/database-reset)
  before each test class. This is the layer that catches Terraform misconfiguration, IAM gaps,
  AppSync↔Lambda wiring problems, and anything else that only exists once the pieces are actually
  deployed together.

## What's changing

- `verify.sh` already takes an arbitrary environment name, so pointing it at a throwaway
  environment instead of the shared `test` one is a change in *usage*, not code. Going forward,
  **`test` is reserved for human manual testing**; any automated acceptance-test run (Claude's or
  future CI's) targets a fresh ephemeral environment instead
  (`claude-<timestamp>-<rand>` for Claude's own dev sessions, or `<frontend>-<tier>-<timestamp>-<rand>`
  for an automated suite's own run, e.g. `web-e2e-<timestamp>-<rand>` — see the [naming
  convention](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/testing-strategy.md#environments)
  in the overall doc), then tears it down.
- **Email verification code bypass (Option 1) — dropped 2026-08-15.** A `CustomEmailSender`
  Lambda trigger + KMS decrypt + a DynamoDB bypass table was built, unit-tested, and confirmed
  `terraform validate`-clean, but never applied (blocked on the account's SCP allowing `kms`).
  Removed rather than left unapplied once the cost/complexity became clear: a customer-managed KMS
  key is billed a flat $1/month *per key* regardless of use (unlike everything else in this
  project, which is pure pay-per-request) — and as originally designed, one key would have been
  created per ephemeral environment, not shared, so it wouldn't have stayed near-zero-cost the way
  the rest of this project does. The AWS Encryption SDK dependency it needed was also heavy enough
  to take the shared Lambda jar from ~7.2 MB to ~24 MB (every function in this project ships from
  one jar — see `lambda.tf`'s "one shaded jar" comment), a real cold-start cost paid by every
  function, not just this one. mootmaker/testing-strategy.md's "Reading Cognito's emails in tests"
  now covers Option 2 only; use the Cognito Admin API (`AdminConfirmSignUp`/`AdminSetUserPassword`)
  directly from test code for anything that needs a working account but doesn't care about
  exercising the real code-entry UI step — no new infrastructure needed for that, `cognito-idp:*`
  is already SCP-allowed.
- The GraphQL schema ([api/mootmaker.graphql](api/mootmaker.graphql)) is the contract
  mootmaker-webapp's hand-maintained types currently mirror by hand. Codegen to remove that drift
  risk is tracked as a to-do in [mootmaker's
  README](https://github.com/geoffweatherall/mootmaker/blob/main/README.md#to-do), deferred until
  CI/CD pipelines exist — see
  [mootmaker-webapp/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-webapp/blob/main/testing-strategy.md)
  for the webapp side of that gap.

## Full-stack e2e

Deployed-webapp-against-deployed-API end-to-end testing (including real email delivery via
SES→SNS→SQS) lives with each frontend now, not here — this repo's own acceptance tests stay
API-only, machine-to-machine, and never touch a browser or real email. **Changed 2026-08-19**:
previously lived in a shared `mootmaker-e2e` repo; that repo is now
[mootmaker-test-infra](https://github.com/geoffweatherall/mootmaker-test-infra) (only the
genuinely cross-frontend pieces - ephemeral-environment lifecycle, the SES email pipeline), and
each frontend owns its own full-stack suite in its own repo instead - see
[mootmaker-webapp/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-webapp/blob/main/testing-strategy.md)
for that repo's `e2e/`/`acceptance/` suites, and
[mootmaker/testing-strategy.md](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/testing-strategy.md)
for the overall cross-repo picture.
