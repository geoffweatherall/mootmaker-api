package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceptance tests for {@code deleteRoom}: the happy path, {@code RoomNotFound}, and the real
 * reason to prove this against a deployed environment rather than just a unit test - {@code
 * RoomHasUpcomingMeetings} actually blocking deletion once a real meeting is booked in the room via
 * {@code createMeeting}, through the real AppSync/DynamoDB path.
 */
class DeleteRoomAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(DeleteRoomAcceptanceIT.class);

  private static final String CREATE_ROOM_MUTATION =
      "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity }"
          + " errors } }";
  private static final String CREATE_PERSON_MUTATION =
      "mutation CreatePerson($name: String!) { createPerson(name: $name) { person { id } errors }"
          + " }";
  private static final String DELETE_ROOM_MUTATION =
      "mutation DeleteRoom($id: ID!) { deleteRoom(id: $id) { rooms { id } errors } }";

  private static GraphQlClient client;
  private static Faker faker;

  @BeforeAll
  static void setUpClient() {
    client = GraphQlClient.fromEnvironment();
    faker = new Faker();
  }

  private static String createRoom(final String name) {
    final JsonNode result =
        client.execute(CREATE_ROOM_MUTATION, Map.of("room", Map.of("name", name, "capacity", 5)));
    return result.get("createRoom").get("room").get("id").asText();
  }

  private static String createPerson(final String name) {
    final JsonNode result = client.execute(CREATE_PERSON_MUTATION, Map.of("name", name));
    return result.get("createPerson").get("person").get("id").asText();
  }

  @Test
  void deletedRoomIsNoLongerReturnedByRoomsQuery() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String roomName = faker.address().city() + " Room";
    LOG.info("Creating room '{}'", roomName);
    final String roomId = createRoom(roomName);

    LOG.info("Deleting room '{}'", roomId);
    final JsonNode deleteResult = client.execute(DELETE_ROOM_MUTATION, Map.of("id", roomId));

    final JsonNode deleteRoomPayload = deleteResult.get("deleteRoom");
    assertThat(deleteRoomPayload.get("errors").size(), equalTo(0));

    LOG.info("Querying rooms to check the room is gone");
    final JsonNode roomsResult = client.execute("query { workspace { rooms { id } } }");
    final List<String> roomIds = new ArrayList<>();
    roomsResult.get("workspace").get("rooms").forEach(room -> roomIds.add(room.get("id").asText()));
    assertThat(roomIds.contains(roomId), equalTo(false));
    LOG.info("Room '{}' was successfully deleted", roomId);
  }

  @Test
  void refusesToDeleteARoomWithAnUpcomingMeetingAndTheRoomSurvives() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String roomId = createRoom(faker.address().city() + " Room");
    final String organiserId = createPerson(faker.name().fullName());

    LOG.info("Booking a real meeting in room '{}' via createMeeting", roomId);
    final JsonNode meetingResult =
        client.execute(
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) {"
                + " meeting { id } errors } }",
            Map.of(
                "meeting",
                Map.of(
                    "roomId", roomId,
                    "organiserId", organiserId,
                    "attendeeIds", List.of(),
                    "subject", faker.company().catchPhrase(),
                    "startTime", BookableDates.at("10:00:00"),
                    "endTime", BookableDates.at("10:30:00"))));
    assertThat(meetingResult.get("createMeeting").get("errors").size(), equalTo(0));

    LOG.info("Attempting to delete room '{}', which now has an upcoming meeting", roomId);
    final JsonNode deleteResult = client.execute(DELETE_ROOM_MUTATION, Map.of("id", roomId));

    final JsonNode deleteRoomPayload = deleteResult.get("deleteRoom");
    assertThat(
        deleteRoomPayload.get("errors").get(0).asText(),
        equalTo(RoomError.RoomHasUpcomingMeetings.name()));

    final JsonNode roomsResult = client.execute("query { workspace { rooms { id } } }");
    final List<String> roomIds = new ArrayList<>();
    roomsResult.get("workspace").get("rooms").forEach(room -> roomIds.add(room.get("id").asText()));
    assertThat(
        "the room must survive the rejected delete", roomIds.contains(roomId), equalTo(true));
  }

  @Test
  void returnsRoomNotFoundForAMissingId() {
    LOG.info("Checking a delete of a non-existent room id is rejected");
    final JsonNode deleteResult =
        client.execute(DELETE_ROOM_MUTATION, Map.of("id", "00000000-0000-0000-0000-000000000000"));

    assertThat(
        deleteResult.get("deleteRoom").get("errors").get(0).asText(),
        equalTo(RoomError.RoomNotFound.name()));
  }
}
