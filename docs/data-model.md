# Data model

Every table, item and attribute this API stores, and what lives in Cognito. The big picture (why
meetings are stored a day at a time, the booking window, limits) is in the hub's
[data overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/data.md); this page
is the reference. Keep it in step with `com.mootmaker.model` and `com.mootmaker.dynamo`: a change to a
stored shape changes this page in the same pull request.

## DynamoDB

Three tables per environment, all on-demand billing, no secondary indexes, defined in
`deploy/terraform/dynamodb.tf`.

### `<env>-mootmaker-rooms`

Key: `id` (S).

| Attribute | Type | Notes |
|---|---|---|
| `id` | S | 8-character base62 token from `IdAllocator`. Created with `attribute_not_exists(id)`, retried with a new id on the (negligible) chance of a collision |
| `name` | S | At most 100 bytes of UTF-8 |
| `capacity` | N | At least 2 |
| `color` | S, optional | One of the eight `RoomColor` values. Absent means the client chooses a colour by position |

`updateRoom` replaces the whole item (see [mootmaker-api#112](https://github.com/geoffweatherall/mootmaker-api/issues/112)).
`deleteRoom` is refused while any meeting from now on uses the room; it never cascades.

### `<env>-mootmaker-people`

Key: `id` (S).

| Attribute | Type | Notes |
|---|---|---|
| `id` | S | Same 8-character token. A person created at sign-up gets a fresh id; `PersonRepository.create` writes a given id and fails rather than retrying, so a Cognito claim can never point at a different id than was stored |
| `name` | S | The source of truth for a person's name. Unique, ignoring case and whitespace |
| `cognitoSubs` | L of S | Every linked Cognito account's `sub`. Empty for a guest. Never exposed over GraphQL |
| `cognitoEmails` | L of S, optional | The email of each linked account, in the same order. Exposed as `Person.linkedEmails` |
| `isAdmin` | BOOL, optional | Display copy of the linked account's `custom:class == admin`, for the admin screens. Never used for authorization. Absent means false |
| `dateFormat`, `timeFormat`, `weekStart` | S, optional | Display preferences. Absent means the defaults (`Iso`, `TwentyFourHour`, `Monday`), applied in `Person.fromItem` |
| `avatarUrl` | S, optional | Not a URL: the stored key `v1/<personId>/<sha256>`. `AvatarUrls` adds the host and `.jpg` on the way out |

Every update after creation is an attribute-level `UpdateItem` with `attribute_exists(id)`, so a write
can neither touch fields it does not name nor recreate a deleted person.

### `<env>-mootmaker-meetings`

Key: `pk` (S). Three kinds of item:

| `pk` | Item |
|---|---|
| `DAY#2026-10-14` | Every meeting on that date, and a version |
| `PTR#<meetingId>` | Which date a meeting is on |
| `CONFIG#retention` | The retention boundary |

**Day item**

| Attribute | Type | Notes |
|---|---|---|
| `pk` | S | `DAY#` + ISO date |
| `version` | N | Optimistic lock. Every write is conditional on the version read. A day never written has no item, so the first write is conditional on `attribute_not_exists(pk)` rather than on version 0 |
| `meetings` | L of M | One map per meeting, below |

Each meeting in `meetings` (`MeetingRecord`):

| Attribute | Type | Notes |
|---|---|---|
| `id` | S | 8-character token |
| `roomId`, `organiserId` | S | Ids only. Names are resolved at read time, so a rename needs no propagation |
| `attendeeIds` | L of S | Never contains the organiser |
| `attendeeStatuses` | L of S | Parallel to `attendeeIds`, same length and order: `G`, `M`, `N` or `U` (Going, Maybe, Not going, No response). Always written together with `attendeeIds` |
| `subject` | S | At most 280 bytes of NFC-normalised UTF-8 |
| `startTime`, `endTime` | N | Minutes since 1970-01-01T00:00, computed as if the zone-less local time were UTC. Converted back to `yyyy-MM-ddTHH:mm:ss` by `MeetingRecord`; nothing else sees the number |

A day item must stay under DynamoDB's 400 KB. `Limits` bounds the inputs so that it does, and
`ItemSizer` measures the real item before every write as a backstop (`DayIsFull` if it would not fit).

**Pointer item**: `pk` = `PTR#<meetingId>`, `date` = the ISO date. Written in the same
`TransactWriteItems` as its day, conditional on not existing (which is also what makes a new meeting id
collision-safe), and deleted with it. Moving a meeting to another day updates the pointer with a
condition that it still names the old date.

**Retention config**: `pk` = `CONFIG#retention`, `earliestRetainedDate` = a Monday. Seeded by Terraform,
advanced by `history-cleanup`, never moved backwards except by `database-reset`.

## Cognito

User pool `<env>-mootmaker-users`, in `deploy/terraform/cognito.tf`. Users sign in by email; the username
is a UUID equal to `sub`.

| Attribute | Set by | Notes |
|---|---|---|
| `email`, `email_verified` | Sign-up, or Terraform for the demo user | |
| `name` | Sign-up; then kept in step with `Person.name` by `updateMyName` and `renamePerson` (best effort) | A copy; the Person holds the real name |
| `custom:personId` | The post-confirmation trigger, or `database-repair` | 8 characters. Names the account's Person; carried in the ID token |
| `custom:class` | The post-confirmation trigger (`standard`); `setPersonAdmin`; Terraform and reset for reserved accounts | `standard` or `admin`. The authorization source. The app clients cannot write it |

App clients, triggers and reserved accounts are described in the hub's
[identity and access overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/identity-and-access.md).

**Links between the two.** The account names its Person (`custom:personId`); the Person lists its
accounts (`cognitoSubs`). Requests follow the first direction, by key. The second is used by the
post-confirmation trigger (does a Person already exist?), `database-reset` (who survives?) and
`database-repair`.

## S3: `<env>-mootmaker-avatars-<account>`

| Prefix | Holds | Lifetime |
|---|---|---|
| `uploads/<personId>/<uploadId>` | An upload as the client sent it, waiting for `confirmAvatarUpload`. Never served | A lifecycle rule deletes it after one day |
| `avatars/v1/<personId>/<sha256>.jpg` | The 256 × 256 JPEG the API produced. `<sha256>` is of the uploaded bytes | Until replaced, removed, or the person is deleted |

At most one object per person under `avatars/`: confirming a new avatar writes it, updates the Person,
then deletes the person's other objects. Deleting a person deletes their objects before the Person, so
a failure leaves a retryable state rather than an orphaned public image. Served by the avatars
CloudFront distribution with origin path `/avatars`, so `uploads/` is unreachable through it.

## SSM Parameter Store

What this API publishes for other components is listed in [the README](../README.md#published-configuration).
