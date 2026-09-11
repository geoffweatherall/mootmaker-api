package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Query.meeting(id:)}.
 *
 * <p>The one lookup that is not a date range. It used to be served by fetching every meeting ever
 * stored and filtering client-side; it is now two point reads - the {@code PTR#} pointer for the
 * date, then that day - which is the entire reason the pointer is written in the same transaction
 * as the day item.
 *
 * <p><b>Not found and aged-out-of-retention return the same answer.</b> That is deliberate: callers
 * handle one case instead of two, and nothing confirms that a given id was once valid. A shared
 * link to a meeting older than retention simply stops resolving.
 */
public class MeetingByIdHandler implements RequestHandler<Map<String, Object>, Object> {

  private final DayRepository days;
  private final RoomRepository rooms;
  private final PersonRepository people;

  public MeetingByIdHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  MeetingByIdHandler(
      final DynamoDbClient dynamoDbClient,
      final String meetingsTableName,
      final String roomsTableName,
      final String peopleTableName) {
    this(
        new DayRepository(dynamoDbClient, meetingsTableName),
        new RoomRepository(dynamoDbClient, roomsTableName),
        new PersonRepository(dynamoDbClient, peopleTableName));
  }

  MeetingByIdHandler(
      final DayRepository days, final RoomRepository rooms, final PersonRepository people) {
    this.days = days;
    this.rooms = rooms;
    this.people = people;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final Object arguments = event.get("arguments");
    final String id =
        arguments instanceof Map<?, ?> args
            ? (String) ((Map<String, Object>) args).get("id")
            : null;
    if (id == null || id.isBlank()) {
      return null;
    }

    // A pointer whose day no longer holds the meeting resolves to not-found rather than an error:
    // the day item is the source of truth, and an orphaned pointer is a state the design tolerates
    // precisely because it degrades this way.
    final Optional<MeetingRecord> found =
        days.findDateOfMeeting(id)
            .map(days::read)
            .flatMap(day -> day.meetings().stream().filter(m -> m.id().equals(id)).findFirst());
    if (found.isEmpty()) {
      return null;
    }

    final MeetingRecord record = found.get();
    final SelectionSet selection = SelectionSet.from(event);
    final boolean resolveRooms = selection.needsLookup("room");
    final boolean resolvePeople =
        selection.needsLookup("organiser") || selection.needsLookup("attendees");

    final Map<String, Room> roomsById =
        resolveRooms ? rooms.loadByIds(Set.of(record.roomId())) : Map.of();
    final Map<String, Person> peopleById =
        resolvePeople
            ? people.loadByIds(
                Stream.concat(Stream.of(record.organiserId()), record.attendeeIds().stream())
                    .collect(Collectors.toSet()))
            : Map.of();

    return MeetingResponse.of(record, roomsById, peopleById, resolveRooms, resolvePeople);
  }
}
