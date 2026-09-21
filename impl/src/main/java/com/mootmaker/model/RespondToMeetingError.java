package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code RespondToMeetingError} enum. Constant names must match the schema's
 * enum value names exactly, since AppSync serializes/validates enum values as these literal
 * strings.
 */
public enum RespondToMeetingError {
  /** The caller has no linked Person, so there is no attendee identity to respond as. */
  NoLinkedPerson,
  /** {@code meetingId} did not resolve to any meeting - never existed, or aged out of retention. */
  MeetingNotFound,
  /**
   * The caller's own Person is not among this meeting's attendees - either they are its organiser
   * (who has no status to set, see designs/attendee-response-status.md) or unrelated to it
   * entirely. Re-checked on every write attempt, same as {@code MeetingError}'s day-state rules,
   * since a concurrent edit could remove the caller as an attendee between read and write.
   */
  NotAnAttendee
}
