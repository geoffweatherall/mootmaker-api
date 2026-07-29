package com.mootmaker.verify;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import module java.base;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/** Acceptance tests for creating a room, reading it back via the {@code rooms} query, and validation. */
class CreateRoomAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(CreateRoomAcceptanceIT.class);

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";

    private static GraphQlClient client;
    private static Faker faker;

    @BeforeAll
    static void setUpClient() {
        client = GraphQlClient.fromEnvironment();
        faker = new Faker();
    }

    @Test
    void createdRoomIsReturnedByRoomsQuery() {
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        final String roomName = faker.address().city() + " Room";
        final int capacity = faker.number().numberBetween(2, 20);
        LOG.info("Creating room '{}' with capacity {}", roomName, capacity);
        final JsonNode createResult = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", roomName, "capacity", capacity)));

        final JsonNode createRoomPayload = createResult.get("createRoom");
        assertThat(createRoomPayload.get("errors").size(), equalTo(0));

        final JsonNode createdRoom = createRoomPayload.get("room");
        final String createdId = createdRoom.get("id").asText();
        LOG.info("Created room with id '{}'", createdId);
        assertThat(createdRoom.get("name").asText(), equalTo(roomName));
        assertThat(createdRoom.get("capacity").asInt(), equalTo(capacity));

        LOG.info("Querying rooms to check the created room is returned");
        final JsonNode roomsResult = client.execute("query { rooms { id name capacity } }");
        final JsonNode rooms = roomsResult.get("rooms");

        assertThat(rooms.size(), equalTo(1));
        assertThat(rooms.get(0).get("id").asText(), equalTo(createdId));
        assertThat(rooms.get(0).get("name").asText(), equalTo(roomName));
        assertThat(rooms.get(0).get("capacity").asInt(), equalTo(capacity));
        LOG.info("Room '{}' was successfully returned by the rooms query", roomName);
    }

    @Test
    void blankRoomNameIsRejected() {
        LOG.info("Checking a blank room name is rejected");
        final JsonNode createResult = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", "", "capacity", 5)));

        final JsonNode createRoomPayload = createResult.get("createRoom");
        assertThat(createRoomPayload.get("room").isNull(), is(true));
        assertThat(createRoomPayload.get("errors").get(0).asText(), equalTo(RoomError.NameRequired.name()));
    }

    @Test
    void missingRoomNameIsRejectedWithoutPersistingARoom() {
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        LOG.info("Checking a blank room name does not create a room");
        client.execute(CREATE_ROOM_MUTATION, Map.of("room", Map.of("name", "   ", "capacity", 5)));

        final JsonNode roomsResult = client.execute("query { rooms { id } }");
        assertThat(roomsResult.get("rooms").size(), equalTo(0));
    }

    @Test
    void capacityOfZeroIsRejected() {
        LOG.info("Checking a room capacity of 0 is rejected");
        final JsonNode createResult = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", faker.address().city() + " Room", "capacity", 0)));

        final JsonNode createRoomPayload = createResult.get("createRoom");
        assertThat(createRoomPayload.get("room").isNull(), is(true));
        assertThat(createRoomPayload.get("errors").get(0).asText(), equalTo(RoomError.CapacityTooLow.name()));
    }

    @Test
    void capacityOfOneIsRejected() {
        LOG.info("Checking a room capacity of 1 is rejected");
        final JsonNode createResult = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", faker.address().city() + " Room", "capacity", 1)));

        final JsonNode createRoomPayload = createResult.get("createRoom");
        assertThat(createRoomPayload.get("room").isNull(), is(true));
        assertThat(createRoomPayload.get("errors").get(0).asText(), equalTo(RoomError.CapacityTooLow.name()));
    }

    @Test
    void capacityOfExactlyTwoIsAllowed() {
        LOG.info("Checking a room capacity of exactly 2 is allowed");
        final JsonNode createResult = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", faker.address().city() + " Room", "capacity", 2)));

        final JsonNode createRoomPayload = createResult.get("createRoom");
        assertThat(createRoomPayload.get("errors").size(), equalTo(0));
        assertThat(createRoomPayload.get("room").get("capacity").asInt(), equalTo(2));
    }
}
