package com.mootmaker.handler;

import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListMeetingsHandlerTest {

    private static final Map<String, Object> AUTHENTICATED_EVENT = Map.of("identity", Map.of("sub", "test-user"));

    private static Map<String, Object> eventWithFilter(final Map<String, Object> filter) {
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("filter", filter);
        final Map<String, Object> event = new HashMap<>();
        event.put("arguments", arguments);
        event.put("identity", Map.of("sub", "test-user"));
        return event;
    }

    private static Map<String, Object> rangeFilter(final String fromStartTime, final String toEndTime) {
        final Map<String, Object> filter = new HashMap<>();
        filter.put("fromStartTime", fromStartTime);
        filter.put("toEndTime", toEndTime);
        return filter;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> invoke(final ListMeetingsHandler handler, final Map<String, Object> event) {
        return (List<Map<String, Object>>) handler.handleRequest(event, null);
    }

    private static Set<String> ids(final List<Map<String, Object>> meetings) {
        return meetings.stream().map(meeting -> (String) meeting.get("id")).collect(Collectors.toSet());
    }

    @Test
    void returnsAllMeetingsWithRoomAndPeopleResolvedFromTheirIds() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("Rooms", List.of(new Room("r1", "Conference A", 8).toItem()));
        fakeClient.tables.put("People", List.of(
                new Person("p1", "Ada Lovelace").toItem(),
                new Person("p2", "Alan Turing").toItem()));
        final MeetingRecord record = new MeetingRecord(
                "1", "r1", "p1", List.of("p2"), "Weekly sync", "2026-07-01T14:30:00", "2026-07-01T15:00:00");
        fakeClient.tables.put("Meetings", List.of(record.toItem()));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");

        final List<Map<String, Object>> result = invoke(handler, AUTHENTICATED_EVENT);

        assertEquals(1, result.size());
        final Map<String, Object> resultMeeting = result.getFirst();
        assertEquals("Weekly sync", resultMeeting.get("subject"));
        assertEquals("2026-07-01T14:30:00", resultMeeting.get("startTime"));
        assertEquals("2026-07-01T15:00:00", resultMeeting.get("endTime"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> resultRoom = (Map<String, Object>) resultMeeting.get("room");
        assertEquals("Conference A", resultRoom.get("name"));

        @SuppressWarnings("unchecked")
        final Map<String, Object> resultOrganiser = (Map<String, Object>) resultMeeting.get("organiser");
        assertEquals("Ada Lovelace", resultOrganiser.get("name"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resultAttendees = (List<Map<String, Object>>) resultMeeting.get("attendees");
        assertEquals(1, resultAttendees.size());
        assertEquals("Alan Turing", resultAttendees.getFirst().get("name"));
    }

    @Test
    void resolvesAMeetingWhoseOrganiserOrAttendeeWasDeletedToAPlaceholderInsteadOfCrashing() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("Rooms", List.of(new Room("r1", "Conference A", 8).toItem()));
        // Neither "p1" (organiser) nor "p2" (attendee) has a matching People row - as if both had
        // deleted their accounts (see DeleteMyAccountHandler) after this historical meeting happened.
        fakeClient.tables.put("People", List.of());
        final MeetingRecord record = new MeetingRecord(
                "1", "r1", "p1", List.of("p2"), "Old sync", "2020-07-01T14:30:00", "2020-07-01T15:00:00");
        fakeClient.tables.put("Meetings", List.of(record.toItem()));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");

        final List<Map<String, Object>> result = invoke(handler, AUTHENTICATED_EVENT);

        assertEquals(1, result.size());
        final Map<String, Object> resultMeeting = result.getFirst();
        @SuppressWarnings("unchecked")
        final Map<String, Object> resultOrganiser = (Map<String, Object>) resultMeeting.get("organiser");
        assertEquals("p1", resultOrganiser.get("id"));
        assertEquals("Deleted user", resultOrganiser.get("name"));

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> resultAttendees = (List<Map<String, Object>>) resultMeeting.get("attendees");
        assertEquals("Deleted user", resultAttendees.getFirst().get("name"));
    }

    @Test
    void resolvesTheSamePersonOrRoomOnlyOnceAcrossMultipleMeetings() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("Rooms", List.of(new Room("r1", "Conference A", 8).toItem()));
        fakeClient.tables.put("People", List.of(
                new Person("p1", "Ada Lovelace").toItem(),
                new Person("p2", "Alan Turing").toItem()));
        fakeClient.tables.put("Meetings", List.of(
                new MeetingRecord("1", "r1", "p1", List.of("p2"), "Weekly sync",
                        "2026-07-01T14:30:00", "2026-07-01T15:00:00").toItem(),
                new MeetingRecord("2", "r1", "p2", List.of("p1"), "Follow-up",
                        "2026-07-01T15:00:00", "2026-07-01T15:30:00").toItem()));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");

        final List<Map<String, Object>> result = invoke(handler, AUTHENTICATED_EVENT);

        assertEquals(2, result.size());
        @SuppressWarnings("unchecked")
        final Map<String, Object> firstOrganiser = (Map<String, Object>) result.get(0).get("organiser");
        @SuppressWarnings("unchecked")
        final Map<String, Object> secondOrganiser = (Map<String, Object>) result.get(1).get("organiser");
        assertEquals("Ada Lovelace", firstOrganiser.get("name"));
        assertEquals("Alan Turing", secondOrganiser.get("name"));
    }

    @Test
    void returnsEmptyListWhenTableIsEmpty() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");

        final List<Map<String, Object>> result = invoke(handler, AUTHENTICATED_EVENT);

        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final ListMeetingsHandler handler =
                new ListMeetingsHandler(new FakeDynamoDbClient(), "Meetings", "Rooms", "People", "MeetingParticipants");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));
    }

    @Test
    void filtersByDateRangeExcludingMeetingsOnOtherDays() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        seedRoomsAndPeople(fakeClient);
        fakeClient.tables.put("Meetings", new ArrayList<>(List.of(
                new MeetingRecord("day1", "r1", "p1", List.of(), "Day 1 meeting",
                        "2026-07-01T09:00:00", "2026-07-01T10:00:00").toItem(),
                new MeetingRecord("day2", "r1", "p1", List.of(), "Day 2 meeting",
                        "2026-07-02T09:00:00", "2026-07-02T10:00:00").toItem())));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");
        final Map<String, Object> event = eventWithFilter(rangeFilter("2026-07-01T00:00:00", "2026-07-02T00:00:00"));

        final List<Map<String, Object>> result = invoke(handler, event);

        assertEquals(Set.of("day1"), ids(result));
    }

    @Test
    void dateRangeFilterIncludesAMeetingThatStartedBeforeTheWindowButIsStillOngoing() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        seedRoomsAndPeople(fakeClient);
        fakeClient.tables.put("Meetings", new ArrayList<>(List.of(
                new MeetingRecord("early-start", "r1", "p1", List.of(), "Runs into the window",
                        "2026-07-01T09:00:00", "2026-07-01T10:00:00").toItem())));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");
        final Map<String, Object> event = eventWithFilter(rangeFilter("2026-07-01T09:30:00", "2026-07-01T11:00:00"));

        final List<Map<String, Object>> result = invoke(handler, event);

        assertEquals(Set.of("early-start"), ids(result));
    }

    @Test
    void dateRangeFilterExcludesAMeetingStartingExactlyAtToEndTime() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        seedRoomsAndPeople(fakeClient);
        fakeClient.tables.put("Meetings", new ArrayList<>(List.of(
                new MeetingRecord("next-day", "r1", "p1", List.of(), "Starts right on the boundary",
                        "2026-07-02T00:00:00", "2026-07-02T00:30:00").toItem())));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");
        final Map<String, Object> event = eventWithFilter(rangeFilter("2026-07-01T00:00:00", "2026-07-02T00:00:00"));

        final List<Map<String, Object>> result = invoke(handler, event);

        assertTrue(result.isEmpty());
    }

    @Test
    void filtersByPersonIdMatchingOrganiserOrAttendee() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        seedRoomsAndPeople(fakeClient);
        fakeClient.tables.put("Meetings", new ArrayList<>(List.of(
                new MeetingRecord("organised-by-p1", "r1", "p1", List.of("p2"), "P1 organises",
                        "2026-07-01T09:00:00", "2026-07-01T10:00:00").toItem(),
                new MeetingRecord("attended-by-p1", "r1", "p2", List.of("p1"), "P1 attends",
                        "2026-07-01T11:00:00", "2026-07-01T12:00:00").toItem(),
                new MeetingRecord("no-p1", "r1", "p2", List.of(), "P1 not involved",
                        "2026-07-01T13:00:00", "2026-07-01T14:00:00").toItem())));
        seedParticipants(fakeClient,
                new MeetingParticipant("p1", "organised-by-p1", "2026-07-01T09:00:00", "2026-07-01T10:00:00"),
                new MeetingParticipant("p2", "organised-by-p1", "2026-07-01T09:00:00", "2026-07-01T10:00:00"),
                new MeetingParticipant("p2", "attended-by-p1", "2026-07-01T11:00:00", "2026-07-01T12:00:00"),
                new MeetingParticipant("p1", "attended-by-p1", "2026-07-01T11:00:00", "2026-07-01T12:00:00"),
                new MeetingParticipant("p2", "no-p1", "2026-07-01T13:00:00", "2026-07-01T14:00:00"));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");
        final Map<String, Object> filter = new HashMap<>();
        filter.put("personId", "p1");
        final Map<String, Object> event = eventWithFilter(filter);

        final List<Map<String, Object>> result = invoke(handler, event);

        assertEquals(Set.of("organised-by-p1", "attended-by-p1"), ids(result));
    }

    @Test
    void filtersByPersonIdAndDateRangeTogether() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        seedRoomsAndPeople(fakeClient);
        fakeClient.tables.put("Meetings", new ArrayList<>(List.of(
                new MeetingRecord("p1-day1", "r1", "p1", List.of(), "Day 1", "2026-07-01T09:00:00", "2026-07-01T10:00:00").toItem(),
                new MeetingRecord("p1-day2", "r1", "p1", List.of(), "Day 2", "2026-07-02T09:00:00", "2026-07-02T10:00:00").toItem())));
        seedParticipants(fakeClient,
                new MeetingParticipant("p1", "p1-day1", "2026-07-01T09:00:00", "2026-07-01T10:00:00"),
                new MeetingParticipant("p1", "p1-day2", "2026-07-02T09:00:00", "2026-07-02T10:00:00"));

        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");
        final Map<String, Object> filter = rangeFilter("2026-07-01T00:00:00", "2026-07-02T00:00:00");
        filter.put("personId", "p1");
        final Map<String, Object> event = eventWithFilter(filter);

        final List<Map<String, Object>> result = invoke(handler, event);

        assertEquals(Set.of("p1-day1"), ids(result));
    }

    @Test
    void rejectsWhenOnlyOneOfFromStartTimeOrToEndTimeIsSupplied() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final ListMeetingsHandler handler = new ListMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", "MeetingParticipants");
        final Map<String, Object> filter = new HashMap<>();
        filter.put("fromStartTime", "2026-07-01T00:00:00");
        final Map<String, Object> event = eventWithFilter(filter);

        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(event, null));
    }

    private static void seedRoomsAndPeople(final FakeDynamoDbClient fakeClient) {
        fakeClient.tables.put("Rooms", new ArrayList<>(List.of(new Room("r1", "Conference A", 8).toItem())));
        fakeClient.tables.put("People", new ArrayList<>(List.of(
                new Person("p1", "Ada Lovelace").toItem(),
                new Person("p2", "Alan Turing").toItem())));
    }

    private static void seedParticipants(final FakeDynamoDbClient fakeClient, final MeetingParticipant... participants) {
        fakeClient.tables.put("MeetingParticipants",
                Arrays.stream(participants).map(MeetingParticipant::toItem).collect(Collectors.toCollection(ArrayList::new)));
    }
}
