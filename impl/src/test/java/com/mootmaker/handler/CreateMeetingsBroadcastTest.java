package com.mootmaker.handler;

import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.RecordingBroadcaster;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk creation's broadcast behaviour, which is the whole reason Decision 5 made bulk creation
 * day-scoped: many meetings, one day, ONE broadcast.
 */
class CreateMeetingsBroadcastTest {

    private static final String DATE = "2026-07-01";

    private FakeDynamoDbClient fakeClient;
    private RecordingBroadcaster broadcaster;
    private CreateMeetingsHandler handler;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("Rooms", new ArrayList<>(List.of(
                new Room("room-1", "Kaikoura", 8).toItem(),
                new Room("room-2", "Wanaka", 8).toItem())));
        fakeClient.tables.put("People", new ArrayList<>(List.of(
                new Person("person-1", "Ada Lovelace", "sub-1").toItem())));
        fakeClient.tables.computeIfAbsent("Meetings", _ -> new ArrayList<>())
                .add(DayFixtures.retentionConfig(DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE));
        broadcaster = new RecordingBroadcaster();
        handler = new CreateMeetingsHandler(fakeClient, "Meetings", "Rooms", "People", broadcaster);
    }

    private static Map<String, Object> input(final String roomId, final String from, final String to) {
        return Map.of("roomId", roomId, "organiserId", "person-1", "attendeeIds", List.of(),
                "subject", "Seeded", "startTime", DATE + "T" + from, "endTime", DATE + "T" + to);
    }

    private Object invoke(final List<Map<String, Object>> meetings) {
        return handler.handleRequest(Map.of(
                "identity", Map.of("sub", "sub-1"),
                "arguments", Map.of("date", DATE, "meetings", meetings)), null);
    }

    @Test
    @DisplayName("many meetings on one date produce exactly ONE broadcast")
    void broadcastsOncePerCallRatherThanPerMeeting() {
        invoke(List.of(
                input("room-1", "09:00:00", "09:30:00"),
                input("room-1", "10:00:00", "10:30:00"),
                input("room-2", "09:00:00", "09:30:00"),
                input("room-2", "11:00:00", "11:30:00")));

        // Asserted as the list-of-calls rather than a flattened set of dates: four broadcasts each
        // carrying the same date would flatten to the same answer, and is exactly the regression
        // this guards - subscribers woken four times to refetch one day.
        assertEquals(List.of(List.of(DATE)), broadcaster.broadcasts,
                "one call that changed one day must wake subscribers once");
    }

    @Test
    @DisplayName("a partially rejected call still broadcasts once, because the day did change")
    void broadcastsWhenSomeWereAccepted() {
        invoke(List.of(
                input("room-1", "09:00:00", "09:30:00"),
                input("room-does-not-exist", "10:00:00", "10:30:00")));

        assertEquals(List.of(List.of(DATE)), broadcaster.broadcasts,
                "the accepted half changed the day, so subscribers must still be told");
    }

    @Test
    @DisplayName("a call where everything was rejected broadcasts nothing")
    void broadcastsNothingWhenAllRejected() {
        invoke(List.of(
                input("room-does-not-exist", "09:00:00", "09:30:00"),
                input("also-not-a-room", "10:00:00", "10:30:00")));

        assertTrue(broadcaster.broadcasts.isEmpty(),
                "nothing was written, so there is nothing to refetch. Was: " + broadcaster.broadcasts);
    }
}
