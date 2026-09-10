# mootmaker API

A project that is part of my [Claude Code exploration](https://github.com/geoffweatherall/mootmaker).

A GraphQL API for scheduling meetings in meeting rooms. Clients can create and list rooms, people, and meetings; every account is one of two classes, `standard` or `admin` (see [User classes and authorization](#user-classes-and-authorization)) — admins can also edit rooms and people, standard users can only rename themselves. The API is serverless: AWS AppSync fronts a set of Java Lambda functions backed by DynamoDB, and every component scales to zero so an idle deployment costs (almost) nothing.

## Data model

The GraphQL schema lives in [api/mootmaker.graphql](api/mootmaker.graphql). There are three entities:

- **Room** — `id`, `name`, `capacity`. Capacity is the total number of people the room holds (organiser + attendees).
- **Person** — `id`, `name`, `dateFormat`, `timeFormat`. The two formats are the person's own display preferences (`Usa`/`British`/`Iso` and `TwentyFourHour`/`AmPm`), non-null over GraphQL and defaulting to `Iso`/`TwentyFourHour` for anyone who has never chosen — including every Person written before the preferences existed, and every guest. They are **display-only**: this API accepts and returns ISO-8601 local date-times regardless of anyone's setting, and nothing server-side branches on them (see [Date/time display preferences](#datetime-display-preferences)). Also has a backend-only `cognitoSub` attribute, not exposed over GraphQL: it's set to the Cognito user's `sub` for a Person created automatically on sign-up (see [Sign-up creates a linked Person](#sign-up-creates-a-linked-person)), and left unset for people added directly (e.g. guests with no login), so a future account-deletion flow can find and remove the Person linked to a deleted Cognito user.
- **Meeting** — `id`, `room`, `organiser` (a Person), `attendees` (list of Person), `subject`, `startTime`, `endTime`. `subject` must not be null or blank. Times are ISO-8601 local date-times with no time-zone offset (`java.time.LocalDateTime` semantics), e.g. `2026-07-01T14:30:00`, must fall on a 15-minute boundary, and `startTime`/`endTime` must fall on the same calendar date — a meeting cannot span midnight (see [Validation](#validation)).

All `id` values are server-generated UUIDs; clients never supply ids on creation.

### Storage

Rooms and people each have their own DynamoDB table (`mootmaker-rooms`, `mootmaker-people`), keyed by `id`. **The meetings table is different: it stores one item per calendar day, not one per meeting.** Its hash key is `pk`, and it holds three kinds of item:

| `pk` | What it is |
|---|---|
| `DAY#2026-09-14` | Every meeting on that date, in one item, plus a `version` number |
| `PTR#<meetingId>` | A pointer from a meeting id to the date whose day item holds it |
| `CONFIG#retention` | The single stored retention boundary (see [Retention](#retention)) |

There are **no GSIs on this table**, and that is the point rather than an omission. A day is reachable by primary key, and a primary-key read can be a `ConsistentRead` — which removes read-after-write staleness as a class, instead of working around it. The previous model needed three secondary structures (`bucket-startTime-index`, `roomId-startTime-index`, and a `mootmaker-meeting-participants` join table) to answer questions this one answers by key.

Meetings are still stored **normalised** inside the day item: a meeting holds `roomId`, `organiserId` and `attendeeIds`, never the room or person objects, so renaming a room or a person is reflected everywhere with no propagation step.

`startTime`/`endTime` keep their canonical, always-19-character format (`MeetingRecord.DATE_TIME_FORMAT`, e.g. `2026-07-01T09:00:00`). A meeting still cannot span midnight (see [Validation](#validation)), and that rule now carries more weight than it used to: it is what makes "one calendar day" a well-defined unit to store, retain and broadcast at all.

#### What one item per day costs

Worth stating plainly, because it is a real trade and not a free win:

- **Write amplification.** Rewriting a day rewrites every meeting on it. The cost grows linearly with how full the day is, and the last booking of the day pays for every earlier one.
- **Contention.** Two people booking the same day contend for one item's `version`. `DayRepository` writes conditionally on it and retries; a version of `0` becomes a "must not exist" condition rather than an equality check, because two racing first-writes would otherwise both pass a comparison against zero.
- **A hard ceiling.** A day item must fit DynamoDB's 400 KB, so there is a maximum number of meetings per day, enforced as `DayIsFull` — a rejection naming the *day*, not the room. A booking can be refused with rooms still free.

#### Reading days

`Query.workspace(dates: [String!])` is the only way meetings are read. It takes an explicit list of dates, fetches those day items by key, and returns them in the order asked for. There is no "all meetings" query and deliberately no way to ask for one: an unbounded read has no bound on its response, and the previous model's `Scan` fallback is exactly what this design removed.

At most 42 dates per request — enough for the calendar's six-week span with a week to spare. Asking for more is refused with a message naming both the number requested and the limit.

`Query.meeting(id: ID!)` is the one remaining lookup that is not date-keyed, and the `PTR#` pointers exist solely to serve it: read the pointer to learn the date, then read that day. Pointers are written in the same `TransactWriteItems` as the day item they describe, so a meeting can never exist without the pointer that resolves it — and deleted with their day, so `meeting(id:)` cannot resolve one whose day has been retained away.

#### Suggesting a room

`Query.suggestRoom(startTime, endTime, requiredCapacity)` finds every room with enough capacity that's free over the given time range, ranked smallest surplus capacity first (rooms with equal capacity break ties by name, so the order is stable and predictable) - the "suggest a room" button in the webapp's meeting form calls this once, the first time it's pressed for a given time/attendee count, and the webapp caches the whole ranked list client-side so repeat presses just step to the next entry instead of re-querying (see the [webapp README](https://github.com/geoffweatherall/mootmaker-webapp#readme) for that caching). `SuggestRoomHandler` composes two existing pieces rather than needing anything new: it `Scan`s the rooms table (the same full-table `Scan` `ListRoomsHandler` already does - fine at this project's scale, since there's no capacity GSI and none is needed yet), filters and sorts the candidates by capacity ascending then name, then checks each in turn with `RoomAvailability.hasOverlappingMeeting` (the same per-room GSI query `createMeeting`'s own validation uses), keeping the ones that come back free. Unlike `createMeeting`, this doesn't return a structured error list - it's a best-effort suggestion, not authoritative validation, so invalid or missing input (an unparseable time, a non-positive `requiredCapacity`, `startTime` not before `endTime`) just yields an empty list, same as "no room qualifies." The authoritative rules are still enforced by `createMeeting` when the meeting is actually saved.

#### Why the organiser may not also be an attendee

`OrganiserIsAttendee` is worth a note because **the rule outlived its original reason**. It used to be a technical necessity: one row per (meeting, participant) pair was written to the `meeting-participants` table, so a duplicated id produced two `Put`s at the identical primary key inside one `TransactWriteItems`, which DynamoDB rejects outright — surfacing to the caller as an unhandled server error rather than a validation result.

That table is gone, and with it the technical argument. The rule remains as a plain semantic one: an organiser is already a participant, so listing them again is a mistake worth naming rather than silently deduplicating.

`InsufficientCapacity` counts a `Set` of participant ids rather than `1 + attendeeIds.size()`, which is a separate fix for the same underlying confusion — so a duplicated organiser, already rejected above, cannot *also* raise a spurious capacity error for a room that was actually big enough.

### API operations

| Operation | Kind | Notes |
|---|---|---|
| `workspace(dates: [String!])` | Query | **Everything a screen needs, in one invocation** - the caller's own `Person`, all rooms, all people, one `Day` per requested date, and the bookable/retained `Boundaries`. Sub-fields not selected are not fetched, so `workspace { rooms { id } }` does no people or day lookup at all. Omitting `dates` means no days are wanted. At most 42 dates |
| `meeting(id: ID!)` | Query | The one lookup that is not a date range, backed by an id -> date pointer. Returns `null` both for an id that never existed and for one whose day has passed out of retention - deliberately the same answer |
| `suggestRoom(startTime, endTime, requiredCapacity)` | Query | Every `Room` with sufficient capacity that is free over that range, smallest surplus first (empty list if none qualify) - see [Suggesting a room](#suggesting-a-room) |
| `createRoom(room)` | Mutation | **Admin only.** Returns `CreateRoomResult` (the whole `rooms` collection, the room, or errors) |
| `updateRoom(id, room)` | Mutation | **Admin only.** Replaces a room's name/capacity. Returns `UpdateRoomResult` (including `RoomNotFound`) |
| `createPerson(person)` | Mutation | **Admin only.** Returns `CreatePersonResult`; no validation beyond a required `name` |
| `updatePerson(id, person)` | Mutation | **Self, or admin.** Renames a person and propagates to Cognito for a linked account (see [Denormalised data](#denormalised-data-cognitos-name-attribute)) |
| `updateMyPreferences(preferences)` | Mutation | **Self only, no admin override.** Sets the caller's own `dateFormat`/`timeFormat`. Both required - it replaces the pair rather than patching one |
| `createMeeting(meeting)` | Mutation | Returns `CreateMeetingResult` - the meeting, its whole updated `Day`, or validation errors. Select `day` to let a day-keyed client cache absorb the new state with no update logic |
| `createMeetings(date, meetings)` | Mutation | Day-scoped bulk creation: one operation, one item write, one broadcast. At most 99 per call, because the day item and one pointer per meeting go in a single DynamoDB transaction, which caps at 100 items |
| `deleteMyAccount` | Mutation | **Self only.** Cancels upcoming meetings the caller organises, removes them from ones they attend, deletes their `Person` and their Cognito user. Irreversible, and refused for reserved system accounts |
| `publishDaysInvalidated(dates)` | Mutation | **Not callable by any client.** `@aws_iam`-only, so AppSync refuses the call before a resolver runs. Exists solely to drive the subscription below - see [Real-time updates](#real-time-updates) |
| `daysInvalidated` | Subscription | Pushes the **dates** that changed, never the data - see [Real-time updates](#real-time-updates) |

Wiping stored data is no longer an API operation - see [Reset and real user accounts](#reset-and-real-user-accounts).

Sample requests for every operation are in [api/requests.http](api/requests.http). To use them: deploy, run `source authenticate.sh <environment>`, open the file in VS Code (REST Client extension), and run the **"Get an access token"** request first — the other requests reference the returned token via `{{cognitoToken.response.body.$.access_token}}` and send it in the `Authorization` header. Tokens last 1 hour; re-run the token request when one expires.

## How it is implemented

```
Client ──HTTP/GraphQL──▶ AWS AppSync ──direct Lambda resolver──▶ Java Lambda ──▶ DynamoDB
```

- **AWS AppSync** hosts the GraphQL endpoint and validates requests against the schema. Authentication is a **Cognito user pool** — every request must carry a valid JWT (see [Authentication](#authentication)). Every query and mutation field has its own resolver.
- Each resolver is a **direct Lambda resolver**: the request template forwards the whole AppSync context (`$ctx`) as the Lambda payload, and the response template returns the Lambda result as-is. There is no VTL mapping logic — all behaviour lives in Java.
- **A single shared `resolvers` Lambda function fronts all 10 GraphQL fields** (list-rooms, list-people, my-person, list-meetings, suggest-room, create-room, update-room, create-person, update-person, create-meeting): [ResolverDispatchHandler](impl/src/main/java/com/mootmaker/handler/ResolverDispatchHandler.java) routes each request on `parentTypeName.fieldName` (already present in every AppSync request via the shared pass-through template) to the matching per-field handler class, e.g. `com.mootmaker.handler.CreateMeetingHandler` — so a user's calls across several fields can land on the same warm/SnapStart-restored execution environment instead of each field paying its own restore. All per-field handler instances are built eagerly in the constructor, so every operation is exercised during Lambda INIT and captured in the SnapStart snapshot. Business logic is unchanged and still lives entirely in the per-field handler classes. Three more Lambda functions aren't GraphQL resolvers at all, and are all built from the same shaded jar (`impl/target/mootmaker-api.jar`) as `resolvers`: `post-confirmation-create-person` is a Cognito trigger (see below); `database-reset`/`database-repair` (see [Reset and real user accounts](#reset-and-real-user-accounts)) are invoked directly via `aws lambda invoke`, never through AppSync or Cognito. `resolvers` and `post-confirmation-create-person` run at Java 25, 512 MB, 15 s timeout; `database-reset`/`database-repair` run at 512 MB but a 900 s timeout, since their work scales with stored data volume rather than a single request. (See [switching-to-a-switchboard-lambda.md](impl/switching-to-a-switchboard-lambda.md) for why the resolver consolidation was done.)
- Handlers read the table names from environment variables (`ROOMS_TABLE_NAME`, `PEOPLE_TABLE_NAME`, `MEETINGS_TABLE_NAME`, `MEETING_PARTICIPANTS_TABLE_NAME`) set by Terraform, and use the AWS SDK v2 DynamoDB client via [DynamoDbClientProvider](impl/src/main/java/com/mootmaker/dynamo/DynamoDbClientProvider.java), a lazily-built singleton reused across warm invocations.
- **The shaded jar is kept as small as reasonably possible**, since jar size is part of what a Java Lambda has to load at cold start: [impl/pom.xml](impl/pom.xml) explicitly excludes `apache5-client` and `netty-nio-client`, two alternative HTTP client implementations the `dynamodb` SDK artifact pulls in transitively that this project never uses (only the synchronous `url-connection-client`, which the SDK would otherwise not even reliably pick — with `apache5-client` also on the classpath, the SDK's default resolution silently prefers it over the one actually configured), and uses `slf4j-simple` rather than `logback-classic` as the SLF4J binding (see [simplelogger.properties](impl/src/main/resources/simplelogger.properties)), since Lambda captures stdout/stderr into CloudWatch Logs directly and has no use for logback's file rotation, async appenders, or layout engine. Together these keep the shaded jar smaller than it would otherwise be (currently ~10.5 MiB, `impl/target/mootmaker-api.jar`; this has grown since these exclusions were first measured, mainly due to the Cognito admin-API dependency added for `AdminUpdateUserAttributes`).
- **DynamoDB** stores the data in four on-demand (`PAY_PER_REQUEST`) tables (see [Storage](#storage)).
- All resources are named with the `mootmaker` prefix and created in `us-east-1` by default (see [deploy/terraform/variables.tf](deploy/terraform/variables.tf)).

## Sign-up creates a linked Person

When a user confirms their email during sign-up (in the [mootmaker-webapp](https://github.com/geoffweatherall/mootmaker-webapp)), a Cognito **PostConfirmation Lambda trigger** ([PostConfirmationCreatePersonHandler](impl/src/main/java/com/mootmaker/handler/PostConfirmationCreatePersonHandler.java), wired up in [cognito.tf](deploy/terraform/cognito.tf)) automatically creates a `Person` using the `name` the user entered on sign-up, and links it to the account via `cognitoSub`.

This runs **after** email confirmation rather than before (a `PreSignUp` trigger would fire while the address is still unverified, risking orphaned Person records for abandoned or typo'd sign-ups) or from the browser (a client-side call after `confirmSignUp()` would leave a confirmed account with no Person if the tab closes or the network drops before that call completes). Cognito also retries `PostConfirmation` invocations on failure, so the handler is idempotent — it checks the `cognitoSub-index` GSI on the People table before writing, and skips creation if a Person already exists for that `sub`. Because Cognito treats an exception thrown here as a failure of the user's confirm-sign-up call (even though the account is already confirmed by that point), the handler logs and swallows any error rather than throwing, so a transient DynamoDB problem never blocks sign-up.

Note: the Terraform-managed e2e test user and demo user ([cognito.tf](deploy/terraform/cognito.tf) `aws_cognito_user.e2e` and `aws_cognito_user.demo`) are created directly rather than through the sign-up/confirm API calls, so neither gets a linked Person this way. The demo user's Person is instead written directly by an `aws_dynamodb_table_item` resource in [cognito.tf](deploy/terraform/cognito.tf), in the same shape `PostConfirmationCreatePersonHandler` would produce and linked via `cognitoSub` to the demo user's `sub`. The e2e test user has no such workaround and so has no Person at all — nothing in the webapp or acceptance tests reads its name.

### Reset and real user accounts

Wiping stored data is no longer part of the GraphQL API - it used to be `Mutation.reset`, callable by any signed-in user, which the [mootmaker business functionality doc](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/business-functionality.md) called out as a known gap ("not currently restricted to administrators"). It's now `database-reset`, an IAM-authenticated Lambda deployed as part of this repo (`impl/src/main/java/com/mootmaker/handler/DatabaseResetHandler.java`, `deploy/terraform/admin-tools.tf`) - closing that gap, since invoking it needs an explicit AWS permission grant rather than just being signed in to the product. It briefly lived in a separate repository, `mootmaker-admin-tools`, between 2026-08-29 and 2026-09-02, before moving back in here - see [mootmaker/designs/admin-tools-into-api.md](https://github.com/geoffweatherall/mootmaker/blob/main/designs/archive/admin-tools-into-api.md) for why.

Reset always deletes every room and meeting. What happens to People and the Cognito user pool depends on the target environment:

- **Outside `production`**, reset also wipes the Cognito user pool down to the two Terraform-managed reserved accounts (the demo user and the e2e test user - see [Sign-up creates a linked Person](#sign-up-creates-a-linked-person) above), deleting every other Cognito user regardless of confirmation status, and logging each deleted user's email to CloudWatch. A Person survives only if its `cognitoSub` matches one of those two reserved accounts' *actual current* `sub` (looked up fresh via `ListUsers`, not trusted from the stored attribute) - so a Person left over from a Cognito account that's since been deleted no longer survives just because the attribute wasn't cleared. This is what lets a reset environment become genuinely indistinguishable from a freshly deployed one.
- **In `production`**, the Cognito wipe is refused outright - a Terraform-computed `ALLOW_COGNITO_WIPE` environment variable, not an invoke-time check, so it's structurally impossible to override per-invocation. Person deletion falls back to the original, narrower rule instead: a Person survives if it has *any* non-null `cognitoSub`, since production may have real signed-up visitors whose Cognito account this Lambda never touches and therefore can't verify still exists.

[mootmaker-demo-data](https://github.com/geoffweatherall/mootmaker-demo-data)'s sample data generator, and this project's own acceptance tests (see [Build, test, deploy](#build-test-deploy)), both invoke it as a first step before creating fresh data. Invoke it directly - `aws lambda invoke`, the AWS console, or the AWS SDK, e.g. `aws lambda invoke --function-name <environment>-mootmaker-database-reset --cli-read-timeout 900 out.json` - there is no wrapper script. Its configured Lambda timeout is 900 seconds, the AWS maximum, so a caller's own client-side timeout needs raising to match or a legitimately-long run can be reported as a client failure while the Lambda keeps running (or even succeeds) regardless.

A second Lambda, `database-repair` (`DatabaseRepairHandler`, same Terraform file), runs maintenance repairs directly against Cognito/DynamoDB that the API itself has no way to fix - backfilling a missing Person for a confirmed Cognito user. (It used to also reconcile the `meeting-participants` derived index against the meetings table; that table and its repair are gone, because nothing is stored twice any more.) Invoked the same way, with an optional `{"dryRun": true}` payload: `aws lambda invoke --function-name <environment>-mootmaker-database-repair --payload '{"dryRun": true}' --cli-binary-format raw-in-base64-out out.json`.

### Displaying the signed-in user's name

The webapp shows the signed-in user's name by reading it live from the `Person` record rather than from the Cognito JWT, so a "change my name" only has to write one place. It arrives as `workspace { me { ... } }` — a selection on the composite entry point, not a query of its own.

That is a change worth noticing if you knew the old shape: this used to be `Query.myPerson`, resolved by a `MyPersonHandler` that read the `Person` off a `cognitoSub-index` GSI. Both are gone. The caller's person id now arrives on the token as the `custom:personId` claim, so the lookup is by primary key and the GSI that existed to support it was deleted along with the handler. `me` is still selected explicitly, because the Person record — not the claim — is the source of truth for the *name*.

This was chosen over customising the `name` claim in the ID token with a Pre Token Generation Lambda trigger. That approach would also work, but a token's claims are only refreshed on next sign-in/token-refresh (up to the token's ~1 hour lifetime), so a rename would appear stale for up to an hour; reading `me` on demand is always current. It also avoids a subtler correctness gap: `ConfirmSignUp` invokes the `PostConfirmation` trigger synchronously and Cognito won't authenticate an unconfirmed user, so the trigger that creates the Person is guaranteed to have *run* before any sign-in — but not guaranteed to have *succeeded* (its DynamoDB write is deliberately swallowed on error, see above) or to be *visible yet* (DynamoDB GSI reads are only eventually consistent, and the webapp signs the user in immediately after confirming). A Pre Token Generation trigger racing that same window could bake a missing/stale name into a token for up to an hour; `myPerson` just returns `null` for that one moment and the webapp's existing email/JWT-name fallback covers it until the next query.

## Authentication

All access to the API is authenticated by an **Amazon Cognito user pool** (`mootmaker-users`, created by Terraform in [deploy/terraform/cognito.tf](deploy/terraform/cognito.tf)). Users sign in with an **email address and password**; Cognito emails a verification code on sign-up, and account recovery (forgot password) works the same way — a code emailed to the verified address. These are sent through Amazon SES, via the `mail.mootmaker.com` identity verified in `mootmaker-domain` (`email_configuration` in `cognito.tf`), rather than Cognito's own built-in mailer — that default sender has a low, undocumented daily cap shared across every user pool in the account, which real usage plus the acceptance suite's own account creation started hitting. There is no API key.

Every GraphQL request must carry a JWT issued by the user pool in the `Authorization` header (the raw token, no `Bearer` prefix). Enforcement happens in two layers:

1. **AppSync** is configured with `AMAZON_COGNITO_USER_POOLS` authentication: it verifies the token's signature, issuer, and expiry against the user pool **before any resolver runs**, and returns HTTP 401 `UnauthorizedException` otherwise.
2. **Every Lambda handler** re-checks, before running any logic, that the AppSync context it received contains an authenticated `identity` ([Identity.requireAuthenticated](impl/src/main/java/com/mootmaker/handler/Identity.java)) — defence-in-depth in case the API is ever accidentally exposed without the authoriser.

The user pool has three app clients (plus a hosted domain used only for the OAuth2 token endpoint):

| App client | Kind | Used by |
|---|---|---|
| `mootmaker-webapp` | Public (no secret), SRP auth flow | The [mootmaker-webapp](https://github.com/geoffweatherall/mootmaker-webapp) browser SPA: users sign up / sign in and their id token is sent with each GraphQL call |
| `mootmaker-acceptance-tests` | Confidential (client secret), OAuth2 `client_credentials` flow | The [verify/](verify/) acceptance tests and [api/requests.http](api/requests.http) |
| `mootmaker-demo-data` | Confidential (client secret), OAuth2 `client_credentials` flow | [mootmaker-demo-data](https://github.com/geoffweatherall/mootmaker-demo-data), which reads its id and secret from SSM at runtime — see below |

The resource server (`mootmaker-api`) defines two OAuth2 scopes: `execute` (general API access) and `admin` (see [User classes and authorization](#user-classes-and-authorization)). `mootmaker-acceptance-tests` requests both — `authenticate.sh`'s `COGNITO_TEST_SCOPE` output is the space-separated pair — so M2M-authenticated tooling can call the admin-gated mutations without needing a real Cognito user. `mootmaker-demo-data` requests the same pair.

#### How mootmaker-demo-data gets its credentials

It has its **own** app client rather than borrowing the acceptance tests'. Sharing one credential between two unrelated consumers meant neither could be rotated or revoked without breaking the other, and CloudTrail could not tell them apart.

[demo-data-credentials.tf](deploy/terraform/demo-data-credentials.tf) publishes that client's id and secret — plus the GraphQL URL, token endpoint and scopes — to SSM Parameter Store under `/mootmaker/<environment>/demo-data/`, and demo-data's Lambda reads them **at runtime**. The client secret is a `SecureString` on the AWS-managed `alias/aws/ssm` key (free; a customer-managed key would be $1/month per environment).

That path is derived from the environment name alone, which is the point: demo-data's deploy needs nothing from this project's Terraform state, so the secret never lands in its state or in a Lambda environment variable, and the two repos' releases stay uncoupled. It is the same deterministic-name loose coupling used for the `database-reset` function name.

### The schema is published as a package

`api/mootmaker.graphql` is the source of truth for the API contract, and is published whenever it
changes on `main` (see [.github/workflows/publish-schema.yml](.github/workflows/publish-schema.yml)):

| Registry | Artifact | Who consumes it |
|---|---|---|
| npmjs.com | `@mootmaker/schema` | `mootmaker-webapp`, which generates its types and operations from it |
| GitHub Packages | `com.mootmaker:mootmaker-schema` | `mootmaker-android` and `mootmaker-demo-data`, once they adopt codegen |

The split is deliberate: **GitHub Packages requires an access token to install even a public
package**, which would make `mootmaker-webapp` unbuildable for anyone cloning it. npmjs.com has no
such restriction. The Maven consumers are this project's own repositories, which already
authenticate, so the token costs nothing there.

**Authentication is by OIDC trusted publishing, not a token.** The workflow authenticates as
itself; there is no `NPM_TOKEN` secret to rotate. npm revoked classic tokens in December 2025, and
write-enabled granular tokens expire within 90 days and stop working for publishing in January 2027,
so a token here would have been a thing to rebuild rather than maintain. It also means the published
package carries a provenance attestation linking it to the commit that produced it.

The trusted publisher is configured on the package itself (npmjs.com → the package → Settings),
naming `geoffweatherall/mootmaker-api` and `publish-schema.yml`. Because npm requires a package to
exist before a trusted publisher can be attached to it, `1.0.0` was published by hand once; every
version after it comes from this workflow.

`api/package.json` holds the version, bumped in the same pull request as the schema change.
Registries are immutable, so forgetting to bump cannot silently succeed — the workflow checks
explicitly and fails with a message naming the file to edit, rather than a raw 409.

Publishing is a standalone workflow rather than part of a deploy pipeline: it needs no AWS
credentials and no environment targeting, so it does not wait for the CI/CD design. See
[mootmaker/designs/graphql-schema-sharing.md](https://github.com/geoffweatherall/mootmaker/blob/main/designs/archive/graphql-schema-sharing.md).

### Demo user

This is a demo system rather than a real business, so every deployment — including a "production" one — includes a pre-confirmed, publicly-known demo user (`demo@mootmaker.com`, Terraform outputs `demo_user_email` / `demo_user_password`, resources `aws_cognito_user.demo` / `random_password.demo_user` in [cognito.tf](deploy/terraform/cognito.tf)) that anyone can sign in as without creating their own account. Its password is randomly generated at deploy time (like the e2e test user's), but restricted to lowercase letters and digits only, so it's easy to read and type by hand when the webapp shows it on the home page. It is not a secret and its output is not marked `sensitive` — the whole point is that it's shown in the clear. (An earlier version used a fixed password, `demo1234`, which turned out to be on Google's list of known-compromised passwords; it's random now to avoid that.)

The user pool's password policy is set correspondingly loose to match: a minimum of 10 characters with a lowercase letter and a number, and no requirement for uppercase letters or symbols. A real product would want a stricter policy; this one is deliberately weakened so the demo password (and anyone else's) is easy to type.

### Authentication in end-to-end tests

Both projects' end-to-end tests run non-interactively (a dev shell or CI), so neither can prompt a human for credentials. They authenticate differently because they test different things:

- **The API acceptance tests in [verify/](verify/) use machine-to-machine (M2M) auth** — the OAuth2 **client_credentials flow**. [GraphQlClient](verify/src/test/java/com/mootmaker/verify/GraphQlClient.java) POSTs the test client's id and secret (read from the `COGNITO_TEST_CLIENT_ID` / `COGNITO_TEST_CLIENT_SECRET` environment variables, which `authenticate.sh` populates from Terraform outputs) to the user pool's token endpoint (`COGNITO_TOKEN_URL`) and receives a short-lived (1 h) JWT access token scoped to `mootmaker-api/execute`, which AppSync accepts like any user token. One token is fetched per test run and shared by all test classes.
- **The webapp's Playwright tests sign in as a real user** — a Terraform-managed, pre-confirmed user `e2e-tests@example.com` (outputs `e2e_user_email` / `e2e_user_password`). A browser sign-in form inherently needs a user, and exercising the real sign-in UI is part of what those tests verify.

[AuthenticationAcceptanceIT](verify/src/test/java/com/mootmaker/verify/AuthenticationAcceptanceIT.java) proves the API is closed: requests with no token, a malformed token, or a forged JWT all get HTTP 401 and no data, while a client_credentials token succeeds.

Most acceptance tests reset the database to a known state immediately before they act, so they can't be thrown off by data left behind by another test or a previous run. Since `Mutation.reset` no longer exists (see [Reset and real user accounts](#reset-and-real-user-accounts)), they do this by invoking the `database-reset` Lambda directly via the AWS SDK ([DatabaseReset](verify/src/test/java/com/mootmaker/verify/DatabaseReset.java)) rather than through GraphQL - a different auth mechanism (AWS IAM, via whatever credentials are running the tests) from the M2M JWT used for the GraphQL calls above. `database-reset` is deployed by this same repo's `deploy.sh`, so there's no separate deployment step to remember - just deploy this environment normally before running `verify.sh` against it. [DatabaseResetCognitoWipeAcceptanceIT](verify/src/test/java/com/mootmaker/verify/DatabaseResetCognitoWipeAcceptanceIT.java) additionally proves reset's Cognito-wipe survivor logic (a throwaway Cognito user is deleted, the demo account survives) against a real deployed pool.

## Real-time updates

One user's booking appears on another user's screen without a refetch. The chain is short but every
link in it has a silent failure mode, so it is worth knowing in full.

1. A write commits (`createMeeting` or `createMeetings`).
2. The resolver Lambda calls the API's **own** `publishDaysInvalidated` mutation over IAM-signed
   HTTP ([DaysInvalidatedPublisher](impl/src/main/java/com/mootmaker/realtime/DaysInvalidatedPublisher.java)).
   AppSync has no server-side publish API - a broadcast *is* a mutation call.
3. `@aws_subscribe` pushes that mutation's return value to every client subscribed to
   `daysInvalidated`.
4. Each client evicts `Day:<date>` from its cache; its ordinary gap fetch refills it.

**Why a separate publish field rather than subscribing to `createMeeting`.** A rejected
`createMeeting` returns *successfully*, carrying a typed `errors` array, and AppSync broadcasts it
exactly like a success - so every client would be woken by bookings that never happened. A field
called only after a write has already committed has nothing to filter out.

**Why dates and never data.** AppSync caps a subscription payload at 240 KB against 5 MB for a
resolver response, while a worst-case `Day` is ~618 KB of JSON - so a broadcast could not carry a day
even if it wanted to. Beyond fitting: the payload is tiny and uniform whatever the day holds, one
channel serves every kind of change, and it is idempotent, where merging the same meeting twice
would need deduplication.

**The permissions are the boundary, not the documentation.** `publishDaysInvalidated` is
`@aws_iam`-only, so AppSync refuses a signed-in user's call before any resolver runs. The Lambda's
`appsync:GraphQL` grant names that single field rather than the API, because `apis/<id>/*` would let
it call every mutation as an IAM principal, bypassing the admin checks other resolvers perform.

**A failed broadcast is logged and swallowed.** The write has already committed and been reported to
the caller, so failing the mutation would turn a missed refresh into a lost booking - at the exact
moment AppSync is unhealthy.

**Things that fail silently here**, all verified against a real AppSync API rather than read from
documentation, and all with no error visible to the subscriber:

- Omitting either type-level directive on `Invalidation` (`@aws_iam @aws_cognito_user_pools`).
  Subscribing still succeeds; nothing is ever delivered. The only symptom is on the *publisher's*
  response, which is why the publisher inspects a 200 body for `errors` rather than trusting the
  status code.
- Exceeding the 240 KB payload cap. The publish returns success and delivery simply does not happen.
- A subscription filter using an unsupported field or operator. Two such combinations matched in
  *opposite* directions - one dropped everything, the other dropped nothing - and neither raised an
  error.

The one loud failure is a subscription whose type does not match its mutation's return type: AppSync
rejects the schema at deploy with `The subscription has an invalid output type.`

**The realtime endpoint path differs by host.** On the custom domain it is `/graphql/realtime`; on
the raw AppSync realtime host it is `/graphql`. Using the wrong one fails to connect at all. See
[AppSyncSubscription](verify/src/test/java/com/mootmaker/verify/AppSyncSubscription.java), which is
hand-rolled because AppSync refuses the `graphql-transport-ws` subprotocol every client library
speaks.

## User classes and authorization

Every Cognito user has a `custom:class` attribute, `standard` or `admin`, included in the ID token as the `custom:class` claim. `PostConfirmationCreatePersonHandler` (see [Sign-up creates a linked Person](#sign-up-creates-a-linked-person) above) sets it to `standard` for every new sign-up via `AdminUpdateUserAttributes`, right after creating the linked Person — the client is never trusted to set its own class, and the webapp's `mootmaker-webapp` app client is deliberately not granted write access to `custom:class` (see its `write_attributes` in [cognito.tf](deploy/terraform/cognito.tf)), so a signed-in user can't self-promote by calling Cognito's own attribute-update API directly. The Terraform-managed demo user is `admin` and the e2e test user is `standard` (`aws_cognito_user.demo` / `aws_cognito_user.e2e` in [cognito.tf](deploy/terraform/cognito.tf)).

[Identity.requireAdmin](impl/src/main/java/com/mootmaker/handler/Identity.java) is the enforcement point, checked before any logic runs in an admin-only handler (`CreateRoomHandler`, `UpdateRoomHandler`, `CreatePersonHandler`) — same shape as `Identity.requireAuthenticated`, but also accepting a caller whose `scope` claim contains the `mootmaker-api/admin` OAuth scope, so the M2M `mootmaker-acceptance-tests` and `mootmaker-demo-data` clients keep working without a real Cognito user or `custom:class` claim behind them. `UpdatePersonHandler` uses the softer `Identity.isAdmin` instead: a caller may update a person if they're admin *or* if the target person's `cognitoSub` matches their own `identity.sub` (a self-rename).

This is enforced **server-side only** — the webapp's `isAdmin` flag (read from the same JWT claim) only decides what the UI shows; a standard user calling `updateRoom` directly still gets rejected by the Lambda regardless of what the client thinks.

Accounts confirmed before this feature shipped have no `custom:class` attribute at all; every check above only ever tests for `== "admin"`, so a missing claim behaves exactly like `standard` — fail-safe, no backfill needed.

### Denormalised data: Cognito's `name` attribute

Meetings aren't denormalised by room/person name at all — see [Storage](#storage) above — so a rename via `updateRoom`/`updatePerson` is reflected everywhere automatically with no extra propagation. The one real exception is Cognito's own `name` user attribute, a separate copy of a linked person's name set once at sign-up. `UpdatePersonHandler` keeps it in sync: whenever the target person has a `cognitoSub`, it calls `AdminUpdateUserAttributes` to set Cognito's `name` to match, after the DynamoDB write succeeds — for both a self-rename and an admin renaming someone else's linked account. This call is best-effort (logged and swallowed on failure, like the PostConfirmation trigger) so a transient Cognito problem never fails the rename itself; the DynamoDB `Person.name` remains the source of truth read by `workspace { me }`, so a swallowed sync failure only means the *next* sign-in's JWT `name` claim is briefly stale, not that the rename was lost.

**Known trade-off, accepted as-is:** the demo person ("Demo Strater") is declared by an `aws_dynamodb_table_item` Terraform resource (see [Sign-up creates a linked Person](#sign-up-creates-a-linked-person) above). If an admin renames it via the webapp, a future unrelated `terraform apply` for that environment will silently revert the name back to "Demo Strater" the next time that resource is applied. This is a known, deliberately-unfixed gap (no `lifecycle { ignore_changes }` guard) — harmless for a demo system, just worth knowing if it's ever confusing during a demo.

## Date/time display preferences

A `Person` carries a `dateFormat` and a `timeFormat`, set by their owner in the webapp's Settings page and used by clients to render and parse date/times for humans.

**They change nothing about this API.** Every date/time crossing the GraphQL boundary is an ISO-8601 local date-time with no time-zone offset (`java.time.LocalDateTime` semantics, e.g. `2026-07-01T14:30:00`) — `Meeting.startTime`/`endTime`, `MeetingInput`, `suggestRoom`'s arguments, and the `MeetingsFilter` window — in both directions, for every caller, regardless of their preference. This is a display preference stored as data, not content negotiation: nothing here renders a date, parses a localized one, or varies its wire format by who is asking. A wrong preference can only show a human the right instant written the wrong way round; it can never corrupt stored data or change validation.

Both fields are non-null in the schema, but the DynamoDB attributes behind them are optional — Persons written before this feature simply lack them. [`Person.fromItem`](impl/src/main/java/com/mootmaker/model/Person.java) substitutes the defaults, which is the single point holding the non-null guarantee up against pre-existing data, so it is unit-tested directly in [`PersonTest`](impl/src/test/java/com/mootmaker/model/PersonTest.java). An unrecognised stored value also falls back to the default rather than failing the read.

[`UpdateMyPreferencesHandler`](impl/src/main/java/com/mootmaker/handler/UpdateMyPreferencesHandler.java) is **self-only with no admin bypass**, deliberately unlike `updatePerson`: a personal display preference isn't profile data an admin should set on someone else's behalf, so the handler takes no id at all and always targets the Person linked to `identity.sub`. Like `UpdatePersonHandler` it does a full-item `PutItem`, so it carries `name` and `cognitoSub` forward explicitly — the mirror image of that handler's own care, in the other direction.

## Directory structure

| Path | Contents |
|---|---|
| [api/](api/) | GraphQL schema ([mootmaker.graphql](api/mootmaker.graphql)) and sample requests ([requests.http](api/requests.http)) |
| [impl/](impl/) | Maven project with the Java Lambda handlers (`com.mootmaker.handler.*`), model records (`com.mootmaker.model.*`), and unit tests. Builds the shaded jar deployed to Lambda. |
| [deploy/terraform/](deploy/terraform/) | Terraform for all AWS resources: AppSync API, resolvers and data sources ([appsync.tf](deploy/terraform/appsync.tf)), Cognito user pool, app clients, the e2e test user, and the public demo user ([cognito.tf](deploy/terraform/cognito.tf)), the resolvers/post-confirmation Lambda functions ([lambda.tf](deploy/terraform/lambda.tf)) and the `database-reset`/`database-repair` Lambda functions with their own dedicated IAM roles ([admin-tools.tf](deploy/terraform/admin-tools.tf)), DynamoDB tables ([dynamodb.tf](deploy/terraform/dynamodb.tf)), the shared resolver IAM role ([iam.tf](deploy/terraform/iam.tf)), outputs (API URL, Cognito ids, test and demo user credentials). All resource names are prefixed with `<environment>-<project_name>` ([locals.tf](deploy/terraform/locals.tf)) so multiple environments can coexist in one AWS account. State is stored remotely in S3, one state file per environment ([backend.hcl](deploy/terraform/backend.hcl) — see the [mootmaker-bootstrap-terraform](https://github.com/geoffweatherall/mootmaker-bootstrap-terraform) README for how that bucket is set up, and the [mootmaker project README](https://github.com/geoffweatherall/mootmaker#multi-environment-deployments) for the multi-environment design). |
| [verify/](verify/) | Maven project with JUnit acceptance tests (`*IT.java`, run by failsafe) that exercise the **deployed** API over HTTP, resetting data via `database-reset` rather than a GraphQL mutation (see [Authentication in end-to-end tests](#authentication-in-end-to-end-tests)). |

See [testing-strategy.md](testing-strategy.md) for the overall testing approach for this repo (unit vs. acceptance tests, ephemeral environments, how Cognito verification codes are read in tests), and [mootmaker's testing-strategy.md](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/testing-strategy.md) for how it fits the wider project.

### Bash scripts

All scripts live in the project root and are run from there:

| Script | What it does | How to run |
|---|---|---|
| [deploy.sh](deploy.sh) | Builds the Lambda jar (`mvn clean package` in `impl/`), then `terraform init` + `terraform apply -auto-approve` to create/update all AWS resources **for the given environment**. Creates real AWS resources — run deliberately. Add `--skip-build` to deploy the jar already in `impl/target/` instead of rebuilding it; the release pipeline uses this so the *same* artifact is promoted from `test` to `production` rather than rebuilt per environment. | `./deploy.sh <environment> [--skip-build]` |
| [undeploy.sh](undeploy.sh) | `terraform destroy` — deletes the AppSync API, Lambdas, and DynamoDB tables **including all stored data**, for the given environment. Prompts for confirmation. `--yes` skips the prompt for automation, and is deliberately *narrower* than the interactive path: it refuses `production` and `test` outright rather than asking. | `./undeploy.sh <environment> [--yes]` |
| [authenticate.sh](authenticate.sh) | Reads the given environment's Terraform outputs and exports `GRAPHQL_API_URL`, the `COGNITO_*` variables (user pool id, webapp client id, token URL, test client id/secret/scope) and the `E2E_USER_*` test-user credentials into the current shell. Must be **sourced**, not executed. | `source authenticate.sh <environment>` |
| [verify.sh](verify.sh) | Sources `authenticate.sh <environment>`, then runs the acceptance tests (`mvn clean verify` in `verify/`) against that environment's deployed API. `database-reset` is part of this same repo's Terraform, so `./deploy.sh <environment>` is all that's needed first (see [Authentication in end-to-end tests](#authentication-in-end-to-end-tests)). | `./verify.sh <environment>` |

## Build, test, deploy

Prerequisites: Java 25, Maven, Terraform ≥ 1.10, and AWS credentials configured for the target account, including `lambda:InvokeFunction` on `database-reset` to run `verify.sh` (granted automatically to whatever credentials also deployed it).

Every deploy/undeploy/authenticate/verify script takes an **environment** name
(e.g. `test`, `production`, or your own name for a personal sandbox) so
multiple independent copies of the API can run in the same AWS account at
once — see the [mootmaker project README](https://github.com/geoffweatherall/mootmaker#multi-environment-deployments)
for the full multi-environment how-to and the reasoning behind it.

### Custom domain

Each environment deploys behind its own hostname under `mootmaker.com`:
`production` gets `api.mootmaker.com`, every other environment gets
`api.<environment>.mootmaker.com` (see [domain.tf](deploy/terraform/domain.tf)
for why each environment provisions its own certificate rather than sharing
one wildcard). `deploy.sh`/`undeploy.sh` refuse any environment name that
starts with `prod` but isn't exactly `production`, to avoid a typo silently
landing on a production-looking-but-not-actually-production subdomain.
Requires [mootmaker-domain](https://github.com/geoffweatherall/mootmaker-domain)
to already be deployed, with its nameservers configured at the registrar and
delegation propagated - see that project's README.

```bash
# Build the Lambda jar and run unit tests
mvn -f impl/pom.xml clean package

# Deploy (build + terraform apply) to an environment, e.g. "test" or your own name
./deploy.sh test

# Run acceptance tests against that environment's deployed API
./verify.sh test

# Tear it down when you're done
./undeploy.sh test
```

The acceptance tests need a deployed API; they read the endpoint and the Cognito client_credentials settings from the environment variables exported by `authenticate.sh`, and fetch a JWT from the token endpoint before calling the API (see [Authentication](#authentication)). Note that `reset` and the acceptance tests delete/modify live data, so don't point them at a deployment you care about.

## Cost model

Every component is configured to scale to zero, so a deployed-but-idle API costs effectively nothing. All costs are **per-use**:

| Resource | Billing | Idle cost |
|---|---|---|
| AppSync | Per query/mutation request | $0 |
| Lambda | Per invocation + GB-seconds of execution | $0 |
| DynamoDB | On-demand (`PAY_PER_REQUEST`): per read/write request unit + storage | ~$0 (storage only, negligible at this scale) |
| Cognito | Per monthly active user (10k free), plus $0.00225 per M2M token issued to the acceptance-test client (no free tier) | $0 |
| CloudWatch Logs | Per GB ingested/stored from Lambda logs | ~$0 when idle |
| ACM certificate (custom domain) | Free when attached to AppSync | $0 |
| Route53 record (custom domain) | Covered by [mootmaker-domain](https://github.com/geoffweatherall/mootmaker-domain)'s hosted zone; query volume is negligible at this scale | $0 |

There are no fixed-price resources (no provisioned DynamoDB capacity, no EC2/containers, no NAT gateways, no provisioned Lambda concurrency). Costs scale linearly with API call volume: each GraphQL call is one AppSync request, one Lambda invocation, and one or more DynamoDB operations.

One scaling caveat the previous model had is simply gone: there is no unfiltered `meetings` query to scan the whole table with, because there is no unfiltered meetings query at all. Reads name their dates, and a day is fetched by primary key, so read cost tracks *what was asked for* rather than total stored data. Retention caps the table at 217 day items regardless (see [Retention](#retention)) — a hard bound on row count, not a growth rate.

`createMeeting` writes cost more than one write request unit: it writes the whole day item plus one `PTR#` pointer in a single `TransactWriteItems` call, which DynamoDB bills at 2× the normal per-item write cost, and the day item's size grows with how full that day already is. This is the write amplification described under [Storage](#storage), showing up on the bill — the trade taken deliberately in exchange for consistent primary-key reads.

## Validation

### How it works

Validation is implemented entirely in the Java Lambda handlers (not in AppSync/VTL, apart from the type/nullability checks the GraphQL schema itself enforces). The create mutations for rooms and meetings never throw GraphQL errors for rule violations; instead they return a **structured result object**:

- `CreateRoomResult { room, errors: [RoomError!]! }` / `UpdateRoomResult { room, errors: [RoomError!]! }`
- `UpdatePersonResult { person, errors: [PersonError!]! }`
- `CreateMeetingResult { meeting, errors: [MeetingError!]! }`

On success the entity field is populated and `errors` is empty. On failure the entity field is `null` and `errors` contains **one enum entry per rule broken** — the handlers collect all violations rather than stopping at the first, so a client gets the complete list in one round trip. Nothing is written to DynamoDB unless validation passes.

### Rules

`createRoom` ([CreateRoomHandler](impl/src/main/java/com/mootmaker/handler/CreateRoomHandler.java)) and `updateRoom` ([UpdateRoomHandler](impl/src/main/java/com/mootmaker/handler/UpdateRoomHandler.java)):

| Error | Rule |
|---|---|
| `NameRequired` | `name` must not be null or blank |
| `CapacityTooLow` | `capacity` must be ≥ 2 |
| `RoomNotFound` | `updateRoom` only: `id` must refer to an existing room |

`updatePerson` ([UpdatePersonHandler](impl/src/main/java/com/mootmaker/handler/UpdatePersonHandler.java)):

| Error | Rule |
|---|---|
| `NameRequired` | `name` must not be null or blank |
| `PersonNotFound` | `id` must refer to an existing person |

`updateMyPreferences` ([UpdateMyPreferencesHandler](impl/src/main/java/com/mootmaker/handler/UpdateMyPreferencesHandler.java)):

| Error | Rule |
|---|---|
| `NoLinkedPerson` | The caller must have a linked Person to store a preference against |

Both formats being non-null in `PreferencesInput` means AppSync rejects a missing or null one before the resolver runs, so there is no validation rule here beyond the above.

A room's capacity can be reduced below the size of a meeting already booked into it — nothing retroactively re-validates past decisions, matching how nothing else in this API does either.

`createMeeting` ([CreateMeetingHandler](impl/src/main/java/com/mootmaker/handler/CreateMeetingHandler.java)):

| Error | Rule |
|---|---|
| `StartMissaligned` / `EndMissaligned` | Start/end time must parse as an ISO-8601 local date-time and fall exactly on a 15-minute boundary (no seconds/nanos) |
| `SpansMultipleDays` | `startTime` and `endTime` must fall on the same calendar date — a meeting cannot span midnight |
| `RoomRequired` | `roomId` must not be blank |
| `RoomNotFound` | `roomId` must refer to an existing room |
| `OrganiserRequired` | `organiserId` must not be blank |
| `OrganiserNotFound` | `organiserId` must refer to an existing person |
| `AttendeeNotFound` | Every id in `attendeeIds` must refer to an existing person (one error per missing attendee) |
| `SubjectRequired` | `subject` must not be null or blank |
| `OrganiserIsAttendee` | `organiserId` must not also appear in `attendeeIds` - the organiser is already counted as one of the meeting's people (see `InsufficientCapacity` below) and cannot additionally be listed as an attendee |
| `InsufficientCapacity` | Room capacity must be ≥ the number of distinct people (organiser + attendees, deduplicated by id) |
| `TimeRangeUnavailable` | The room must have no existing meeting overlapping the requested `[startTime, endTime)` range (touching end-to-start is allowed) |

`createPerson` performs no validation beyond the schema's non-null `name`. The acceptance tests in [verify/](verify/) cover these rules, and the admin-only/self-or-admin authorization checks, end-to-end against the deployed API.

## Implementation choices

#### Why M2M was chosen for the API tests

The alternatives considered, and why they lost:

| Approach | Why not |
|---|---|
| **Test user + `USER_PASSWORD_AUTH`** (tests sign in with an email/password from Terraform outputs) | Works, and is marginally cheaper (user sign-ins are covered by the free MAU tier, while M2M tokens cost $0.00225 each with no free tier — pennies per year at this project's scale). But it puts a username/password in the test pipeline and makes the tests impersonate a fake "person", when what is really calling the API is a program. |
| **IAM (SigV4) as a second AppSync auth mode** | Free, but it weakens the security model: the API would no longer have the single invariant "every request carries a user-pool JWT", and the tests would then be exercising a different auth path than real clients use. It also needs AWS-credential signing in the test client. |
| **Keeping an API key for tests only** | Same problem — a second, weaker auth mode that bypasses Cognito entirely, and exactly what this design set out to remove. |

client_credentials won because it is the standard OAuth2 pattern for service-to-service callers: **no username or password exists anywhere in the flow**, the secret is generated by Terraform (never appearing in the repo) and is rotatable/revocable independently of any user, the token's identity honestly says "the acceptance-test client" rather than pretending to be a person, and — crucially — the resulting JWT goes through the **same AppSync user-pool authoriser and the same handler identity check as real user traffic**, so the tests exercise the production auth path. The only extra infrastructure it needs is the hosted domain (for the token endpoint) and the resource-server scope, both free.
