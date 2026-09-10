package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code MeetingError} enum. Constant names must match the schema's enum
 * value names exactly, since AppSync serializes/validates enum values as these literal strings.
 */
public enum MeetingError {
    StartMisaligned,
    EndMisaligned,
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
    OrganiserIsAttendee,
    /** The day already holds the maximum number of meetings. The DAY, not the room - a booking can be refused with rooms still free. */
    DayIsFull,
    /** More attendees than a single meeting may carry. */
    TooManyAttendees,
    /** Subject exceeds the byte budget, measured as NFC-normalised UTF-8. */
    SubjectTooLong,
    /** Outside the window meetings may be booked in - too far ahead, or before the retention boundary. */
    OutsideBookableRange,
    /** More meetings in one bulk create than a single DynamoDB transaction can carry with their pointers. */
    TooManyMeetingsInOneCall
}
