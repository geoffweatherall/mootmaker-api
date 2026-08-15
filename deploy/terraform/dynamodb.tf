resource "aws_dynamodb_table" "rooms" {
  name         = "${local.resource_prefix}-rooms"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

resource "aws_dynamodb_table" "people" {
  name         = "${local.resource_prefix}-people"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  # Populated only for people created by the PostConfirmation sign-up trigger (guests added
  # directly have no Cognito account and so no cognitoSub). The index lets that trigger check
  # "does a Person already exist for this sub" so a retried invocation doesn't create a
  # duplicate, lets Query.myPerson resolve the signed-in caller's own Person (see
  # MyPersonHandler), and will also let a future account-deletion flow find the Person linked
  # to a given Cognito user. ALL projection (rather than KEYS_ONLY) so Query.myPerson can read
  # the full item straight off the index without a second GetItem - the table is tiny, so the
  # extra copy of each item's attributes costs effectively nothing.
  attribute {
    name = "cognitoSub"
    type = "S"
  }

  global_secondary_index {
    name            = "cognitoSub-index"
    hash_key        = "cognitoSub"
    projection_type = "ALL"
  }
}

resource "aws_dynamodb_table" "meetings" {
  name         = "${local.resource_prefix}-meetings"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }

  # Every meeting now spans a single calendar day (see the SpansMultipleDays validation rule in
  # CreateMeetingHandler), which is what makes both indexes below exact rather than approximate:
  # a meeting can only overlap a window or a room/day if its own startTime falls in the matching
  # range, there's no cross-midnight case to account for.

  # bucket is a constant ("ALL") written on every item purely to give this GSI a hash key -
  # Query.meetings' date-range filter (no personId) wants "every meeting whose startTime falls in
  # [from, to)" with no other partitioning dimension, and DynamoDB requires a hash key on every
  # GSI. A single constant partition is fine at this project's scale (see the README's cost
  # model); it would need bucketing by month or similar to spread load at real scale.
  attribute {
    name = "bucket"
    type = "S"
  }

  attribute {
    name = "startTime"
    type = "S"
  }

  global_secondary_index {
    name            = "bucket-startTime-index"
    hash_key        = "bucket"
    range_key       = "startTime"
    projection_type = "ALL"
  }

  # Lets CreateMeetingHandler's overlap check (roomHasOverlappingMeeting) query "this room's
  # meetings on this day" via begins_with(startTime, datePrefix) instead of scanning every
  # meeting ever created.
  attribute {
    name = "roomId"
    type = "S"
  }

  global_secondary_index {
    name            = "roomId-startTime-index"
    hash_key        = "roomId"
    range_key       = "startTime"
    projection_type = "ALL"
  }
}

# Denormalised join index resolving "which meetings is this person organiser of or an attendee
# on" - attendeeIds is a list on the meeting item, and DynamoDB keys must be scalars, so that
# can't be answered with a GSI on the meetings table itself. One item is written here per
# (meeting, participant) pair - the organiser plus every attendee - alongside the meeting item
# itself, in a single TransactWriteItems call in CreateMeetingHandler, so the two can never drift
# under normal operation. The meetings table remains the source of truth; this table is a derived
# index that mootmaker-tools/database-repair's RebuildMeetingParticipantsRepair can
# regenerate from it (needed once when this table is first introduced against an environment that
# already has meetings, and as a safety net against any drift).
resource "aws_dynamodb_table" "meeting_participants" {
  name         = "${local.resource_prefix}-meeting-participants"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "personId"
  range_key    = "sortKey"

  attribute {
    name = "personId"
    type = "S"
  }

  # startTime + "#" + meetingId. LocalDateTime's ISO-8601 string form (with the canonical
  # fixed-width formatting CreateMeetingHandler stores rather than trusting client-supplied text -
  # see its DATE_TIME_FORMAT) is lexicographically sortable, so a plain string range query on this
  # sort key correctly answers "this person's meetings starting in [from, to)". Appending the
  # meetingId keeps the key unique even if two of a person's meetings start at the same instant.
  attribute {
    name = "sortKey"
    type = "S"
  }
}
