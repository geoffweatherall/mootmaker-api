package com.mootmaker.verify;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import module java.base;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Acceptance coverage for selection-aware resolving of {@code Query.meetings}.
 *
 * <p>Whether a lookup was <i>skipped</i> is not observable from outside the API, and deliberately so
 * - it is an optimisation, not a contract. What is observable, and what actually matters, is that
 * every selection still returns correct data. The alias case is the one worth having a deployed test
 * for: if the resolver decided an aliased name was unwanted and returned an id-only object, GraphQL
 * would null the non-null field and cascade the null up through the list to the meeting. That failure
 * cannot happen in a unit test of the selection logic alone, because it needs a real GraphQL executor
 * enforcing non-null.
 */
class SelectionAwareMeetingsAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(SelectionAwareMeetingsAcceptanceIT.class);

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";
    private static final String CREATE_PERSON_MUTATION =
            "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { person { id name } errors } }";
    private static final String CREATE_MEETING_MUTATION =
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) { meeting { id } errors } }";

    private static final String START_TIME = BookableDates.at("09:00:00");
    private static final String END_TIME = BookableDates.at("09:30:00");

    private static GraphQlClient client;
    private static Faker faker;

    private static String roomId;
    private static String roomName;
    private static String organiserId;
    private static String organiserName;
    private static String attendeeId;
    private static String attendeeName;

    @BeforeAll
    static void createOneMeetingToInspect() {
        client = GraphQlClient.fromEnvironment();
        faker = new Faker();

        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        roomName = faker.address().city() + " Room";
        organiserName = faker.name().fullName();
        attendeeName = faker.name().fullName();

        roomId = createRoom(roomName, 8);
        organiserId = createPerson(organiserName);
        attendeeId = createPerson(attendeeName);

        LOG.info("Booking one meeting in '{}' organised by '{}' with attendee '{}'", roomName, organiserName, attendeeName);
        final JsonNode created = client.execute(CREATE_MEETING_MUTATION, Map.of("meeting", Map.of(
                "roomId", roomId,
                "organiserId", organiserId,
                "attendeeIds", List.of(attendeeId),
                "subject", "Selection-aware coverage",
                "startTime", START_TIME,
                "endTime", END_TIME)));
        assertThat(created.get("createMeeting").get("errors").size(), equalTo(0));
    }

    private static String createRoom(final String name, final int capacity) {
        final JsonNode result = client.execute(CREATE_ROOM_MUTATION, Map.of("room", Map.of("name", name, "capacity", capacity)));
        return result.get("createRoom").get("room").get("id").asText();
    }

    private static String createPerson(final String name) {
        final JsonNode result = client.execute(CREATE_PERSON_MUTATION, Map.of("person", Map.of("name", name)));
        return result.get("createPerson").get("person").get("id").asText();
    }

    /**
     * Reads the one day this test seeded through the composite entry point, and returns its single
     * meeting. The old {@code meetings(filter:)} field is gone: a date range is a list of day keys now.
     */
    private JsonNode theMeeting(final String meetingSelection) {
        final String query = "query Workspace($dates: [String!]) { workspace(dates: $dates) {"
                + " days { date meetings { " + meetingSelection + " } } } }";
        final JsonNode days = client.execute(query, Map.of("dates", List.of(BookableDates.day().toString())))
                .get("workspace").get("days");
        assertThat("one date was asked for, so exactly one day must come back", days.size(), equalTo(1));
        final JsonNode meetings = days.get(0).get("meetings");
        assertThat("expected exactly the one seeded meeting", meetings.size(), equalTo(1));
        return meetings.get(0);
    }

    @Test
    @DisplayName("scalars only - no nested object is selected at all")
    void returnsScalarsWithNoNestedSelection() {
        final JsonNode meeting = theMeeting("id subject startTime endTime");
        assertThat(meeting.get("subject").asText(), equalTo("Selection-aware coverage"));
        assertThat(meeting.get("startTime").asText(), equalTo(START_TIME));
        assertThat(meeting.get("id").asText().length(), is(greaterThan(0)));
    }

    @Test
    @DisplayName("ids only - resolvable without any room or person lookup, and still correct")
    void returnsCorrectIdsWithoutResolvingNames() {
        final JsonNode meeting = theMeeting("id room { id } organiser { id } attendees { id }");
        assertThat(meeting.get("room").get("id").asText(), equalTo(roomId));
        assertThat(meeting.get("organiser").get("id").asText(), equalTo(organiserId));
        assertThat(meeting.get("attendees").size(), equalTo(1));
        assertThat(meeting.get("attendees").get(0).get("id").asText(), equalTo(attendeeId));
    }

    @Test
    @DisplayName("full selection - names and capacity are resolved")
    void returnsResolvedNamesWhenSelected() {
        final JsonNode meeting = theMeeting("id room { id name capacity } organiser { id name } attendees { id name }");
        assertThat(meeting.get("room").get("name").asText(), equalTo(roomName));
        assertThat(meeting.get("room").get("capacity").asInt(), equalTo(8));
        assertThat(meeting.get("organiser").get("name").asText(), equalTo(organiserName));
        assertThat(meeting.get("attendees").get(0).get("name").asText(), equalTo(attendeeName));
    }

    @Test
    @DisplayName("ALIASED names still resolve - the case that would otherwise null a non-null field and cascade")
    void returnsResolvedNamesWhenTheyAreAliased() {
        final JsonNode meeting = theMeeting(
                "id room { id whereWeAre: name } organiser { id whoBooked: name } attendees { alsoComing: name }");
        assertThat(meeting.get("room").get("whereWeAre"), is(notNullValue()));
        assertThat(meeting.get("room").get("whereWeAre").asText(), equalTo(roomName));
        assertThat(meeting.get("organiser").get("whoBooked").asText(), equalTo(organiserName));
        assertThat(meeting.get("attendees").get(0).get("alsoComing").asText(), equalTo(attendeeName));
    }

    @Test
    @DisplayName("a fragment resolves the same as an inline selection")
    void returnsResolvedNamesFromAFragment() {
        final String query = "query Workspace($dates: [String!]) {"
                + " workspace(dates: $dates) { days { meetings { id room { ...RoomFields } } } } }"
                + " fragment RoomFields on Room { id name capacity }";
        final JsonNode days = client.execute(query, Map.of("dates", List.of(BookableDates.day().toString())))
                .get("workspace").get("days");
        final JsonNode meetings = days.get(0).get("meetings");
        assertThat(meetings.size(), equalTo(1));
        assertThat(meetings.get(0).get("room").get("name").asText(), equalTo(roomName));
    }
}
