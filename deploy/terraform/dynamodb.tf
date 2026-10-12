resource "aws_dynamodb_table" "rooms" {
  name         = "${local.resource_prefix}-rooms"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

# No index: the caller's Person id travels on the token (custom:personId), and the reverse
# direction is the cognitoSubs list on each item.
resource "aws_dynamodb_table" "people" {
  name         = "${local.resource_prefix}-people"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

# One item per calendar date, holding that day's meetings as a list. See
# ../../../mootmaker/designs/archive/graphql-schema-and-caching.md and ../../docs/data-model.md.
#
# Reading a day by primary key is what buys ConsistentRead, which removes the read-after-write class
# of bug outright (an index cannot be read consistently).
#
# Three kinds of item share the table, discriminated by pk prefix:
#   DAY#2026-09-14     the day's meetings, plus a version attribute for optimistic locking
#   PTR#<meetingId>    id -> date, backs meeting(id:)
#   CONFIG#retention   the stored retention boundary (below)
#
# No secondary indexes: a date range maps straight onto day keys, and "every meeting this person or
# room is in", needed only for deletions, is a scan of at most 217 day items.
resource "aws_dynamodb_table" "meetings" {
  name         = "${local.resource_prefix}-meetings"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
}

# Captured once, at creation, and kept in state - so the seeded boundary below does not drift on
# every plan the way timestamp() would.
resource "time_static" "retention_seed" {}

locals {
  # The Monday on or before (creation date - 30 days). Monday alignment makes "is this week
  # reachable" an exact comparison rather than a straddling judgement.
  #
  # 1970-01-01 was a Thursday, so (days-since-epoch + 3) % 7 is 0 exactly on Mondays.
  retention_seed_day     = floor(time_static.retention_seed.unix / 86400) - 30
  retention_monday_shift = (local.retention_seed_day + 3) % 7
  earliest_retained_date = formatdate("YYYY-MM-DD",
  timeadd("1970-01-01T00:00:00Z", "${(local.retention_seed_day - local.retention_monday_shift) * 24}h"))
}

# The stored retention boundary. Seeded here so it exists the moment the table does: without it every
# booking fails, because the bookable window cannot be computed.
#
# It is seeded close to the real boundary rather than at some far-past date on purpose. The invariant
# the whole cleanup ordering protects is that the advertised boundary is never MORE PERMISSIVE than
# reality - a fresh environment advertising years of history it does not have breaks exactly that,
# and clients would offer navigation into empty weeks.
resource "aws_dynamodb_table_item" "retention_boundary" {
  table_name = aws_dynamodb_table.meetings.name
  hash_key   = aws_dynamodb_table.meetings.hash_key

  item = jsonencode({
    pk                   = { S = "CONFIG#retention" }
    earliestRetainedDate = { S = local.earliest_retained_date }
  })

  # REQUIRED, not tidiness. The weekly cleanup job advances this value; without ignore_changes the
  # next apply would reset it to the seed, walking the boundary BACKWARDS and resurrecting the very
  # "advertised more permissive than reality" failure the advance-then-delete ordering exists to
  # prevent - silently, and only on whichever apply happened to follow a cleanup run.
  lifecycle {
    ignore_changes = [item]
  }
}
