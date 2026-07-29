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

/**
 * Acceptance tests for updating a room's name/capacity, reading the change back via the {@code
 * rooms} query, and validation. The acceptance-test client authenticates as the M2M tooling
 * client, which carries the admin-equivalent OAuth scope (see the API README's "M2M tooling"
 * section) - there's no way to exercise a non-admin rejection from this suite, since it has no
 * real signed-in user to authenticate as; that's covered by {@code UpdateRoomHandlerTest} instead.
 */
class UpdateRoomAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateRoomAcceptanceIT.class);

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";
    private static final String UPDATE_ROOM_MUTATION =
            "mutation UpdateRoom($id: ID!, $room: RoomInput!) { updateRoom(id: $id, room: $room) { room { id name capacity } errors } }";

    private static GraphQlClient client;
    private static Faker faker;

    @BeforeAll
    static void setUpClient() {
        client = GraphQlClient.fromEnvironment();
        faker = new Faker();
    }

    private static String createRoom(final String name, final int capacity) {
        final JsonNode result = client.execute(CREATE_ROOM_MUTATION, Map.of("room", Map.of("name", name, "capacity", capacity)));
        return result.get("createRoom").get("room").get("id").asText();
    }

    @Test
    void updatedRoomIsReturnedByRoomsQuery() {
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        final String originalName = faker.address().city() + " Room";
        LOG.info("Creating room '{}'", originalName);
        final String roomId = createRoom(originalName, 8);

        final String newName = faker.address().city() + " Room";
        LOG.info("Updating room '{}' to '{}' with capacity 12", roomId, newName);
        final JsonNode updateResult =
                client.execute(UPDATE_ROOM_MUTATION, Map.of("id", roomId, "room", Map.of("name", newName, "capacity", 12)));

        final JsonNode updateRoomPayload = updateResult.get("updateRoom");
        assertThat(updateRoomPayload.get("errors").size(), equalTo(0));
        assertThat(updateRoomPayload.get("room").get("name").asText(), equalTo(newName));
        assertThat(updateRoomPayload.get("room").get("capacity").asInt(), equalTo(12));

        LOG.info("Querying rooms to check the update is reflected");
        final JsonNode roomsResult = client.execute("query { rooms { id name capacity } }");
        final JsonNode room = roomsResult.get("rooms").get(0);
        assertThat(room.get("id").asText(), equalTo(roomId));
        assertThat(room.get("name").asText(), equalTo(newName));
        assertThat(room.get("capacity").asInt(), equalTo(12));
        LOG.info("Room '{}' was successfully updated to '{}'", roomId, newName);
    }

    @Test
    void updatingAMissingRoomIsRejected() {
        LOG.info("Checking an update to a non-existent room id is rejected");
        final JsonNode updateResult = client.execute(UPDATE_ROOM_MUTATION,
                Map.of("id", "00000000-0000-0000-0000-000000000000", "room", Map.of("name", "Anything", "capacity", 5)));

        final JsonNode updateRoomPayload = updateResult.get("updateRoom");
        assertThat(updateRoomPayload.get("room").isNull(), is(true));
        assertThat(updateRoomPayload.get("errors").get(0).asText(), equalTo(RoomError.RoomNotFound.name()));
    }

    @Test
    void updatingWithABlankNameIsRejected() {
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        final String roomId = createRoom(faker.address().city() + " Room", 8);

        LOG.info("Checking a blank name is rejected on update");
        final JsonNode updateResult =
                client.execute(UPDATE_ROOM_MUTATION, Map.of("id", roomId, "room", Map.of("name", "   ", "capacity", 8)));

        final JsonNode updateRoomPayload = updateResult.get("updateRoom");
        assertThat(updateRoomPayload.get("room").isNull(), is(true));
        assertThat(updateRoomPayload.get("errors").get(0).asText(), equalTo(RoomError.NameRequired.name()));
    }
}
