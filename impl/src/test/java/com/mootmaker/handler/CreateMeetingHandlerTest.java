package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class CreateMeetingHandlerTest {

  private FakeDynamoDbClient fakeClient;
  private CreateMeetingHandler handler;

  @BeforeEach
  void setUp() {
    fakeClient = new FakeDynamoDbClient();
    fakeClient
        .tables
        .computeIfAbsent("Meetings", _ -> new ArrayList<>())
        .add(DayFixtures.retentionConfig(DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE));
    handler = new CreateMeetingHandler(fakeClient, "Meetings", "Rooms", "People");

    fakeClient.tables.put("Rooms", List.of(new Room("room-1", "Conference A", 2).toItem()));
    fakeClient.tables.put(
        "People",
        List.of(
            new Person("organiser-1", "Ada Lovelace").toItem(),
            new Person("attendee-1", "Alan Turing").toItem(),
            new Person("attendee-2", "Grace Hopper").toItem()));
  }

  private static Map<String, Object> meetingArguments(
      final String roomId,
      final String organiserId,
      final List<String> attendeeIds,
      final String startTime,
      final String endTime) {
    return meetingArguments(roomId, organiserId, attendeeIds, "Team sync", startTime, endTime);
  }

  private static Map<String, Object> meetingArguments(
      final String roomId,
      final String organiserId,
      final List<String> attendeeIds,
      final String subject,
      final String startTime,
      final String endTime) {
    final Map<String, Object> meeting = new HashMap<>();
    meeting.put("roomId", roomId);
    meeting.put("organiserId", organiserId);
    meeting.put("attendeeIds", attendeeIds);
    meeting.put("subject", subject);
    meeting.put("startTime", startTime);
    meeting.put("endTime", endTime);
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("meeting", meeting);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    event.put("identity", Map.of("sub", "test-user"));
    return event;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");
    event.remove("identity");

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty());
  }

  @Test
  void createsMeetingWhenAllRulesPass() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());

    @SuppressWarnings("unchecked")
    final Map<String, Object> meeting = (Map<String, Object>) result.get("meeting");
    assertNotNull(meeting);
    assertNotNull(meeting.get("id"));
    assertEquals(1, DayFixtures.meetingsIn(fakeClient, "Meetings").size());
  }

  @Test
  void writesAnIdToDatePointerAlongsideTheDay() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);
    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());

    // Replaces the old "one participants row per person" assertion. The join table is gone; what
    // has to exist now is the pointer that lets meeting(id:) resolve without scanning, and it must
    // point at the day that actually holds the meeting.
    @SuppressWarnings("unchecked")
    final Map<String, Object> meeting = (Map<String, Object>) result.get("meeting");
    final String meetingId = (String) meeting.get("id");

    final Map<String, AttributeValue> pointer =
        fakeClient.tables.get("Meetings").stream()
            .filter(item -> ("PTR#" + meetingId).equals(item.get("pk").s()))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no id -> date pointer was written for " + meetingId));
    assertEquals("2026-07-01", pointer.get("date").s());
  }

  @Test
  void rejectsWhenStartAndEndTimeAreOnDifferentCalendarDates() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T23:45:00",
            "2026-07-02T00:15:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.SpansMultipleDays.name()));
    assertNull(result.get("meeting"));
    assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty());
  }

  @Test
  void rejectsWhenEndTimeIsBeforeStartTime() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:00:00",
            "2026-07-01T10:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.EndBeforeStart.name()));
    assertNull(result.get("meeting"));
    assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty());
  }

  @Test
  void rejectsWhenEndTimeEqualsStartTime() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T10:00:00",
            "2026-07-01T10:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.EndBeforeStart.name()));
    assertNull(result.get("meeting"));
    assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty());
  }

  @Test
  void rejectsStartOrEndTimeNotOnFifteenMinuteBoundary() {
    // :35 is on a 5-minute boundary but not a 15-minute one - specifically proves the rule is
    // 15 minutes, not just "not on a 5-minute boundary" (which :32 alone wouldn't distinguish).
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:35:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.StartMisaligned.name()));
    assertNull(result.get("meeting"));
    assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty());
  }

  @Test
  void rejectsWhenRoomIdIsMissing() {
    final Map<String, Object> event =
        meetingArguments(
            null,
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.RoomRequired.name()));
    assertFalse(errors.contains(MeetingError.RoomNotFound.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenRoomIdIsBlank() {
    final Map<String, Object> event =
        meetingArguments(
            "   ",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.RoomRequired.name()));
    assertFalse(errors.contains(MeetingError.RoomNotFound.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenSubjectIsMissing() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            null,
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.SubjectRequired.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenSubjectIsBlank() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "   ",
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.SubjectRequired.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenOrganiserIdIsMissing() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1", null, List.of("attendee-1"), "2026-07-01T14:30:00", "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.OrganiserRequired.name()));
    assertFalse(errors.contains(MeetingError.OrganiserNotFound.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenOrganiserIdIsBlank() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1", "", List.of("attendee-1"), "2026-07-01T14:30:00", "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.OrganiserRequired.name()));
    assertFalse(errors.contains(MeetingError.OrganiserNotFound.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWithBothRequiredErrorsWhenRoomAndOrganiserAreBothMissing() {
    final Map<String, Object> event =
        meetingArguments(
            null, null, List.of("attendee-1"), "2026-07-01T14:30:00", "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.RoomRequired.name()));
    assertTrue(errors.contains(MeetingError.OrganiserRequired.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenOrganiserIsAlsoAnAttendee() {
    // room-1 has capacity 2, and organiser-1 + attendee-1 = 2 distinct people, i.e. exactly enough
    // capacity - the duplicated organiserId must not also trigger a spurious InsufficientCapacity.
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("organiser-1", "attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.OrganiserIsAttendee.name()));
    assertFalse(errors.contains(MeetingError.InsufficientCapacity.name()));
    assertNull(result.get("meeting"));
    assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty());
  }

  @Test
  void doesNotFlagOrganiserIsAttendeeWhenOrganiserAndAttendeesAreDisjoint() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertFalse(errors.contains(MeetingError.OrganiserIsAttendee.name()));
  }

  @Test
  void rejectsWhenRoomCapacityInsufficient() {
    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1", "attendee-2"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.InsufficientCapacity.name()));
    assertNull(result.get("meeting"));
  }

  @Test
  @DisplayName(
      "reports request errors AND day-state errors together, rather than stopping at the first"
          + " kind")
  void reportsRequestAndDayStateErrorsTogether() {
    final MeetingRecord existing =
        new MeetingRecord(
            "existing-meeting",
            "room-1",
            "organiser-1",
            List.of(),
            "Existing meeting",
            "2026-07-01T14:00:00",
            "2026-07-01T15:00:00");
    fakeClient.tables.put("Meetings", new ArrayList<>(DayFixtures.dayItems(existing)));

    // A missing organiser is a request-level rule; an unavailable room is a day-state one. They are
    // checked in different places, and an earlier version of this handler threw on the first set -
    // so the caller learned about the organiser, fixed it, and only then discovered the room was
    // gone. Two round trips to be told two things that were both true the first time.
    final Map<String, Object> event =
        meetingArguments(
            "room-1", "", List.of("attendee-1"), "2026-07-01T14:30:00", "2026-07-01T15:30:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.OrganiserRequired.name()), errors.toString());
    assertTrue(errors.contains(MeetingError.TimeRangeUnavailable.name()), errors.toString());
    assertNull(result.get("meeting"));
  }

  @Test
  void rejectsWhenRoomAlreadyBookedForOverlappingTime() {
    final MeetingRecord existing =
        new MeetingRecord(
            "existing-meeting",
            "room-1",
            "organiser-1",
            List.of(),
            "Existing meeting",
            "2026-07-01T14:00:00",
            "2026-07-01T15:00:00");
    fakeClient.tables.put("Meetings", DayFixtures.dayItems(existing));

    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:30:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(MeetingError.TimeRangeUnavailable.name()));
    assertNull(result.get("meeting"));
    assertEquals(1, DayFixtures.meetingsIn(fakeClient, "Meetings").size());
  }

  @Test
  void allowsBackToBackMeetingsThatDoNotOverlap() {
    final MeetingRecord existing =
        new MeetingRecord(
            "existing-meeting",
            "room-1",
            "organiser-1",
            List.of(),
            "Existing meeting",
            "2026-07-01T14:00:00",
            "2026-07-01T14:30:00");
    fakeClient.tables.put("Meetings", new ArrayList<>(DayFixtures.dayItems(existing)));

    final Map<String, Object> event =
        meetingArguments(
            "room-1",
            "organiser-1",
            List.of("attendee-1"),
            "2026-07-01T14:30:00",
            "2026-07-01T15:00:00");

    final Map<String, Object> result = invoke(event);

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertNotNull(result.get("meeting"));
  }
}
