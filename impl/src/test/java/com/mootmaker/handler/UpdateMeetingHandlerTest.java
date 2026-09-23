package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UpdateMeetingHandlerTest {

  private FakeDynamoDbClient fakeClient;
  private UpdateMeetingHandler handler;

  @BeforeEach
  void setUp() {
    fakeClient = new FakeDynamoDbClient();
    handler = new UpdateMeetingHandler(fakeClient, "Meetings", "Rooms", "People");
    fakeClient.tables.put(
        "Rooms",
        new ArrayList<>(
            List.of(
                new Room("room-a", "Room A", 8).toItem(),
                new Room("room-b", "Room B", 8).toItem())));
    fakeClient.tables.put(
        "People",
        new ArrayList<>(
            List.of(
                new Person("organiser-1", "Ada Lovelace", "organiser-sub").toItem(),
                new Person("admin-1", "Grace Hopper", "admin-sub").toItem(),
                new Person("stranger-1", "Alan Turing", "stranger-sub").toItem())));
  }

  private static MeetingRecord existingMeeting(
      final String roomId, final String start, final String end) {
    return new MeetingRecord(
        "m-1", roomId, "organiser-1", List.of(), List.of(), "Standup", start, end);
  }

  private static Map<String, Object> meetingInput(
      final String roomId, final String startTime, final String endTime) {
    final Map<String, Object> input = new HashMap<>();
    input.put("roomId", roomId);
    input.put("organiserId", "organiser-1");
    input.put("attendeeIds", List.of());
    input.put("subject", "Standup");
    input.put("startTime", startTime);
    input.put("endTime", endTime);
    return input;
  }

  private static Map<String, Object> updateArguments(
      final String id,
      final Map<String, Object> meetingInput,
      final String callerSub,
      final String callerClass) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", id);
    arguments.put("meeting", meetingInput);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    final Map<String, Object> identity = new HashMap<>();
    identity.put("sub", callerSub);
    if (callerClass != null) {
      identity.put("claims", Map.of("custom:class", callerClass));
    }
    event.put("identity", identity);
    return event;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @Test
  @DisplayName("the organiser can edit their own meeting, within its own room, same date")
  void organiserCanEditTheirOwnMeeting() {
    DayFixtures.seed(
        fakeClient,
        "Meetings",
        DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE,
        existingMeeting("room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final Map<String, Object> result =
        invoke(
            updateArguments(
                "m-1",
                meetingInput("room-a", "2026-09-24T10:30:00", "2026-09-24T11:30:00"),
                "organiser-sub",
                "standard"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty(), "errors: " + errors);
    @SuppressWarnings("unchecked")
    final Map<String, Object> meeting = (Map<String, Object>) result.get("meeting");
    assertEquals("2026-09-24T10:30:00", meeting.get("startTime"));
  }

  @Test
  @DisplayName("an admin can edit a meeting they do not organise")
  void adminCanEditSomeoneElsesMeeting() {
    DayFixtures.seed(
        fakeClient,
        "Meetings",
        DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE,
        existingMeeting("room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final Map<String, Object> result =
        invoke(
            updateArguments(
                "m-1",
                meetingInput("room-a", "2026-09-24T10:00:00", "2026-09-24T10:30:00"),
                "admin-sub",
                "admin"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty(), "errors: " + errors);
  }

  @Test
  @DisplayName("someone who is neither the organiser nor admin is forbidden")
  void neitherOrganiserNorAdminIsForbidden() {
    DayFixtures.seed(
        fakeClient,
        "Meetings",
        DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE,
        existingMeeting("room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                invoke(
                    updateArguments(
                        "m-1",
                        meetingInput("room-a", "2026-09-24T10:00:00", "2026-09-24T10:30:00"),
                        "stranger-sub",
                        "standard")));
    assertTrue(thrown.getMessage().contains("Forbidden"), thrown.getMessage());
  }

  @Test
  @DisplayName("an id that doesn't resolve to any meeting reports MeetingNotFound")
  void unknownIdReportsMeetingNotFound() {
    fakeClient
        .tables
        .computeIfAbsent("Meetings", _ -> new ArrayList<>())
        .add(DayFixtures.retentionConfig(DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE));

    final Map<String, Object> result =
        invoke(
            updateArguments(
                "never-existed",
                meetingInput("room-a", "2026-09-24T10:00:00", "2026-09-24T10:30:00"),
                "organiser-sub",
                "standard"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertEquals(List.of(MeetingError.MeetingNotFound.name()), errors);
  }

  @Test
  @DisplayName("editing the date to a different day moves the meeting, id unchanged")
  void movingToADifferentDateMovesTheMeeting() {
    DayFixtures.seed(
        fakeClient,
        "Meetings",
        DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE,
        existingMeeting("room-a", "2026-09-24T10:00:00", "2026-09-24T11:00:00"));

    final Map<String, Object> result =
        invoke(
            updateArguments(
                "m-1",
                meetingInput("room-a", "2026-09-25T10:00:00", "2026-09-25T11:00:00"),
                "organiser-sub",
                "standard"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty(), "errors: " + errors);

    final DayRepository days = new DayRepository(fakeClient, "Meetings");
    assertTrue(days.read("2026-09-24").meetings().isEmpty(), "gone from the old date");
    assertEquals(1, days.read("2026-09-25").meetings().size());
    assertEquals("m-1", days.read("2026-09-25").meetings().getFirst().id());
    assertEquals(Optional.of("2026-09-25"), days.findDateOfMeeting("m-1"));
  }
}
