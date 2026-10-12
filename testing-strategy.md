# Testing strategy

How mootmaker-api is tested. The layers across the whole system, and why they are shaped as they are,
are in the hub's [testing overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/testing.md).

| Layer | Where | Runs against | When |
|---|---|---|---|
| Unit | `impl/src/test/` | Nothing: handlers with fake DynamoDB, Cognito and S3 clients | Every pull request (`unit-tests`), every build |
| Acceptance | `verify/src/test/`, `*IT.java` | A deployed environment over real HTTPS, AppSync, Cognito and DynamoDB | Every release, in a fresh `rel-api-*` environment; by hand with `./verify.sh <env>` |
| Static | `code-style` PR check | google-java-format, Checkstyle (Google rules), ecj diagnostics | Every pull request |
| Schema | `schema-parses`, `schema-compatible` PR checks | The schema with standard GraphQL tooling, and diffed against the base branch | Every pull request |

## Unit tests

Fast and deterministic; run them constantly. They cover the validation rules, the size limits and their
consistency proof, the day-item read-modify-write loop (with a fake client that can lose a race on
purpose), meeting versions, avatar decoding, the selection-set logic, the reset and repair
rules, and each handler's authorization checks.

## Acceptance tests

What only a real deployment can show: a missing IAM permission, a Terraform mistake, AppSync wiring, a
Cognito trigger that does not fire, a subscription that never delivers.

- **They authenticate as a machine client** (OAuth2 client credentials, the `mootmaker-acceptance-tests`
  app client), reading its secret from SSM. One token per run. The token passes the same AppSync check
  as a user's.
- **Most reset the environment first** by invoking `database-reset` directly with the caller's AWS
  credentials, so a test never depends on data another left behind. **Never point `verify.sh` at an
  environment whose data you want to keep.**
- **Some act as real users**, the fixture users, where a Cognito user is the point (roles, the
  no-Person path).
- **The subscription tests use a real WebSocket** (`AppSyncSubscription`), because AppSync speaks its own
  protocol and silent delivery failures are the main risk.

The suites, by area:

| Area | Suites |
|---|---|
| Authentication and roles | `AuthenticationAcceptanceIT`, `ReservedAccountRolesAcceptanceIT`, `ResolverErrorAcceptanceIT` |
| Meetings | `CreateMeetingAcceptanceIT`, `CreateMeetingValidationAcceptanceIT`, `UpdateMeetingVersionAcceptanceIT`, `SelectionAwareMeetingsAcceptanceIT`, `SuggestRoomAcceptanceIT` |
| Rooms and people | `CreateRoomAcceptanceIT`, `UpdateRoomAcceptanceIT`, `DeleteRoomAcceptanceIT`, `CreatePersonAcceptanceIT`, `RenamePersonAcceptanceIT`, `SetPersonAdminAcceptanceIT`, `DeletePersonAcceptanceIT` |
| Avatars | `AvatarUploadAcceptanceIT`, which also fetches the image through the avatars CDN |
| Live updates | `DaysInvalidatedAcceptanceIT` |
| Retention and reset | `HistoryRetentionAcceptanceIT`, `DatabaseResetCognitoWipeAcceptanceIT` |

## Not tested here

Real email (sign-up and password-reset codes) and anything in a browser or on a phone: the web app and
the Android app test those through their own acceptance suites, against this API.
