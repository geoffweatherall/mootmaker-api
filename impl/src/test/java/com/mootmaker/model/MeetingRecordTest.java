package com.mootmaker.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import module java.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * {@code startTime}/{@code endTime} are stored as epoch-minutes ({@code N}), not the 19-byte ISO
 * string every other layer of this system sees - see designs/dynamodb-storage-compaction.md. These
 * tests pin that encoding directly, the way {@link PersonTest} pins {@link Person}'s own storage
 * quirks, rather than only reaching it indirectly through {@code DayRepositoryTest}.
 */
class MeetingRecordTest {

  private static MeetingRecord meeting(final String startTime, final String endTime) {
    return new MeetingRecord(
        "m-1",
        "room-1",
        "person-1",
        List.of("person-2"),
        List.of(AttendeeStatus.Maybe),
        "Standup",
        startTime,
        endTime);
  }

  @Test
  void roundTripsStartAndEndTimeThroughTheItem() {
    final MeetingRecord original = meeting("2026-09-14T09:00:00", "2026-09-14T09:30:00");

    final MeetingRecord restored = MeetingRecord.fromAttributeValue(original.toAttributeValue());

    assertEquals(original, restored);
  }

  @Test
  @DisplayName("startTime/endTime are stored as N, not S - that is the whole point of the change")
  void storesStartAndEndTimeAsNumbersNotStrings() {
    final Map<String, AttributeValue> fields =
        meeting("2026-09-14T09:00:00", "2026-09-14T09:30:00").toAttributeValue().m();

    assertNotNull(fields.get("startTime").n(), "startTime must be a DynamoDB N, not S");
    assertNull(fields.get("startTime").s());
    assertNotNull(fields.get("endTime").n(), "endTime must be a DynamoDB N, not S");
    assertNull(fields.get("endTime").s());
  }

  @Test
  @DisplayName("midnight round-trips even though it has no non-zero component to anchor on")
  void roundTripsMidnightExactly() {
    final MeetingRecord original = meeting("2026-09-14T00:00:00", "2026-09-14T00:15:00");

    final MeetingRecord restored = MeetingRecord.fromAttributeValue(original.toAttributeValue());

    assertEquals("2026-09-14T00:00:00", restored.startTime());
    assertEquals("2026-09-14T00:15:00", restored.endTime());
  }

  @Test
  void dateIsUnaffectedByTheStorageEncoding() {
    final MeetingRecord original = meeting("2026-09-14T09:00:00", "2026-09-14T09:30:00");

    assertEquals("2026-09-14", original.date());
    assertEquals(
        "2026-09-14", MeetingRecord.fromAttributeValue(original.toAttributeValue()).date());
  }

  @Test
  @DisplayName("attendeeStatuses is stored as single-character codes and round-trips every value")
  void attendeeStatusesRoundTripsEveryCode() {
    final MeetingRecord original =
        new MeetingRecord(
            "m-1",
            "room-1",
            "person-1",
            List.of("p2", "p3", "p4", "p5"),
            List.of(
                AttendeeStatus.Going,
                AttendeeStatus.NotGoing,
                AttendeeStatus.Maybe,
                AttendeeStatus.NoResponse),
            "Standup",
            "2026-09-14T09:00:00",
            "2026-09-14T09:30:00");

    final Map<String, AttributeValue> fields = original.toAttributeValue().m();
    assertEquals(
        List.of("G", "N", "M", "U"),
        fields.get("attendeeStatuses").l().stream().map(AttributeValue::s).toList());

    assertEquals(original, MeetingRecord.fromAttributeValue(original.toAttributeValue()));
  }

  @Test
  @DisplayName("attendeeStatuses must be the same length as attendeeIds")
  void rejectsAMismatchedAttendeeStatusesLength() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MeetingRecord(
                "m-1",
                "room-1",
                "person-1",
                List.of("p2", "p3"),
                List.of(AttendeeStatus.Going),
                "Standup",
                "2026-09-14T09:00:00",
                "2026-09-14T09:30:00"));
  }
}
