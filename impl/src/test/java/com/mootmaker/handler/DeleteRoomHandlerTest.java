package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Room;
import com.mootmaker.model.RoomError;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;

class DeleteRoomHandlerTest {

  private static final String ROOMS_TABLE = "Rooms";
  private static final String MEETINGS_TABLE = "Meetings";

  private static final String PAST = "2020-01-01T09:00:00";
  private static final String PAST_END = "2020-01-01T09:30:00";
  private static final String FUTURE = "2099-01-01T09:00:00";
  private static final String FUTURE_END = "2099-01-01T09:30:00";

  private static Map<String, Object> event(final String id, final boolean admin) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", id);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    event.put("identity", Map.of("claims", Map.of("custom:class", admin ? "admin" : "standard")));
    return event;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(
      final DeleteRoomHandler handler, final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  private static FakeDynamoDbClient clientWithRoom(final Room room) {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(ROOMS_TABLE, new ArrayList<>(List.of(room.toItem())));
    return fakeClient;
  }

  @Test
  void deletesARoomWithNoMeetings() {
    final FakeDynamoDbClient dynamoDbClient = clientWithRoom(new Room("room-1", "Kereru", 8));
    final DeleteRoomHandler handler =
        new DeleteRoomHandler(dynamoDbClient, ROOMS_TABLE, MEETINGS_TABLE);

    final Map<String, Object> result = invoke(handler, event("room-1", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertTrue(dynamoDbClient.tables.get(ROOMS_TABLE).isEmpty());
  }

  @Test
  void refusesToDeleteARoomWithAnUpcomingMeetingAndChangesNothing() {
    final FakeDynamoDbClient dynamoDbClient = clientWithRoom(new Room("room-1", "Kereru", 8));
    DayFixtures.addMeeting(
        dynamoDbClient,
        MEETINGS_TABLE,
        new MeetingRecord(
            "meeting-1",
            "room-1",
            "organiser-1",
            List.of(),
            List.of(),
            "Standup",
            FUTURE,
            FUTURE_END));
    final DeleteRoomHandler handler =
        new DeleteRoomHandler(dynamoDbClient, ROOMS_TABLE, MEETINGS_TABLE);

    final Map<String, Object> result = invoke(handler, event("room-1", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(RoomError.RoomHasUpcomingMeetings.name()));
    assertEquals(1, dynamoDbClient.tables.get(ROOMS_TABLE).size(), "the room must survive");
  }

  @Test
  void aRoomWithOnlyPastMeetingsCanStillBeDeleted() {
    final FakeDynamoDbClient dynamoDbClient = clientWithRoom(new Room("room-1", "Kereru", 8));
    DayFixtures.addMeeting(
        dynamoDbClient,
        MEETINGS_TABLE,
        new MeetingRecord(
            "meeting-1",
            "room-1",
            "organiser-1",
            List.of(),
            List.of(),
            "Old standup",
            PAST,
            PAST_END));
    final DeleteRoomHandler handler =
        new DeleteRoomHandler(dynamoDbClient, ROOMS_TABLE, MEETINGS_TABLE);

    final Map<String, Object> result = invoke(handler, event("room-1", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertTrue(dynamoDbClient.tables.get(ROOMS_TABLE).isEmpty());
  }

  @Test
  void returnsRoomNotFoundForAMissingId() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final DeleteRoomHandler handler =
        new DeleteRoomHandler(dynamoDbClient, ROOMS_TABLE, MEETINGS_TABLE);

    final Map<String, Object> result = invoke(handler, event("missing", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(RoomError.RoomNotFound.name()));
  }

  @Test
  void rejectsANonAdminCaller() {
    final FakeDynamoDbClient dynamoDbClient = clientWithRoom(new Room("room-1", "Kereru", 8));
    final DeleteRoomHandler handler =
        new DeleteRoomHandler(dynamoDbClient, ROOMS_TABLE, MEETINGS_TABLE);

    final Map<String, Object> event = event("room-1", false);

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    assertEquals(1, dynamoDbClient.tables.get(ROOMS_TABLE).size());
  }
}
