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

/** Acceptance test for creating a meeting and reading it back via the {@code meetings} query. */
class CreateMeetingAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(CreateMeetingAcceptanceIT.class);

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id name capacity } errors } }";
    private static final String CREATE_PERSON_MUTATION =
            "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { id name } }";
    private static final String MEETING_FIELDS =
            "id room { id name capacity } organiser { id name } attendees { id name } subject startTime endTime";

    private static GraphQlClient client;
    private static Faker faker;

    @BeforeAll
    static void setUpClient() {
        client = GraphQlClient.fromEnvironment();
        faker = new Faker();
    }

    @Test
    void createdMeetingIsReturnedByMeetingsQuery() {
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();

        final String roomName = faker.address().city() + " Room";
        LOG.info("Creating room '{}'", roomName);
        final JsonNode roomResult = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", roomName, "capacity", 5)));
        final String roomId = roomResult.get("createRoom").get("room").get("id").asText();

        final String organiserName = faker.name().fullName();
        LOG.info("Creating organiser '{}'", organiserName);
        final JsonNode organiserResult = client.execute(CREATE_PERSON_MUTATION,
                Map.of("person", Map.of("name", organiserName)));
        final String organiserId = organiserResult.get("createPerson").get("id").asText();

        final String attendeeName = faker.name().fullName();
        LOG.info("Creating attendee '{}'", attendeeName);
        final JsonNode attendeeResult = client.execute(CREATE_PERSON_MUTATION,
                Map.of("person", Map.of("name", attendeeName)));
        final String attendeeId = attendeeResult.get("createPerson").get("id").asText();

        final String subject = faker.company().catchPhrase();
        final String startTime = "2026-08-01T10:00:00";
        final String endTime = "2026-08-01T10:30:00";
        LOG.info("Creating meeting for room '{}' from {} to {}", roomName, startTime, endTime);
        final JsonNode createMeetingResult = client.execute(
                "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) { meeting { "
                        + MEETING_FIELDS + " } errors } }",
                Map.of("meeting", Map.of(
                        "roomId", roomId,
                        "organiserId", organiserId,
                        "attendeeIds", List.of(attendeeId),
                        "subject", subject,
                        "startTime", startTime,
                        "endTime", endTime)));

        final JsonNode createMeetingPayload = createMeetingResult.get("createMeeting");
        assertThat(createMeetingPayload.get("errors").size(), equalTo(0));

        final JsonNode createdMeeting = createMeetingPayload.get("meeting");
        final String meetingId = createdMeeting.get("id").asText();
        LOG.info("Created meeting with id '{}'", meetingId);
        assertThat(createdMeeting.get("room").get("id").asText(), equalTo(roomId));
        assertThat(createdMeeting.get("organiser").get("id").asText(), equalTo(organiserId));
        assertThat(createdMeeting.get("attendees").get(0).get("id").asText(), equalTo(attendeeId));
        assertThat(createdMeeting.get("subject").asText(), equalTo(subject));
        assertThat(createdMeeting.get("startTime").asText(), equalTo(startTime));
        assertThat(createdMeeting.get("endTime").asText(), equalTo(endTime));

        LOG.info("Querying meetings to check the created meeting is returned");
        final JsonNode meetingsResult = client.execute("query { meetings { " + MEETING_FIELDS + " } }");
        final JsonNode meetings = meetingsResult.get("meetings");

        assertThat(meetings.size(), equalTo(1));
        assertThat(meetings.get(0).get("id").asText(), equalTo(meetingId));
        assertThat(meetings.get(0).get("room").get("id").asText(), equalTo(roomId));
        assertThat(meetings.get(0).get("organiser").get("id").asText(), equalTo(organiserId));
        assertThat(meetings.get(0).get("attendees").get(0).get("id").asText(), equalTo(attendeeId));
        LOG.info("Meeting for room '{}' was successfully returned by the meetings query", roomName);
    }
}
