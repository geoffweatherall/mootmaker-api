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
- **Email verification code bypass (Option 1, implemented, unapplied pending SCP)**: a
  `CustomEmailSender` Lambda trigger (not `CustomMessage` — see
  [mootmaker/testing-strategy.md's
  section](https://github.com/geoffweatherall/mootmaker/blob/main/testing-strategy.md#reading-cognitos-emails-in-tests)
  for why `CustomMessage` can't do this) decrypts Cognito's generated code (KMS-encrypted in
  transit) and writes it to a DynamoDB table, instead of the acceptance/e2e suites needing to read
  real email. Taking over this trigger hands it Cognito's default sending entirely for that user
  pool, so when enabled it deliberately sends no email at all rather than reimplementing sending —
  nothing in an ephemeral environment needs to receive it (that's Option 2's job). Gated so it's
  only active where explicitly enabled — **on for ephemeral environments, off for `test` and
  `production`** — so this never touches how a real user's confirmation email behaves, and doesn't
  exist at all in an environment a real person might use. Applies to every sign-up/reset in an
  enabled environment rather than needing a further test-only address convention on top — there's
  no real user to protect from it once the environment itself is ephemeral. That gate is decided by
  `deploy.sh` from the **environment name's naming pattern alone**, not passed in by whatever's
  calling it: a `claude-*`/`e2e-*` name self-enables the bypass, anything else (`test`, `production`,
  a developer's own personal-sandbox name) leaves it off. No caller — including
  `mootmaker-e2e/create-ephemeral-env.sh` (see
  [mootmaker-e2e/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-e2e/blob/main/testing-strategy.md#ephemeral-environment-scripts))
  — needs to know this variable exists or pass a flag for it.

  **Blocked on the same account-wide SCP as Option 2**, for a different reason: `CustomEmailSender`
  needs a customer-managed KMS key Cognito can encrypt with, and `kms` isn't on the allow-list
  either. The Java handler, DynamoDB table, and Terraform are written and unit-tested (the decrypt
  logic is genuinely exercised locally, against a plain JCE key standing in for the real
  `KmsMasterKeyProvider` — only the specific real-KMS integration remains unverified), but
  deliberately left unapplied until that allow-list is updated (Claude doesn't modify SCPs).

  **Worth knowing before deciding to pursue this further**: the AWS Encryption SDK dependency this
  needs is genuinely heavy (Bouncy Castle, a formally-verified crypto runtime pulled in
  transitively) — even after excluding the same unused HTTP clients this project already excludes
  elsewhere, it takes the shaded jar from ~7.2 MB to ~24 MB. Every Lambda function in this project
  shares one jar (see `lambda.tf`'s "one shaded jar" comment), so this is a cold-start cost paid by
  every function, not just this one — worth weighing against just relying on Option 2 instead once
  the SCP unblocks either.
- The GraphQL schema ([api/mootmaker.graphql](api/mootmaker.graphql)) is the contract
  mootmaker-webapp's hand-maintained types currently mirror by hand. Codegen to remove that drift
  risk is tracked as a to-do in [mootmaker's
  README](https://github.com/geoffweatherall/mootmaker/blob/main/README.md#to-do), deferred until
  CI/CD pipelines exist — see
  [mootmaker-webapp/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-webapp/blob/main/testing-strategy.md)
  for the webapp side of that gap.

## Full-stack e2e

Deployed-webapp-against-deployed-API end-to-end testing (including real email delivery via
SES→SNS→SQS) lives in [mootmaker-e2e](https://github.com/geoffweatherall/mootmaker-e2e), not here —
this repo's own acceptance tests stay API-only, machine-to-machine, and never touch a browser or
real email. See
[mootmaker-e2e/testing-strategy.md](https://github.com/geoffweatherall/mootmaker-e2e/blob/main/testing-strategy.md).
