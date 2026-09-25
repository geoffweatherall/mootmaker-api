package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.model.Room;
import com.mootmaker.model.RoomError;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.deleteRoom}. Admin only - see {@link
 * Identity#requireAdmin}.
 *
 * <p>Rejects with {@link RoomError#RoomHasUpcomingMeetings} rather than cascading, unlike {@code
 * DeletePersonHandler}'s cascade for the analogous case on a Person. A room's upcoming meetings can
 * belong to many unrelated organisers - one admin deleting a room shouldn't silently cancel other
 * people's meetings across the whole organisation the way a person's own cascade, naturally scoped
 * to just their own meetings, safely can. Forces a deliberate "move these first" step instead.
 *
 * <p>A hard delete once that check passes: {@code MeetingResponse.resolveRoom} already falls back
 * to a "Deleted room" placeholder for any (necessarily past) meeting still referencing this id, the
 * same way {@code resolvePerson} already does for {@code deleteMyAccount} - proven, not new.
 */
public class DeleteRoomHandler implements RequestHandler<Map<String, Object>, Object> {

  private final RoomRepository rooms;
  private final DayRepository days;

  public DeleteRoomHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"));
  }

  DeleteRoomHandler(
      final DynamoDbClient dynamoDbClient,
      final String roomsTableName,
      final String meetingsTableName) {
    this.rooms = new RoomRepository(dynamoDbClient, roomsTableName);
    this.days = new DayRepository(dynamoDbClient, meetingsTableName);
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAdmin(event);

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String id = (String) arguments.get("id");

    final Map<String, Object> result = new HashMap<>();
    if (rooms.findById(id).isEmpty()) {
      result.put("rooms", allRooms());
      result.put("errors", List.of(RoomError.RoomNotFound.name()));
      return result;
    }

    if (UpcomingMeetings.hasUpcomingMeetingInRoom(days, id, UpcomingMeetings.now())) {
      result.put("rooms", allRooms());
      result.put("errors", List.of(RoomError.RoomHasUpcomingMeetings.name()));
      return result;
    }

    rooms.deleteById(id);

    result.put("rooms", allRooms());
    result.put("errors", List.of());
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }

  /** Every room after the change - see the schema's note on Room mutation results. */
  private List<Map<String, Object>> allRooms() {
    return rooms.listAll().stream().map(Room::toResponseMap).toList();
  }
}
