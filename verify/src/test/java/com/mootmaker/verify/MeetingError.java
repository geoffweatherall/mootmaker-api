package com.mootmaker.verify;

/**
 * Mirrors the GraphQL {@code MeetingError} enum. Constant names must match the schema's enum value
 * names exactly, since that's the literal string AppSync returns over the wire.
 */
enum MeetingError {
  StartMisaligned,
  EndMisaligned,
  InsufficientCapacity,
  TimeRangeUnavailable,
  RoomRequired,
  RoomNotFound,
  OrganiserRequired,
  OrganiserNotFound,
  AttendeeNotFound,
  SubjectRequired,
  OrganiserIsAttendee
}
