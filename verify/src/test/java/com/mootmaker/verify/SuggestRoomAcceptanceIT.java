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

/** Acceptance test for {@code Query.suggestRoom}. */
class SuggestRoomAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(SuggestRoomAcceptanceIT.class);

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";
    private static final String CREATE_PERSON_MUTATION =
            "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { id name } }";
    private static final String CREATE_MEETING_MUTATION =
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) { meeting { id } errors } }";
    private static final String SUGGEST_ROOM_QUERY =
            "query SuggestRoom($startTime: String!, $endTime: String!, $requiredCapacity: Int!) {"
                    + " suggestRoom(startTime: $startTime, endTime: $endTime, requiredCapacity: $requiredCapacity)"
                    + " { id name capacity } }";

    private static GraphQlClient client;
    private static Faker faker;

    @BeforeAll
    static void setUpClient() {
        client = GraphQlClient.fromEnvironment();
        faker = new Faker();
    }

    private String createRoom(final String name, final int capacity) {
        final JsonNode result = client.execute(CREATE_ROOM_MUTATION, Map.of("room", Map.of("name", name, "capacity", capacity)));
        return result.get("createRoom").get("room").get("id").asText();
    }

    private String createPerson(final String name) {
        final JsonNode result = client.execute(CREATE_PERSON_MUTATION, Map.of("person", Map.of("name", name)));
        return result.get("createPerson").get("id").asText();
    }

    private void bookMeeting(final String roomId, final String organiserId, final String startTime, final String endTime) {
        final JsonNode result = client.execute(CREATE_MEETING_MUTATION, Map.of("meeting", Map.of(
                "roomId", roomId,
                "organiserId", organiserId,
                "attendeeIds", List.of(),
                "subject", "Existing meeting",
                "startTime", startTime,
                "endTime", endTime)));
        assertThat(result.get("createMeeting").get("errors").size(), equalTo(0));
    }

    private JsonNode suggestRoom(final String startTime, final String endTime, final int requiredCapacity) {
        final Map<String, Object> variables = new HashMap<>();
        variables.put("startTime", startTime);
        variables.put("endTime", endTime);
        variables.put("requiredCapacity", requiredCapacity);
        return client.execute(SUGGEST_ROOM_QUERY, variables).get("suggestRoom");
    }

    private static List<String> idsOf(final JsonNode rooms) {
        final List<String> ids = new ArrayList<>();
        rooms.forEach(room -> ids.add(room.get("id").asText()));
        return ids;
    }

    @Test
    void ranksEveryFreeRoomWithSufficientCapacitySmallestSurplusFirst() {
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        final String smallRoomName = faker.address().city() + " Small";
        final String largeRoomName = faker.address().city() + " Large";
        LOG.info("Creating rooms '{}' (capacity 3) and '{}' (capacity 6)", smallRoomName, largeRoomName);
        final String smallRoomId = createRoom(smallRoomName, 3);
        final String largeRoomId = createRoom(largeRoomName, 6);

        final String organiserId = createPerson(faker.name().fullName());

        final String startTime = "2026-08-02T10:00:00";
        final String endTime = "2026-08-02T10:30:00";

        LOG.info("Suggesting rooms for 3 people - expecting both, smallest surplus first");
        final JsonNode bothRooms = suggestRoom(startTime, endTime, 3);
        assertThat(idsOf(bothRooms), equalTo(List.of(smallRoomId, largeRoomId)));

        LOG.info("Booking the small room, then re-suggesting - expecting only the larger room");
        bookMeeting(smallRoomId, organiserId, startTime, endTime);
        final JsonNode remainingRoom = suggestRoom(startTime, endTime, 3);
        assertThat(idsOf(remainingRoom), equalTo(List.of(largeRoomId)));

        LOG.info("Requiring more capacity than any room has - expecting an empty list");
        final JsonNode noSuggestions = suggestRoom(startTime, endTime, 100);
        assertThat(noSuggestions.isEmpty(), is(true));
    }
}
