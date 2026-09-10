package com.mootmaker.handler;

import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.limits.Limits;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateMeetingsHandlerTest {

    private static final String DATE = "2026-09-14";

    private FakeDynamoDbClient fakeClient;
    private CreateMeetingsHandler handler;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("Rooms", new ArrayList<>(List.of(
                new Room("room-1", "Kaikoura", 8).toItem(),
                new Room("room-2", "Wanaka", 8).toItem())));
        fakeClient.tables.put("People", new ArrayList<>(List.of(
                new Person("person-1", "Ada Lovelace", "sub-1").toItem(),
                new Person("person-2", "Alan Turing", "sub-2").toItem())));
        fakeClient.tables.computeIfAbsent("Meetings", _ -> new ArrayList<>())
                .add(DayFixtures.retentionConfig(DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE));
        handler = new CreateMeetingsHandler(fakeClient, "Meetings", "Rooms", "People");
    }

    private static Map<String, Object> input(final String roomId, final String from, final String to) {
        return Map.of("roomId", roomId, "organiserId", "person-1", "attendeeIds", List.of(),
                "subject", "Seeded", "startTime", DATE + "T" + from, "endTime", DATE + "T" + to);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(final List<Map<String, Object>> meetings) {
        return (Map<String, Object>) handler.handleRequest(Map.of(
                "identity", Map.of("sub", "sub-1"),
                "arguments", Map.of("date", DATE, "meetings", meetings)), null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> failuresOf(final Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("failures");
    }

    @Test
    @DisplayName("writes a whole day in one item write")
    void createsEveryValidMeeting() {
        final Map<String, Object> result = invoke(List.of(
                input("room-1", "09:00:00", "09:30:00"),
                input("room-1", "10:00:00", "10:30:00"),
                input("room-2", "09:00:00", "09:30:00")));

        assertTrue(failuresOf(result).isEmpty(), failuresOf(result).toString());
        assertEquals(3, DayFixtures.meetingsIn(fakeClient, "Meetings").size());
        // One day item, not three - the point of the whole design.
        assertEquals(1, fakeClient.tables.get("Meetings").stream()
                .filter(item -> item.get("pk").s().startsWith("DAY#")).count());
    }

    @Test
    @DisplayName("meetings in one call are checked against EACH OTHER, not just against what is stored")
    void rejectsAMeetingThatClashesWithAnEarlierOneInTheSameCall() {
        // The mistake a naive implementation makes: both would pass a check that only looked at the
        // existing day, and the day would end up double-booked by a single request.
        final Map<String, Object> result = invoke(List.of(
                input("room-1", "09:00:00", "10:00:00"),
                input("room-1", "09:30:00", "10:30:00")));

        assertEquals(1, failuresOf(result).size());
        assertEquals(1, failuresOf(result).getFirst().get("index"), "the SECOND one is the failure");
        assertEquals(List.of(MeetingError.TimeRangeUnavailable.name()), failuresOf(result).getFirst().get("errors"));
        assertEquals(1, DayFixtures.meetingsIn(fakeClient, "Meetings").size());
    }

    @Test
    @DisplayName("a rejected input does not fail the call - everything valid is still written")
    void reportsFailuresByIndexAndWritesTheRest() {
        final Map<String, Object> result = invoke(List.of(
                input("room-1", "09:00:00", "09:30:00"),
                input("room-1", "09:07:00", "09:37:00"),
                input("room-1", "11:00:00", "11:30:00")));

        assertEquals(1, failuresOf(result).size());
        assertEquals(1, failuresOf(result).getFirst().get("index"));
        assertTrue(((List<?>) failuresOf(result).getFirst().get("errors"))
                .contains(MeetingError.StartMisaligned.name()));
        assertEquals(2, DayFixtures.meetingsIn(fakeClient, "Meetings").size());
    }

    @Test
    @DisplayName("clashing with a meeting already stored is caught too")
    void rejectsAClashWithAnExistingMeeting() {
        DayFixtures.addMeeting(fakeClient, "Meetings", new MeetingRecord("existing", "room-1", "person-2",
                List.of(), "Already there", DATE + "T09:00:00", DATE + "T10:00:00"));

        final Map<String, Object> result = invoke(List.of(input("room-1", "09:30:00", "10:30:00")));

        assertEquals(List.of(MeetingError.TimeRangeUnavailable.name()), failuresOf(result).getFirst().get("errors"));
    }

    @Test
    @DisplayName("a call larger than one transaction can carry is refused as a whole")
    void rejectsACallOverTheBulkLimit() {
        final List<Map<String, Object>> tooMany = IntStream.rangeClosed(0, Limits.MAX_MEETINGS_PER_BULK_CREATE)
                .mapToObj(i -> input("room-1", "09:00:00", "09:15:00"))
                .toList();

        final Map<String, Object> result = invoke(tooMany);

        assertEquals(List.of(MeetingError.TooManyMeetingsInOneCall.name()),
                failuresOf(result).getFirst().get("errors"));
        assertTrue(DayFixtures.meetingsIn(fakeClient, "Meetings").isEmpty(), "nothing may be written");
    }
}
