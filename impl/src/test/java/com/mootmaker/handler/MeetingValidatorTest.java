package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused on {@link MeetingValidator#dayStateErrors}'s {@code excludingMeetingId} parameter -
 * {@link MeetingValidator#validateRequest} is already exercised thoroughly through {@code
 * CreateMeetingHandlerTest}/{@code CreateMeetingsHandlerTest}, so this does not duplicate that.
 */
class MeetingValidatorTest {

  private RoomRepository rooms;
  private PersonRepository people;

  @BeforeEach
  void setUp() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put("Rooms", List.of(new Room("room-a", "Room A", 8).toItem()));
    fakeClient.tables.put("People", List.of(new Person("organiser-1", "Ada Lovelace").toItem()));
    rooms = new RoomRepository(fakeClient, "Rooms");
    people = new PersonRepository(fakeClient, "People");
  }

  private static MeetingRecord meeting(final String id, final String start, final String end) {
    return new MeetingRecord(
        id, "room-a", "organiser-1", List.of(), List.of(), "Existing meeting", start, end);
  }

  private MeetingValidator.Validated validated(final String startTime, final String endTime) {
    final LocalDateTime start = LocalDateTime.parse(startTime);
    final LocalDateTime end = LocalDateTime.parse(endTime);
    return new MeetingValidator.Validated(
        List.of(),
        "room-a",
        "organiser-1",
        List.of(),
        List.of(),
        "Edited meeting",
        start,
        end,
        rooms.findById("room-a").orElseThrow(),
        people.findById("organiser-1").orElseThrow(),
        List.of());
  }

  @Test
  @DisplayName(
      "editing a meeting's own time within its own room - overlapping its own prior slot - "
          + "reports no conflict when excluded")
  void selfOverlapIsNotReportedWhenExcluded() {
    final List<MeetingRecord> meetingsThatDay =
        List.of(meeting("m-1", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final List<String> errors =
        MeetingValidator.dayStateErrors(
            meetingsThatDay, validated("2026-09-24T10:30:00", "2026-09-24T11:30:00"), "m-1");

    assertTrue(
        errors.isEmpty(), "excluding m-1 must let its own overlapping edit through: " + errors);
  }

  @Test
  @DisplayName("the same overlap, unexcluded (create's shape), is reported as TimeRangeUnavailable")
  void selfOverlapIsReportedWhenNotExcluded() {
    final List<MeetingRecord> meetingsThatDay =
        List.of(meeting("m-1", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final List<String> errors =
        MeetingValidator.dayStateErrors(
            meetingsThatDay, validated("2026-09-24T10:30:00", "2026-09-24T11:30:00"), null);

    assertTrue(errors.contains(MeetingError.TimeRangeUnavailable.name()));
  }

  @Test
  @DisplayName(
      "a day already at the meeting cap does not reject an edit to one of its own meetings")
  void dayAtCapDoesNotRejectAnEditToAMeetingAlreadyCountedInIt() {
    // Room availability is irrelevant to this case (a different room, so the edit's own new
    // time/room can never conflict with any of these) - only DayIsFull's count is under test.
    final List<MeetingRecord> meetingsThatDay =
        IntStream.range(0, Limits.MAX_MEETINGS_PER_DAY)
            .mapToObj(
                i ->
                    new MeetingRecord(
                        "m-" + i,
                        "room-elsewhere",
                        "organiser-1",
                        List.of(),
                        List.of(),
                        "Existing meeting",
                        "2026-09-24T08:00:00",
                        "2026-09-24T08:15:00"))
            .toList();

    // Editing m-0 (already one of the MAX_MEETINGS_PER_DAY meetings counted above) must not
    // spuriously report DayIsFull just because the day is already at its cap - an edit never
    // changes how many meetings the day holds.
    final List<String> errors =
        MeetingValidator.dayStateErrors(
            meetingsThatDay, validated("2026-09-24T09:00:00", "2026-09-24T09:15:00"), "m-0");

    assertFalse(errors.contains(MeetingError.DayIsFull.name()), "errors: " + errors);
  }

  @Test
  @DisplayName("the same day-at-cap, unexcluded (create's shape), is reported as DayIsFull")
  void dayAtCapIsReportedWhenNotExcluded() {
    final List<MeetingRecord> meetingsThatDay =
        IntStream.range(0, Limits.MAX_MEETINGS_PER_DAY)
            .mapToObj(i -> meeting("m-" + i, "2026-09-24T08:00:00", "2026-09-24T08:15:00"))
            .toList();

    final List<String> errors =
        MeetingValidator.dayStateErrors(
            meetingsThatDay, validated("2026-09-24T20:00:00", "2026-09-24T20:15:00"), null);

    assertTrue(errors.contains(MeetingError.DayIsFull.name()));
  }
}
