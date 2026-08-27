package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code MeetingError} enum. Constant names must match the schema's enum
 * value names exactly, since AppSync serializes/validates enum values as these literal strings.
 */
public enum MeetingError {
    StartMissaligned,
    EndMissaligned,
    SpansMultipleDays,
    EndBeforeStart,
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
