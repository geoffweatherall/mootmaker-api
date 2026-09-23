package com.mootmaker.dynamo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.MeetingRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomAvailabilityTest {

  private static MeetingRecord meeting(
      final String id, final String roomId, final String start, final String end) {
    return new MeetingRecord(
        id, roomId, "organiser-1", List.of(), List.of(), "Meeting", start, end);
  }

  @Test
  @DisplayName(
      "editing a meeting's own time within its own room - even overlapping its own prior slot "
          + "- does not make the room look unavailable")
  void ignoringAMeetingExcludesItsOwnOverlappingSlot() {
    // The exact motivating case: a meeting was 10:00-11:00 in Room A, being edited to
    // 10:30-11:30, still Room A. The new range genuinely overlaps the meeting's own OLD range -
    // that must not block the edit.
    final List<MeetingRecord> meetingsThatDay =
        List.of(meeting("m-1", "room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    assertTrue(
        RoomAvailability.isFreeIgnoring(
            meetingsThatDay,
            "room-a",
            LocalDateTime.parse("2026-09-24T10:30:00"),
            LocalDateTime.parse("2026-09-24T11:30:00"),
            "m-1"),
        "room-a must be free once m-1's own prior slot is excluded");
  }

  @Test
  @DisplayName(
      "a genuine conflict from a DIFFERENT meeting still blocks, even when excluding one id")
  void aDifferentMeetingsConflictIsNotIgnored() {
    final List<MeetingRecord> meetingsThatDay =
        List.of(
            meeting("m-1", "room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"),
            meeting("m-2", "room-a", "2026-09-24T10:30:00", "2026-09-24T11:30:00"));

    // Excluding m-1 (as if editing it) must not also excuse m-2's own, separate conflict.
    assertFalse(
        RoomAvailability.isFreeIgnoring(
            meetingsThatDay,
            "room-a",
            LocalDateTime.parse("2026-09-24T10:45:00"),
            LocalDateTime.parse("2026-09-24T11:15:00"),
            "m-1"),
        "m-2's own overlapping booking must still block room-a");
  }

  @Test
  @DisplayName("excluding an id that isn't in the day behaves exactly like plain isFree")
  void excludingAnIdNotPresentBehavesLikePlainIsFree() {
    final List<MeetingRecord> meetingsThatDay =
        List.of(meeting("m-1", "room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final LocalDateTime start = LocalDateTime.parse("2026-09-24T10:30:00");
    final LocalDateTime end = LocalDateTime.parse("2026-09-24T11:30:00");

    assertFalse(
        RoomAvailability.isFreeIgnoring(meetingsThatDay, "room-a", start, end, "not-in-this-day"));
    assertFalse(RoomAvailability.isFree(meetingsThatDay, "room-a", start, end));
  }

  @Test
  @DisplayName("excluding null (create's call shape) behaves exactly like plain isFree")
  void excludingNullBehavesLikePlainIsFree() {
    final List<MeetingRecord> meetingsThatDay =
        List.of(meeting("m-1", "room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final LocalDateTime start = LocalDateTime.parse("2026-09-24T09:00:00");
    final LocalDateTime end = LocalDateTime.parse("2026-09-24T09:30:00");

    assertTrue(RoomAvailability.isFreeIgnoring(meetingsThatDay, "room-a", start, end, null));
    assertTrue(RoomAvailability.isFree(meetingsThatDay, "room-a", start, end));
  }
}
