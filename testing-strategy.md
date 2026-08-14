# Testing strategy

The overall cross-repo strategy (environments, the approach to reading Cognito's emails in tests,
and how "vibe coding" shapes all of this) is recorded in
[mootmaker/testing-strategy.md](https://github.com/geoffweatherall/mootmaker/blob/main/testing-strategy.md).
This document covers what's specific to this repo.

## Layers

- **Unit tests** (`impl/src/test/`, run via `mvn -f impl/pom.xml clean package` — see
  [README.md](README.md#build-test-deploy)): fast, no AWS involved, covers the validation rules,
  DynamoDB query/index selection logic, and the other Java-level behaviour in the Lambda handlers.
- **Acceptance tests** (`verify/`, JUnit `*IT.java`, run via `./verify.sh <environment>` — see
  [README.md](README.md#directory-structure)): exercise the **deployed** API over real
  HTTP/AppSync, with real Cognito M2M (`client_credentials`) auth and real DynamoDB, resetting
  state via [mootmaker-tools/database-reset](https://github.com/geoffweatherall/mootmaker-tools/tree/main/database-reset)
  before each test class. This is the layer that catches Terraform misconfiguration, IAM gaps,
  AppSync↔Lambda wiring problems, and anything else that only exists once the pieces are actually
  deployed together.

## What's changing

- `verify.sh` already takes an arbitrary environment name, so pointing it at a throwaway
  environment instead of the shared `test` one is a change in *usage*, not code. Going forward,
  **`test` is reserved for human manual testing**; any automated acceptance-test run (Claude's or
  future CI's) targets a fresh ephemeral environment instead
  (`claude-<timestamp>-<rand>` or `e2e-<timestamp>-<rand>` — see the [naming
  convention](https://github.com/geoffweatherall/mootmaker/blob/main/testing-strategy.md#environments)
  in the overall doc), then tears it down.
- **Email verification code bypass (Option 1, planned)**: a
  `CustomMessage_SignUp`/`CustomMessage_ForgotPassword` Lambda trigger will write Cognito's
  generated code (`event.request.codeParameter`) to a DynamoDB table for addresses matching a
  test-only naming convention, instead of the acceptance/e2e suites needing to read real email.
  Gated behind a Terraform variable so it's only active where explicitly enabled — **on for
  ephemeral environments, off for `test` and `production`** — so this never touches how a real
  user's confirmation email behaves, and doesn't exist at all in an environment a real person might
  use. That variable's value is decided by `deploy.sh` from the **environment name's naming
  pattern alone**, not passed in by whatever's calling it: a `claude-*`/`e2e-*` name self-enables
  the bypass, anything else (`test`, `production`, a developer's own personal-sandbox name) leaves
  it off. No caller — including `mootmaker-e2e/create-ephemeral-env.sh` (see
  [mootmaker-e2e/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-e2e/blob/main/testing-strategy.md#ephemeral-environment-scripts))
  — needs to know this variable exists or pass a flag for it. Not yet implemented; see
  [mootmaker/testing-strategy.md's
  section](https://github.com/geoffweatherall/mootmaker/blob/main/testing-strategy.md#reading-cognitos-emails-in-tests)
  for the full reasoning, including Option 2 (real email, used only in mootmaker-e2e).
- The GraphQL schema ([api/mootmaker.graphql](api/mootmaker.graphql)) is the contract
  mootmaker-webapp's hand-maintained types currently mirror by hand. Codegen to remove that drift
  risk is tracked as a to-do in [mootmaker's
  README](https://github.com/geoffweatherall/mootmaker/blob/main/README.md#to-do), deferred until
  CI/CD pipelines exist — see
  [mootmaker-webapp/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-webapp/blob/main/testing-strategy.md)
  for the webapp side of that gap.

## Full-stack e2e

Deployed-webapp-against-deployed-API end-to-end testing (including real email delivery via
SES→SQS) lives in [mootmaker-e2e](https://github.com/geoffweatherall/mootmaker-e2e), not here —
this repo's own acceptance tests stay API-only, machine-to-machine, and never touch a browser or
real email. See
[mootmaker-e2e/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-e2e/blob/main/testing-strategy.md).
