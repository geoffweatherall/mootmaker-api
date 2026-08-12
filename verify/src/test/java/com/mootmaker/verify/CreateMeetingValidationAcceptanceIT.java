package com.mootmaker.verify;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import module java.base;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Acceptance tests for the {@code createMeeting} validation rules: 5-minute time boundaries,
 * room capacity, and overlapping meetings for the same room (with overlap edge cases).
 */
class CreateMeetingValidationAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(CreateMeetingValidationAcceptanceIT.class);

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id } errors } }";
    private static final String CREATE_PERSON_MUTATION =
            "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { id } }";
    private static final String CREATE_MEETING_MUTATION =
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) { meeting { id } errors } }";

    private static final int ROOM_CAPACITY = 5;

    private static GraphQlClient client;
    private static Faker faker;

    private String roomName;
    private String roomId;
    private String organiserId;
    private String attendeeId;

    @BeforeAll
    static void setUpClient() {
        client = GraphQlClient.fromEnvironment();
        faker = new Faker();
    }

    @BeforeEach
    void resetDatabaseAndCreateFixtures() {
        DatabaseReset.reset();
        roomName = faker.address().city() + " Room";
        roomId = createRoom(roomName, ROOM_CAPACITY);
        organiserId = createPerson(faker.name().fullName());
        attendeeId = createPerson(faker.name().fullName());
        LOG.info("Fixtures ready: room '{}' ({}), organiser {}, attendee {}", roomName, roomId, organiserId, attendeeId);
    }

    @Test
    void startTimeNotOnFiveMinuteBoundaryIsRejected() {
        LOG.info("Checking startTime not aligned to a 5 minute boundary is rejected");
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId),
                "2026-09-01T10:02:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.StartMissaligned.name())));
    }

    @Test
    void endTimeNotOnFiveMinuteBoundaryIsRejected() {
        LOG.info("Checking endTime not aligned to a 5 minute boundary is rejected");
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId),
                "2026-09-01T10:00:00", "2026-09-01T10:33:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.EndMissaligned.name())));
    }

    @Test
    void startTimeWithNonZeroSecondsIsRejected() {
        LOG.info("Checking startTime with non-zero seconds is rejected even though the minute is on a 5 minute boundary");
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId),
                "2026-09-01T10:00:30", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.StartMissaligned.name())));
    }

    @Test
    void blankRoomIdIsRejected() {
        LOG.info("Checking a blank roomId is rejected");
        final JsonNode payload = createMeetingPayload("", organiserId, List.of(attendeeId),
                "2026-09-01T10:00:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.RoomRequired.name())));
        assertThat(errorsOf(payload), not(hasItem(equalTo(MeetingError.RoomNotFound.name()))));
    }

    @Test
    void blankSubjectIsRejected() {
        LOG.info("Checking a blank subject is rejected");
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId), "",
                "2026-09-01T10:00:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.SubjectRequired.name())));
    }

    @Test
    void blankOrganiserIdIsRejected() {
        LOG.info("Checking a blank organiserId is rejected");
        final JsonNode payload = createMeetingPayload(roomId, "", List.of(attendeeId),
                "2026-09-01T10:00:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.OrganiserRequired.name())));
        assertThat(errorsOf(payload), not(hasItem(equalTo(MeetingError.OrganiserNotFound.name()))));
    }

    @Test
    void organiserAlsoListedAsAttendeeIsRejected() {
        LOG.info("Checking an organiserId that also appears in attendeeIds is rejected");
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(organiserId, attendeeId),
                "2026-09-01T10:00:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.OrganiserIsAttendee.name())));
    }

    @Test
    void organiserAlsoListedAsAttendeeDoesNotDoubleCountTowardsCapacity() {
        LOG.info("Checking the organiser-is-attendee case is rejected on its own merits, not reported as "
                + "InsufficientCapacity even though the naive '1 + attendeeIds.size()' count would exceed the room");
        // ROOM_CAPACITY people total (organiser + the other (ROOM_CAPACITY - 1) attendees), but organiserId is
        // also duplicated into attendeeIds, so a naive count would see ROOM_CAPACITY + 1 and wrongly also raise
        // InsufficientCapacity for a meeting that is actually exactly the room's size.
        final List<String> attendeeIds = new ArrayList<>();
        attendeeIds.add(organiserId);
        for (int i = 0; i < ROOM_CAPACITY - 1; i++) {
            attendeeIds.add(createPerson(faker.name().fullName()));
        }
        final JsonNode payload = createMeetingPayload(roomId, organiserId, attendeeIds,
                "2026-09-01T10:00:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.OrganiserIsAttendee.name())));
        assertThat(errorsOf(payload), not(hasItem(equalTo(MeetingError.InsufficientCapacity.name()))));
    }

    @Test
    void insufficientRoomCapacityIsRejected() {
        LOG.info("Checking a meeting exceeding room capacity is rejected");
        final List<String> attendeeIds = new ArrayList<>();
        for (int i = 0; i < ROOM_CAPACITY; i++) {
            attendeeIds.add(createPerson(faker.name().fullName()));
        }
        // organiser + ROOM_CAPACITY attendees = ROOM_CAPACITY + 1 people, one more than the room holds.
        final JsonNode payload = createMeetingPayload(roomId, organiserId, attendeeIds,
                "2026-09-01T10:00:00", "2026-09-01T10:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.InsufficientCapacity.name())));
    }

    @Test
    void identicalTimeRangeOverlapIsRejected() {
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T11:00:00");

        LOG.info("Checking an identical time range for the same room is rejected");
        assertOverlapRejected("2026-09-01T10:00:00", "2026-09-01T11:00:00");
    }

    @Test
    void newMeetingFullyContainingExistingMeetingIsRejected() {
        // Existing meeting is a short window nested inside the new, larger request.
        createExistingMeeting("2026-09-01T10:15:00", "2026-09-01T10:45:00");

        LOG.info("Checking a new meeting that fully contains a smaller existing meeting is rejected");
        assertOverlapRejected("2026-09-01T10:00:00", "2026-09-01T11:00:00");
    }

    @Test
    void newMeetingFullyContainedWithinExistingMeetingIsRejected() {
        // Existing meeting is a large window; the new request is a short window nested inside it.
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T11:00:00");

        LOG.info("Checking a new meeting fully contained within a larger existing meeting is rejected");
        assertOverlapRejected("2026-09-01T10:15:00", "2026-09-01T10:45:00");
    }

    @Test
    void newMeetingOverlappingStartOfExistingMeetingIsRejected() {
        createExistingMeeting("2026-09-01T10:30:00", "2026-09-01T11:00:00");

        LOG.info("Checking a new meeting that starts before and ends during an existing meeting is rejected");
        assertOverlapRejected("2026-09-01T10:00:00", "2026-09-01T10:45:00");
    }

    @Test
    void newMeetingOverlappingEndOfExistingMeetingIsRejected() {
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T10:30:00");

        LOG.info("Checking a new meeting that starts during and ends after an existing meeting is rejected");
        assertOverlapRejected("2026-09-01T10:15:00", "2026-09-01T11:00:00");
    }

    @Test
    void meetingImmediatelyAfterExistingMeetingIsAllowed() {
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T10:30:00");

        LOG.info("Checking a new meeting starting exactly when an existing meeting ends is allowed (back-to-back)");
        assertOverlapAllowed("2026-09-01T10:30:00", "2026-09-01T11:00:00");
    }

    @Test
    void meetingImmediatelyBeforeExistingMeetingIsAllowed() {
        createExistingMeeting("2026-09-01T10:30:00", "2026-09-01T11:00:00");

        LOG.info("Checking a new meeting ending exactly when an existing meeting starts is allowed (back-to-back)");
        assertOverlapAllowed("2026-09-01T10:00:00", "2026-09-01T10:30:00");
    }

    @Test
    void nonOverlappingMeetingForSameRoomIsAllowed() {
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T10:30:00");

        LOG.info("Checking a clearly separate time range for the same room is allowed");
        assertOverlapAllowed("2026-09-01T10:45:00", "2026-09-01T11:00:00");
    }

    @Test
    void overlappingTimeRangeForDifferentRoomIsAllowed() {
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T11:00:00");

        LOG.info("Checking the identical time range is allowed when booked against a different room");
        final String otherRoomId = createRoom(faker.address().city() + " Room", ROOM_CAPACITY);
        final JsonNode payload = createMeetingPayload(otherRoomId, organiserId, List.of(attendeeId),
                "2026-09-01T10:00:00", "2026-09-01T11:00:00");

        assertThat(errorsOf(payload), is(empty()));
        assertThat(meetingOf(payload).isNull(), is(false));
    }

    @Test
    void multipleValidationErrorsAreAllReturnedTogetherInOneRequest() {
        LOG.info("Checking that unrelated validation failures (missing organiser, insufficient capacity, "
                + "unavailable time range) are all reported together rather than stopping at the first one");
        createExistingMeeting("2026-09-01T10:00:00", "2026-09-01T11:00:00");

        final List<String> tooManyAttendees = new ArrayList<>();
        for (int i = 0; i < ROOM_CAPACITY; i++) {
            tooManyAttendees.add(createPerson(faker.name().fullName()));
        }

        // organiserId does not exist; attendee count exceeds room capacity; time range overlaps the existing meeting.
        final JsonNode payload = createMeetingPayload(roomId, "does-not-exist", tooManyAttendees,
                "2026-09-01T10:30:00", "2026-09-01T11:30:00");

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), containsInAnyOrder(
                MeetingError.OrganiserNotFound.name(),
                MeetingError.InsufficientCapacity.name(),
                MeetingError.TimeRangeUnavailable.name()));
    }

    private void createExistingMeeting(final String startTime, final String endTime) {
        LOG.info("Creating existing meeting from {} to {}", startTime, endTime);
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId), startTime, endTime);
        assertThat("fixture meeting must be created successfully", errorsOf(payload), is(empty()));
    }

    private void assertOverlapRejected(final String startTime, final String endTime) {
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId), startTime, endTime);

        assertThat(meetingOf(payload).isNull(), is(true));
        assertThat(errorsOf(payload), hasItem(equalTo(MeetingError.TimeRangeUnavailable.name())));
    }

    private void assertOverlapAllowed(final String startTime, final String endTime) {
        final JsonNode payload = createMeetingPayload(roomId, organiserId, List.of(attendeeId), startTime, endTime);

        assertThat(errorsOf(payload), is(empty()));
        assertThat(meetingOf(payload).isNull(), is(false));
    }

    private String createRoom(final String name, final int capacity) {
        final JsonNode result = client.execute(CREATE_ROOM_MUTATION, Map.of("room", Map.of("name", name, "capacity", capacity)));
        return result.get("createRoom").get("room").get("id").asText();
    }

    private String createPerson(final String name) {
        final JsonNode result = client.execute(CREATE_PERSON_MUTATION, Map.of("person", Map.of("name", name)));
        return result.get("createPerson").get("id").asText();
    }

    private JsonNode createMeetingPayload(final String roomId, final String organiserId, final List<String> attendeeIds,
            final String startTime, final String endTime) {
        return createMeetingPayload(roomId, organiserId, attendeeIds, "Team sync", startTime, endTime);
    }

    private JsonNode createMeetingPayload(final String roomId, final String organiserId, final List<String> attendeeIds,
            final String subject, final String startTime, final String endTime) {
        final JsonNode result = client.execute(CREATE_MEETING_MUTATION, Map.of("meeting", Map.of(
                "roomId", roomId,
                "organiserId", organiserId,
                "attendeeIds", attendeeIds,
                "subject", subject,
                "startTime", startTime,
                "endTime", endTime)));
        return result.get("createMeeting");
    }

    private static JsonNode meetingOf(final JsonNode createMeetingPayload) {
        return createMeetingPayload.get("meeting");
    }

    private static List<String> errorsOf(final JsonNode createMeetingPayload) {
        final List<String> errors = new ArrayList<>();
        createMeetingPayload.get("errors").forEach(node -> errors.add(node.asText()));
        return errors;
    }
}
