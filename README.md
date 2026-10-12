# mootmaker-api

The GraphQL API behind [Mootmaker](https://github.com/geoffweatherall/mootmaker): AWS AppSync in front
of Java 25 Lambda functions, with data in DynamoDB, accounts in Cognito and profile pictures in S3.
Both the web app and the Android app use it, and it scales to zero: an idle environment costs almost
nothing.

This repository also owns the user pool, the avatars bucket and its CDN, and the Lambdas that reset,
repair and clean up an environment's data. How the API fits into the whole system is in the hub's
[system overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/system.md).

| Read | For |
|---|---|
| [docs/architecture.md](docs/architecture.md) | The request path, code layout, the Lambdas, live updates and their traps, avatars |
| [docs/api-reference.md](docs/api-reference.md) | Every operation, who may call it, and every refusal |
| [docs/data-model.md](docs/data-model.md) | Every table, item and attribute, and the Cognito attributes |
| [docs/cold-starts.md](docs/cold-starts.md) | Why Java Lambdas are slow to start and how SnapStart priming fixes it |
| [testing-strategy.md](testing-strategy.md) | Unit and acceptance tests |
| [api/mootmaker.graphql](api/mootmaker.graphql) | The schema: the contract, with every type and field documented |

## Layout

| Path | What |
|---|---|
| `api/` | The GraphQL schema, and the `package.json` it is published to npm with |
| `impl/` | The Java Lambda code and its unit tests. Builds `impl/target/mootmaker-api.jar` |
| `verify/` | Acceptance tests (JUnit, `*IT.java`) that call a deployed environment over HTTP |
| `deploy/terraform/` | All AWS resources for one environment |
| `deploy/` | `prune-lambda-versions.sh` (run by every deploy), `import-log-groups.sh` |
| `config/` | Checkstyle suppressions, each with its reason |
| `deploy.sh`, `undeploy.sh`, `verify.sh` | Deploy, remove and acceptance-test one environment |

## Building, testing, deploying

Needs Java 25, Maven, Terraform 1.10 or later, and AWS access (`aws sso login`).

```bash
mvn -f impl/pom.xml clean package        # build the jar and run the unit tests
mvn -f impl/pom.xml spotless:apply       # format (CI fails on unformatted code)
./lint-java.sh                           # unused imports and other compiler diagnostics

./deploy.sh <env>                        # build, then terraform apply, for one environment
./deploy.sh <env> --skip-build           # deploy the jar already in impl/target (the release does this)
./verify.sh <env>                        # acceptance tests against that environment (they reset its data)
./undeploy.sh <env>                      # remove it; prompts. --yes skips the prompt and refuses test and production
```

Any environment name works; resources are named `<env>-mootmaker-*`. Deploy this before the web app
and demo-data, which read the settings it publishes. `test` and `production` are changed only by
[releases](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/delivery.md); for your
own work use an ephemeral environment.

## Published configuration

Other components never read this repository's Terraform state. Each deploy publishes what they need to
SSM Parameter Store under `/mootmaker/<env>/api/` (`deploy/terraform/published-config.tf`):

| Parameter | Type | Read by |
|---|---|---|
| `graphql-url`, `region`, `cognito/user-pool-id`, `cognito/webapp-client-id`, `cognito/android-client-id` | String | The web app's deploy (it writes `env-config.js` and `mobile-config.json` from them), test runners |
| `demo-user/email`, `demo-user/password` | String, public by design | The web app's deploy (home page), smoke tests |
| `m2m-client/client-id`, `token-url`, `scope`; `m2m-client/client-secret` | String; SecureString | The web app's pre-deploy schema check, test runners, the `test` smoke test |
| `database-reset/function-name`, `history-cleanup/function-name` | String | Test runners |
| `test-fixtures/users/{admin,standard,no-person}/{email,password}` | String; SecureString for passwords | Test runners. Ephemeral environments only |

demo-data's own credentials are under `/mootmaker/<env>/demo-data/` (`deploy/terraform/demo-data-credentials.tf`),
including the demo account's Person id in `guaranteed-person-ids`.

## Test fixture users

Ephemeral environments only. Every `database-reset` creates any that are missing through the real
sign-up path (so the post-confirmation trigger runs as for a real user) and repairs any a test changed,
rather than recreating them.

| Role | Email | Class | Person |
|---|---|---|---|
| admin | `e2e-admin-user@mail.mootmaker.com` | `admin` | "E2E Admin", `isAdmin` |
| standard | `e2e-standard-user@mail.mootmaker.com` | `standard` | "E2E Standard" |
| no-person | `e2e-no-person-user@mail.mootmaker.com` | `standard` | None, deliberately |

## The schema as a package

`api/mootmaker.graphql` is published when it changes on `main` (`.github/workflows/publish-schema.yml`):

| Registry | Package | Used by |
|---|---|---|
| npmjs.com | `@mootmaker/schema` | mootmaker-webapp and mootmaker-android code generation |
| GitHub Packages | `com.mootmaker:mootmaker-schema` | Nothing yet |

npm publishing uses trusted publishing (OIDC), so there is no token. **Bump `api/package.json`'s version
in the same pull request as any schema change**; the workflow fails clearly if you forget. Registries
are immutable, so a version cannot be republished.

A breaking change (a removed or renamed field, a tightened argument) fails the `schema-compatible` PR
check, because installed Android apps would break. Deprecate instead; the
[API contract overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/api-contract.md#not-breaking-what-is-already-out-there)
explains the rule and the `allow-breaking-schema-change` label.

## Operating it

`database-reset`, `database-repair` and `history-cleanup` are invoked with AWS credentials, never through
the API:

```bash
aws lambda invoke --function-name <env>-mootmaker-database-reset --cli-read-timeout 900 --payload '{}' /dev/stdout
aws lambda invoke --function-name <env>-mootmaker-database-repair --cli-binary-format raw-in-base64-out \
  --payload '{"dryRun": true}' --cli-read-timeout 900 /dev/stdout
```

Without `--cli-read-timeout 900` the AWS CLI gives up after 60 seconds while the function carries on.
When something is wrong, start from the hub's [runbooks](https://github.com/geoffweatherall/mootmaker/blob/main/docs/runbooks/README.md).
