package com.mootmaker.dynamo;

import module java.base;

import com.mootmaker.model.MeetingRecord;

/**
 * Whether a room is free over a time range.
 *
 * <p>This used to query the {@code roomId-startTime-index} GSI. With a day held in one item the
 * question is answered in memory against meetings the caller has already read, so the class stops
 * touching DynamoDB at all - which is why it is now a pure function, and why the whole GSI could
 * go.
 *
 * <p>That matters for more than tidiness. {@code CreateMeetingHandler} must re-check availability
 * <i>inside</i> its read-modify-write, so that a booking losing a version conflict re-validates
 * against the day as it now is rather than as it was when the request started. A check that issues
 * its own query cannot be re-run cheaply enough to sit in a retry loop; one that reads a list can.
 */
public final class RoomAvailability {

  private RoomAvailability() {}

  /**
   * Half-open overlap: meetings that merely touch at a boundary do not clash, so 09:00-09:30 and
   * 09:30-10:00 are both bookable. Every meeting is confined to one calendar date, so comparing
   * within a day is exact rather than approximate.
   */
  public static boolean isFree(
      final List<MeetingRecord> meetingsThatDay,
      final String roomId,
      final LocalDateTime startTime,
      final LocalDateTime endTime) {
    return meetingsThatDay.stream()
        .filter(existing -> existing.roomId().equals(roomId))
        .noneMatch(existing -> overlaps(existing, startTime, endTime));
  }

  /** As {@link #isFree}, but ignoring one meeting - so a meeting never clashes with itself. */
  public static boolean isFreeIgnoring(
      final List<MeetingRecord> meetingsThatDay,
      final String roomId,
      final LocalDateTime startTime,
      final LocalDateTime endTime,
      final String ignoredMeetingId) {
    return meetingsThatDay.stream()
        .filter(existing -> !existing.id().equals(ignoredMeetingId))
        .filter(existing -> existing.roomId().equals(roomId))
        .noneMatch(existing -> overlaps(existing, startTime, endTime));
  }

  private static boolean overlaps(
      final MeetingRecord existing, final LocalDateTime startTime, final LocalDateTime endTime) {
    final LocalDateTime existingStart = LocalDateTime.parse(existing.startTime());
    final LocalDateTime existingEnd = LocalDateTime.parse(existing.endTime());
    return startTime.isBefore(existingEnd) && endTime.isAfter(existingStart);
  }
}
