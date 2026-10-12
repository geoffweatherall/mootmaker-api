# API reference

Every operation, who may call it, and every way it can refuse. The schema,
[`api/mootmaker.graphql`](../api/mootmaker.graphql), is the contract and documents each type and field;
this page adds who may call what and why calls are refused. Conventions shared by every client are in the
hub's [API contract overview](https://github.com/geoffweatherall/mootmaker/blob/main/docs/overview/api-contract.md).

## Operations

| Operation | Kind | Who | What |
|---|---|---|---|
| `workspace(dates)` | Query | Signed in | Everything a screen needs in one call: `me`, `rooms`, `people`, the requested `days`, and the booking window (`boundaries`). Unselected fields are not fetched. At most 42 dates; omit `dates` for none |
| `meeting(id)` | Query | Signed in | One meeting by id, through its pointer. `null` for an id that never existed or has been deleted |
| `suggestRoom(startTime, endTime, requiredCapacity, excludingMeetingId)` | Query | Signed in | Every room with enough capacity that is free over the range, smallest surplus first, ties by name. Invalid input returns an empty list, not errors. `excludingMeetingId` ignores that meeting's own slot, for edits |
| `createRoom`, `updateRoom`, `deleteRoom` | Mutation | Admin | Manage rooms. `deleteRoom` is refused while the room has a meeting from now on |
| `createPerson(name)` | Mutation | Admin | Add a person with no account |
| `renamePerson(id, name)` | Mutation | Admin | Rename anyone; also renames their Cognito accounts, best effort |
| `setPersonAdmin(id, isAdmin)` | Mutation | Admin | Grant or remove admin: writes `Person.isAdmin`, then `custom:class` on each linked account. `cognitoSyncFailed: true` means the second step failed and should be retried |
| `deletePerson(id)` | Mutation | Admin | Cancels meetings they organise from now on, removes them from meetings they attend from now on, deletes their avatar, Person and linked accounts |
| `updateMyName(name)` | Mutation | Self | Rename yourself |
| `updateMyPreferences(preferences)` | Mutation | Self | Set all three display preferences at once |
| `deleteMyAccount` | Mutation | Self | As `deletePerson`, for yourself. Refused for reserved accounts |
| `requestAvatarUpload(personId, contentType, contentLength)` | Mutation | Admin, or that person | Step one: a presigned S3 `PUT` URL, valid 15 minutes. JPEG or PNG, up to 2 MiB |
| `confirmAvatarUpload(personId, uploadId)` | Mutation | Admin, or that person | Step two: validate, normalise to a 256 × 256 JPEG, set the avatar. Safe to retry |
| `removeAvatar(personId)` | Mutation | Admin, or that person | Clear the avatar and delete the image |
| `createMeeting(meeting)` | Mutation | Signed in | Book a meeting. Returns the meeting and its whole day |
| `createMeetings(date, meetings)` | Mutation | Signed in | Book up to 99 meetings on one date in one write and one broadcast |
| `updateMeeting(id, meeting)` | Mutation | The organiser, or admin | Replace every field, including the date. Send `expectedVersion` to be refused with `MeetingChanged` if someone else changed it first. Continuing attendees keep their responses |
| `cancelMeeting(id)` | Mutation | The organiser, or admin | Delete the meeting. There is no cancelled state |
| `respondToMeeting(meetingId, status)` | Mutation | An attendee, for themselves | Going, Maybe, NotGoing or NoResponse |
| `publishDaysInvalidated(dates)` | Mutation | IAM only | Called by the resolver Lambda after a write; refused for every client |
| `daysInvalidated` | Subscription | Signed in | The dates that changed. Never the data |

"Signed in" includes the M2M clients (tests, demo-data); "Admin" includes M2M clients holding the
`admin` scope.

## How refusals arrive

- **Not allowed at all** (not signed in, not an admin, not the organiser, a self-only operation aimed at
  someone else): a top-level GraphQL error. AppSync returns 401 before any code runs if the token is
  missing or invalid.
- **Allowed, but the input breaks a rule**: a normal result with the entity `null` and an `errors` list
  naming every rule broken, at once. Nothing is written.
- **An existence check comes first**: `updateMeeting` and `cancelMeeting` report `MeetingNotFound` for a
  bad id before checking who is asking, so they never reveal a 403 for a meeting that does not exist.

## Validation rules

### Meetings: `MeetingError`

Checked by `createMeeting`, `createMeetings` and `updateMeeting` (`MeetingValidator`).

| Code | Refused when |
|---|---|
| `StartMisaligned`, `EndMisaligned` | A time does not parse as `yyyy-MM-ddTHH:mm:ss`, or is not on a quarter hour |
| `SpansMultipleDays` | Start and end are on different dates |
| `EndBeforeStart` | End is not after start |
| `OutsideBookableRange` | The date is before the retention boundary or more than 180 days ahead |
| `SubjectRequired` / `SubjectTooLong` | Blank, or over 280 bytes of NFC-normalised UTF-8 |
| `RoomRequired` / `RoomNotFound` | No room, or no such room |
| `OrganiserRequired` / `OrganiserNotFound` | No organiser, or no such person |
| `AttendeeNotFound` | An attendee id names nobody (one error per missing attendee) |
| `OrganiserIsAttendee` | The organiser is also listed as an attendee |
| `TooManyAttendees` | More than 20 attendees |
| `InsufficientCapacity` | The distinct people (organiser plus attendees) exceed the room's capacity |
| `TimeRangeUnavailable` | Another meeting in that room overlaps `[start, end)`. Touching end to start is allowed. An edit ignores the meeting's own current slot |
| `DayIsFull` | The day already has 320 meetings, or the item would exceed DynamoDB's size limit |
| `TooManyMeetingsInOneCall` | `createMeetings` with more than 99 |
| `MeetingNotFound` | `updateMeeting` or `cancelMeeting` on an id that does not resolve |
| `MeetingChanged` | `updateMeeting` with an `expectedVersion` that no longer matches |

A room's capacity can later be reduced below a booked meeting's size; nothing re-validates past
bookings.

### Responses: `RespondToMeetingError`

| Code | Refused when |
|---|---|
| `NoLinkedPerson` | The caller has no Person |
| `MeetingNotFound` | No such meeting |
| `NotAnAttendee` | The caller is not an attendee (the organiser has nothing to set) |

### Rooms: `RoomError`

| Code | Refused when |
|---|---|
| `NameRequired` | Blank name |
| `CapacityTooLow` | Capacity below 2 |
| `RoomNotFound` | `updateRoom` or `deleteRoom` on an unknown id |
| `RoomHasUpcomingMeetings` | `deleteRoom` while a meeting from now on uses it |

### People: `PersonError`

| Code | Refused when |
|---|---|
| `NameRequired` | Blank name |
| `NameAlreadyExists` | `createPerson` with a name another person has, ignoring case and whitespace. Sign-up applies the same rule through the pre-sign-up trigger |
| `PersonNotFound` | Unknown id |
| `NoLinkedPerson` | `updateMyName` by an account with no Person |
| `NoLinkedAccount` | `setPersonAdmin` on a guest: there is no account to grant anything to |
| `CannotRevokeOwnAdminAccess` | `setPersonAdmin` removing the caller's own admin access |
| `CannotDeleteSelf` | `deletePerson` on the caller's own Person (use `deleteMyAccount`) |
| `ReservedAccount` | `deletePerson` on a reserved account |

### Preferences: `PreferencesError`

| Code | Refused when |
|---|---|
| `NoLinkedPerson` | The caller has no Person to store preferences against |

### Avatars: `AvatarError`

These report the first problem only: an image that fails to decode has no dimensions to check.

| Code | Refused when |
|---|---|
| `PersonNotFound` | Unknown `personId` |
| `UnsupportedContentType` | Not `image/jpeg` or `image/png` |
| `UploadTooLarge` / `InvalidContentLength` | Over 2 MiB, or not positive |
| `UploadNotFound` | Nothing staged under that `uploadId`: never uploaded, incomplete, or more than a day ago |
| `NotAnImage` | The bytes are not a JPEG or PNG, whatever they were sent as |
| `ImageTooSmall` / `ImageTooLarge` | Under 64 or over 4096 pixels on a side |

## Adding an error code

Add it to the schema's enum and the Java enum with exactly the same name (AppSync serialises the
constant name). Both apps then need wording for it: the web app's message map will not compile until it
has one; the Android app shows a generic message for codes it does not know, so add one there too.
