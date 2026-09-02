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

Rooms, people, and meetings each have their own DynamoDB table (`mootmaker-rooms`, `mootmaker-people`, `mootmaker-meetings`), keyed by `id`. Meetings are stored **normalised**: a meeting item holds only `roomId`, `organiserId`, and `attendeeIds`, not the room/person objects themselves, so later changes to a room or person are reflected immediately in every meeting that references it. [ListMeetingsHandler](impl/src/main/java/com/mootmaker/handler/ListMeetingsHandler.java) resolves those ids back into full `Room`/`Person` objects for the GraphQL response using [BatchLoader](impl/src/main/java/com/mootmaker/dynamo/BatchLoader.java), which deduplicates ids across all meetings first (so a room or person referenced by many meetings is fetched once, via `BatchGetItem`) and fetches the rooms table and people table concurrently.

`startTime`/`endTime` are stored in a canonical, always-19-character format (`MeetingRecord.DATE_TIME_FORMAT`, e.g. `2026-07-01T09:00:00`) rather than the client's raw input text, so they sort correctly as plain strings — this is what makes the range queries below exact string comparisons rather than needing to parse every candidate item. It's also why a meeting can't span midnight (see [Validation](#validation)): every meeting is guaranteed to fall within a single calendar day, so "does this meeting's date match" and "could this meeting possibly overlap that window" are always answerable from `startTime` alone, with no cross-midnight case to account for.

#### Querying meetings by date range and/or person, without scanning

`Query.meetings(filter: MeetingsFilter)` accepts an optional `fromStartTime`/`toEndTime` window and/or `personId` (matching organiser or attendee). `ListMeetingsHandler` picks one of four DynamoDB access patterns depending on which filter fields are present, so it never reads more than the matching meetings:

| Filter | Access pattern |
|---|---|
| none | `Scan` the meetings table — the caller genuinely wants everything, so this isn't a workaround |
| date range only | `Query` the meetings table's `bucket-startTime-index` GSI (hash key is a constant `"ALL"`, range key `startTime` — there's no other partitioning dimension for "every meeting's startTime") |
| `personId` only | `Query` the `mootmaker-meeting-participants` table (see below), hash key `personId`, no range condition |
| both | `Query` the same table with a range condition on its sort key |

**Why `bucket-startTime-index`'s hash key is a constant.** The date-range-only query means "every meeting in this window," not "every meeting for this room" or "for this person" — there's no field to naturally shard by. DynamoDB requires every GSI to have a hash key, and a `Query` can only ever target one hash key value per call, so the only way to answer an arbitrary date range with a *single* `Query` (rather than, say, one call per room) is to give every item the same hash key value. That collapses the GSI to one logical partition, sorted entirely by the sort key `startTime` — functionally a sorted index over the whole table. The trade-off is that a single partition is capped by DynamoDB's per-partition limits (~3,000 RCU / 1,000 WCU / 10 GB) — fine at this project's scale, but bucketing by something coarser (e.g. year-month) would be the next step at real scale, at the cost of a query spanning a bucket boundary needing two `Query` calls merged in Java instead of one. Contrast with `roomId-startTime-index` below, whose hash key is `roomId`: the overlap check genuinely does have a partitioning dimension (it always asks "meetings for *this* room"), so it isn't subject to this limit at all.

Both of the range-bounded queries (date-only, against `bucket-startTime-index`; `personId`+range, against meeting-participants) bound the key condition at the start of `fromStartTime`'s calendar day rather than `fromStartTime` itself — a safe bound (not a heuristic) because a meeting confined to one day can't still be running from a previous day. DynamoDB only allows a single condition on a sort key, so both ends of that bound go through one `BETWEEN` in the key condition rather than two separate comparators — and `BETWEEN` is inclusive on both ends, which the two queries handle differently:

- **`bucket-startTime-index`**'s sort key *is* `startTime`, and DynamoDB rejects a `FilterExpression` that references an index's own key attributes — so the exclusive upper bound can't be expressed as `startTime < toEndTime` in a filter (that's not a hypothetical: it's exactly what an earlier version of this code tried, and DynamoDB rejected it with "Filter Expression can only contain non-primary key attributes"). `endTime` isn't part of this index's key schema, so it's fine in a `FilterExpression` (`endTime > fromStartTime`, for the low-end overlap check), but the upper bound has to be enforced in Java after the query returns, by dropping any item whose `startTime` exactly equals `toEndTime`.
- **meeting-participants**' sort key is the compound string `startTime + "#" + meetingId` (see below), so a bare `toEndTime` string as the `BETWEEN` upper bound is naturally exclusive there already — every real row's key is strictly longer, and therefore greater, than the bare boundary string — so no extra Java-side step is needed for that one.

(Separately: `bucket` is also a DynamoDB reserved word, so `#bucket = :bucket` needs an `ExpressionAttributeNames` alias — a literal `bucket = :bucket` is rejected the same way as the `startTime` filter above.)

This still costs read capacity for the (small, date-bounded) candidates either check discards, but neither ever scans the whole table.

`CreateMeetingHandler`'s overlap check uses `roomId-startTime-index` with `begins_with(startTime, datePrefix)` instead: since two meetings for the same room can only possibly overlap if they share a date, this returns exactly that room's meetings for that one day, which are then checked for a real time overlap in Java. This is now shared logic - [RoomAvailability.hasOverlappingMeeting](impl/src/main/java/com/mootmaker/dynamo/RoomAvailability.java) - since `SuggestRoomHandler` below needs the exact same per-room check.

#### Suggesting a room

`Query.suggestRoom(startTime, endTime, requiredCapacity)` finds every room with enough capacity that's free over the given time range, ranked smallest surplus capacity first (rooms with equal capacity break ties by name, so the order is stable and predictable) - the "suggest a room" button in the webapp's meeting form calls this once, the first time it's pressed for a given time/attendee count, and the webapp caches the whole ranked list client-side so repeat presses just step to the next entry instead of re-querying (see the [webapp README](https://github.com/geoffweatherall/mootmaker-webapp#readme) for that caching). `SuggestRoomHandler` composes two existing pieces rather than needing anything new: it `Scan`s the rooms table (the same full-table `Scan` `ListRoomsHandler` already does - fine at this project's scale, since there's no capacity GSI and none is needed yet), filters and sorts the candidates by capacity ascending then name, then checks each in turn with `RoomAvailability.hasOverlappingMeeting` (the same per-room GSI query `createMeeting`'s own validation uses), keeping the ones that come back free. Unlike `createMeeting`, this doesn't return a structured error list - it's a best-effort suggestion, not authoritative validation, so invalid or missing input (an unparseable time, a non-positive `requiredCapacity`, `startTime` not before `endTime`) just yields an empty list, same as "no room qualifies." The authoritative rules are still enforced by `createMeeting` when the meeting is actually saved.

#### The meeting-participants table

`attendeeIds` is a list on the meeting item, and DynamoDB keys must be scalars, so "which meetings is this person organiser of or an attendee on" can't be answered with a GSI on the meetings table itself. `mootmaker-meeting-participants` (hash key `personId`, range key `sortKey` = `startTime` + `"#"` + `meetingId`) exists purely to answer that: one row per (meeting, participant) pair — the organiser plus every attendee — written by [MeetingParticipant](impl/src/main/java/com/mootmaker/model/MeetingParticipant.java). `CreateMeetingHandler` writes a meeting and all of its participant rows in a single `TransactWriteItems` call, so the two can never drift under normal operation.

This is why `organiserId` is not allowed to also appear in `attendeeIds` (see `OrganiserIsAttendee` below): `MeetingParticipant.allFor` emits one row keyed on `personId` per organiser/attendee, so a duplicated id would produce two `Put`s at the identical primary key within the same `TransactWriteItems` call - DynamoDB rejects that outright (`ValidationException: Transaction request cannot include multiple operations on one item`), which without this check would surface to the caller as an unhandled server error rather than a normal validation result. `InsufficientCapacity`'s count (organiser + attendees, deduplicated by id - see the rules table below) is a separate, independent fix for the same underlying mistake: even though a duplicated id is always rejected via `OrganiserIsAttendee` before a meeting can be saved, deduplicating means that rejection isn't also accompanied by a spurious `InsufficientCapacity` for a room that was actually big enough.

The meetings table remains the source of truth; meeting-participants is a **derived index**. `database-repair`'s `RebuildMeetingParticipantsRepair` (see [Reset and real user accounts](#reset-and-real-user-accounts)) regenerates it from the meetings table — needed once when this table is introduced against an environment that already has meetings (existing meetings have no participant rows until then), and as a safety net against drift.

### API operations

| Operation | Kind | Notes |
|---|---|---|
| `rooms`, `people` | Query | List all items of each type |
| `meetings(filter: MeetingsFilter)` | Query | Lists meetings, optionally narrowed by a `fromStartTime`/`toEndTime` window and/or `personId` (organiser or attendee) — see [Querying meetings by date range and/or person](#querying-meetings-by-date-range-andor-person-without-scanning) |
| `myPerson` | Query | Returns the `Person` linked to the caller's own Cognito account (via `identity.sub`), or `null` if none exists |
| `suggestRoom(startTime, endTime, requiredCapacity)` | Query | Returns every `Room` with sufficient capacity that's free over that time range, ranked smallest surplus first (empty list if none qualify) - see [Suggesting a room](#suggesting-a-room) |
| `createRoom(room)` | Mutation | **Admin only.** Returns `CreateRoomResult` (room or validation errors) |
| `updateRoom(id, room)` | Mutation | **Admin only.** Replaces a room's name/capacity. Returns `UpdateRoomResult` (room or errors, including `RoomNotFound`) |
| `createPerson(person)` | Mutation | **Admin only.** Returns the created `Person`; no validation beyond a required `name` |
| `updatePerson(id, person)` | Mutation | **Self, or admin.** Renames a person and propagates the change to Cognito if they're a linked account (see [Denormalised data: Cognito's `name` attribute](#denormalised-data-cognitos-name-attribute)). Returns `UpdatePersonResult` (person or errors, including `PersonNotFound`) |
| `updateMyPreferences(preferences)` | Mutation | **Self only, no admin override.** Sets the caller's own `dateFormat`/`timeFormat`. Both are required — it replaces the pair rather than patching one. Returns `UpdateMyPreferencesResult` (person or errors, i.e. `NoLinkedPerson`) |
| `createMeeting(meeting)` | Mutation | Returns `CreateMeetingResult` (meeting or validation errors) |

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

Wiping stored data is no longer part of the GraphQL API - it used to be `Mutation.reset`, callable by any signed-in user, which the [mootmaker business functionality doc](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/business-functionality.md) called out as a known gap ("not currently restricted to administrators"). It's now `database-reset`, an IAM-authenticated Lambda deployed as part of this repo (`impl/src/main/java/com/mootmaker/handler/DatabaseResetHandler.java`, `deploy/terraform/admin-tools.tf`) - closing that gap, since invoking it needs an explicit AWS permission grant rather than just being signed in to the product. It briefly lived in a separate repository, `mootmaker-admin-tools`, between 2026-08-29 and 2026-09-02, before moving back in here - see [mootmaker/designs/admin-tools-into-api.md](https://github.com/geoffweatherall/mootmaker/blob/main/designs/admin-tools-into-api.md) for why.

Reset always deletes every room and meeting. What happens to People and the Cognito user pool depends on the target environment:

- **Outside `production`**, reset also wipes the Cognito user pool down to the two Terraform-managed reserved accounts (the demo user and the e2e test user - see [Sign-up creates a linked Person](#sign-up-creates-a-linked-person) above), deleting every other Cognito user regardless of confirmation status, and logging each deleted user's email to CloudWatch. A Person survives only if its `cognitoSub` matches one of those two reserved accounts' *actual current* `sub` (looked up fresh via `ListUsers`, not trusted from the stored attribute) - so a Person left over from a Cognito account that's since been deleted no longer survives just because the attribute wasn't cleared. This is what lets a reset environment become genuinely indistinguishable from a freshly deployed one.
- **In `production`**, the Cognito wipe is refused outright - a Terraform-computed `ALLOW_COGNITO_WIPE` environment variable, not an invoke-time check, so it's structurally impossible to override per-invocation. Person deletion falls back to the original, narrower rule instead: a Person survives if it has *any* non-null `cognitoSub`, since production may have real signed-up visitors whose Cognito account this Lambda never touches and therefore can't verify still exists.

[mootmaker-demo-data](https://github.com/geoffweatherall/mootmaker-demo-data)'s sample data generator, and this project's own acceptance tests (see [Build, test, deploy](#build-test-deploy)), both invoke it as a first step before creating fresh data. Invoke it directly - `aws lambda invoke`, the AWS console, or the AWS SDK, e.g. `aws lambda invoke --function-name <environment>-mootmaker-database-reset --cli-read-timeout 900 out.json` - there is no wrapper script. Its configured Lambda timeout is 900 seconds, the AWS maximum, so a caller's own client-side timeout needs raising to match or a legitimately-long run can be reported as a client failure while the Lambda keeps running (or even succeeds) regardless.

A second Lambda, `database-repair` (`DatabaseRepairHandler`, same Terraform file), runs maintenance repairs directly against Cognito/DynamoDB that the API itself has no way to fix - backfilling a missing Person for a confirmed Cognito user, and reconciling the meeting-participants derived index against the meetings table. Invoked the same way, with an optional `{"dryRun": true}` payload: `aws lambda invoke --function-name <environment>-mootmaker-database-repair --payload '{"dryRun": true}' --cli-binary-format raw-in-base64-out out.json`.

### Displaying the signed-in user's name

The webapp shows the signed-in user's name (see [MyPersonHandler](impl/src/main/java/com/mootmaker/handler/MyPersonHandler.java)/`Query.myPerson`) by reading it live from the `Person` record rather than from the Cognito JWT, so a future "change my name" feature only has to write one place. The `cognitoSub-index` GSI has `ALL` projection (not `KEYS_ONLY`) specifically so this resolver can read the full item straight off the index in one request.

This was chosen over customising the `name` claim in the ID token with a Pre Token Generation Lambda trigger. That approach would also work, but a token's claims are only refreshed on next sign-in/token-refresh (up to the token's ~1 hour lifetime), so a rename would appear stale for up to an hour; reading `myPerson` on demand is always current. It also avoids a subtler correctness gap: `ConfirmSignUp` invokes the `PostConfirmation` trigger synchronously and Cognito won't authenticate an unconfirmed user, so the trigger that creates the Person is guaranteed to have *run* before any sign-in — but not guaranteed to have *succeeded* (its DynamoDB write is deliberately swallowed on error, see above) or to be *visible yet* (DynamoDB GSI reads are only eventually consistent, and the webapp signs the user in immediately after confirming). A Pre Token Generation trigger racing that same window could bake a missing/stale name into a token for up to an hour; `myPerson` just returns `null` for that one moment and the webapp's existing email/JWT-name fallback covers it until the next query.

## Authentication

All access to the API is authenticated by an **Amazon Cognito user pool** (`mootmaker-users`, created by Terraform in [deploy/terraform/cognito.tf](deploy/terraform/cognito.tf)). Users sign in with an **email address and password**; Cognito emails a verification code on sign-up, and account recovery (forgot password) works the same way — a code emailed to the verified address. These are sent through Amazon SES, via the `mail.mootmaker.com` identity verified in `mootmaker-domain` (`email_configuration` in `cognito.tf`), rather than Cognito's own built-in mailer — that default sender has a low, undocumented daily cap shared across every user pool in the account, which real usage plus the acceptance suite's own account creation started hitting. There is no API key.

Every GraphQL request must carry a JWT issued by the user pool in the `Authorization` header (the raw token, no `Bearer` prefix). Enforcement happens in two layers:

1. **AppSync** is configured with `AMAZON_COGNITO_USER_POOLS` authentication: it verifies the token's signature, issuer, and expiry against the user pool **before any resolver runs**, and returns HTTP 401 `UnauthorizedException` otherwise.
2. **Every Lambda handler** re-checks, before running any logic, that the AppSync context it received contains an authenticated `identity` ([Identity.requireAuthenticated](impl/src/main/java/com/mootmaker/handler/Identity.java)) — defence-in-depth in case the API is ever accidentally exposed without the authoriser.

The user pool has two app clients (plus a hosted domain used only for the OAuth2 token endpoint):

| App client | Kind | Used by |
|---|---|---|
| `mootmaker-webapp` | Public (no secret), SRP auth flow | The [mootmaker-webapp](https://github.com/geoffweatherall/mootmaker-webapp) browser SPA: users sign up / sign in and their id token is sent with each GraphQL call |
| `mootmaker-acceptance-tests` | Confidential (client secret), OAuth2 `client_credentials` flow | The [verify/](verify/) acceptance tests and [api/requests.http](api/requests.http) |

The resource server (`mootmaker-api`) defines two OAuth2 scopes: `execute` (general API access) and `admin` (see [User classes and authorization](#user-classes-and-authorization)). `mootmaker-acceptance-tests` requests both — `authenticate.sh`'s `COGNITO_TEST_SCOPE` output is the space-separated pair — so M2M-authenticated tooling (acceptance tests, `sample-data-generator`) can call the admin-gated mutations without needing a real Cognito user.

### Demo user

This is a demo system rather than a real business, so every deployment — including a "production" one — includes a pre-confirmed, publicly-known demo user (`demo@mootmaker.com`, Terraform outputs `demo_user_email` / `demo_user_password`, resources `aws_cognito_user.demo` / `random_password.demo_user` in [cognito.tf](deploy/terraform/cognito.tf)) that anyone can sign in as without creating their own account. Its password is randomly generated at deploy time (like the e2e test user's), but restricted to lowercase letters and digits only, so it's easy to read and type by hand when the webapp shows it on the home page. It is not a secret and its output is not marked `sensitive` — the whole point is that it's shown in the clear. (An earlier version used a fixed password, `demo1234`, which turned out to be on Google's list of known-compromised passwords; it's random now to avoid that.)

The user pool's password policy is set correspondingly loose to match: a minimum of 10 characters with a lowercase letter and a number, and no requirement for uppercase letters or symbols. A real product would want a stricter policy; this one is deliberately weakened so the demo password (and anyone else's) is easy to type.

### Authentication in end-to-end tests

Both projects' end-to-end tests run non-interactively (a dev shell or CI), so neither can prompt a human for credentials. They authenticate differently because they test different things:

- **The API acceptance tests in [verify/](verify/) use machine-to-machine (M2M) auth** — the OAuth2 **client_credentials flow**. [GraphQlClient](verify/src/test/java/com/mootmaker/verify/GraphQlClient.java) POSTs the test client's id and secret (read from the `COGNITO_TEST_CLIENT_ID` / `COGNITO_TEST_CLIENT_SECRET` environment variables, which `authenticate.sh` populates from Terraform outputs) to the user pool's token endpoint (`COGNITO_TOKEN_URL`) and receives a short-lived (1 h) JWT access token scoped to `mootmaker-api/execute`, which AppSync accepts like any user token. One token is fetched per test run and shared by all test classes.
- **The webapp's Playwright tests sign in as a real user** — a Terraform-managed, pre-confirmed user `e2e-tests@example.com` (outputs `e2e_user_email` / `e2e_user_password`). A browser sign-in form inherently needs a user, and exercising the real sign-in UI is part of what those tests verify.

[AuthenticationAcceptanceIT](verify/src/test/java/com/mootmaker/verify/AuthenticationAcceptanceIT.java) proves the API is closed: requests with no token, a malformed token, or a forged JWT all get HTTP 401 and no data, while a client_credentials token succeeds.

Most acceptance tests reset the database to a known state immediately before they act, so they can't be thrown off by data left behind by another test or a previous run. Since `Mutation.reset` no longer exists (see [Reset and real user accounts](#reset-and-real-user-accounts)), they do this by invoking the `database-reset` Lambda directly via the AWS SDK ([DatabaseReset](verify/src/test/java/com/mootmaker/verify/DatabaseReset.java)) rather than through GraphQL - a different auth mechanism (AWS IAM, via whatever credentials are running the tests) from the M2M JWT used for the GraphQL calls above. `database-reset` is deployed by this same repo's `deploy.sh`, so there's no separate deployment step to remember - just deploy this environment normally before running `verify.sh` against it. [DatabaseResetCognitoWipeAcceptanceIT](verify/src/test/java/com/mootmaker/verify/DatabaseResetCognitoWipeAcceptanceIT.java) additionally proves reset's Cognito-wipe survivor logic (a throwaway Cognito user is deleted, the demo account survives) against a real deployed pool.

## User classes and authorization

Every Cognito user has a `custom:class` attribute, `standard` or `admin`, included in the ID token as the `custom:class` claim. `PostConfirmationCreatePersonHandler` (see [Sign-up creates a linked Person](#sign-up-creates-a-linked-person) above) sets it to `standard` for every new sign-up via `AdminUpdateUserAttributes`, right after creating the linked Person — the client is never trusted to set its own class, and the webapp's `mootmaker-webapp` app client is deliberately not granted write access to `custom:class` (see its `write_attributes` in [cognito.tf](deploy/terraform/cognito.tf)), so a signed-in user can't self-promote by calling Cognito's own attribute-update API directly. The Terraform-managed demo user is `admin` and the e2e test user is `standard` (`aws_cognito_user.demo` / `aws_cognito_user.e2e` in [cognito.tf](deploy/terraform/cognito.tf)).

[Identity.requireAdmin](impl/src/main/java/com/mootmaker/handler/Identity.java) is the enforcement point, checked before any logic runs in an admin-only handler (`CreateRoomHandler`, `UpdateRoomHandler`, `CreatePersonHandler`) — same shape as `Identity.requireAuthenticated`, but also accepting a caller whose `scope` claim contains the `mootmaker-api/admin` OAuth scope, so the M2M `mootmaker-acceptance-tests` client (and tools built on it, e.g. `sample-data-generator`) keeps working without a real Cognito user or `custom:class` claim behind it. `UpdatePersonHandler` uses the softer `Identity.isAdmin` instead: a caller may update a person if they're admin *or* if the target person's `cognitoSub` matches their own `identity.sub` (a self-rename).

This is enforced **server-side only** — the webapp's `isAdmin` flag (read from the same JWT claim) only decides what the UI shows; a standard user calling `updateRoom` directly still gets rejected by the Lambda regardless of what the client thinks.

Accounts confirmed before this feature shipped have no `custom:class` attribute at all; every check above only ever tests for `== "admin"`, so a missing claim behaves exactly like `standard` — fail-safe, no backfill needed.

### Denormalised data: Cognito's `name` attribute

Meetings aren't denormalised by room/person name at all — see [Storage](#storage) above — so a rename via `updateRoom`/`updatePerson` is reflected everywhere automatically with no extra propagation. The one real exception is Cognito's own `name` user attribute, a separate copy of a linked person's name set once at sign-up. `UpdatePersonHandler` keeps it in sync: whenever the target person has a `cognitoSub`, it calls `AdminUpdateUserAttributes` to set Cognito's `name` to match, after the DynamoDB write succeeds — for both a self-rename and an admin renaming someone else's linked account. This call is best-effort (logged and swallowed on failure, like the PostConfirmation trigger) so a transient Cognito problem never fails the rename itself; the DynamoDB `Person.name` remains the source of truth read by `myPerson`, so a swallowed sync failure only means the *next* sign-in's JWT `name` claim is briefly stale, not that the rename was lost.

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
| [deploy.sh](deploy.sh) | Builds the Lambda jar (`mvn clean package` in `impl/`), then `terraform init` + `terraform apply -auto-approve` to create/update all AWS resources **for the given environment**. Creates real AWS resources — run deliberately. | `./deploy.sh <environment>` |
| [undeploy.sh](undeploy.sh) | `terraform destroy` — deletes the AppSync API, Lambdas, and DynamoDB tables **including all stored data**, for the given environment. Prompts for confirmation. | `./undeploy.sh <environment>` |
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

One scaling caveat: an unfiltered `meetings` query still **scans** the whole table rather than using an index, so its DynamoDB read cost grows with total stored data, not just with call volume. That's an intentional trade-off - it's asking for literally everything - but not for `createMeeting`'s overlap check or a filtered `meetings` query — both of those go through the `bucket-startTime-index`/`roomId-startTime-index` GSIs or the meeting-participants table instead (see [Storage](#storage)), so their cost is bounded by the size of the matching result, not total stored data. `database-reset` (formerly `Mutation.reset` here - see [Reset and real user accounts](#reset-and-real-user-accounts)) has the same full-table-scan trade-off for the same reason.

`createMeeting` writes cost slightly more than one write request unit now: it writes the meeting plus one meeting-participants row per organiser/attendee in a single `TransactWriteItems` call, which DynamoDB bills at 2× the normal per-item write cost. For a typical small meeting (a couple of attendees) that's still a handful of write request units — a fraction of a cent even at thousands of meetings/month, negligible next to Lambda/AppSync costs.

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
