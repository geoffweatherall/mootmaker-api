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
 * What gets broadcast, and - just as importantly - what does not.
 *
 * <p>Both halves are asserted on purpose. A test that only checks "the broadcast I wanted arrived"
 * passes against an implementation that broadcasts on every call including rejected ones, which is
 * precisely the bug this design exists to avoid: AppSync broadcasts a rejected mutation exactly
 * like a successful one (verified), which is why bookings are published from a separate field
 * rather than by subscribing to createMeeting itself.
 */
class CreateMeetingBroadcastTest {

    private static final String DATE = "2026-07-01";

    private FakeDynamoDbClient fakeClient;
    private RecordingBroadcaster broadcaster;
    private CreateMeetingHandler handler;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.computeIfAbsent("Meetings", _ -> new ArrayList<>())
                .add(DayFixtures.retentionConfig(DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE));
        fakeClient.tables.put("Rooms", List.of(new Room("room-1", "Conference A", 2).toItem()));
        fakeClient.tables.put("People", List.of(
                new Person("organiser-1", "Ada Lovelace").toItem(),
                new Person("attendee-1", "Alan Turing").toItem()));
        broadcaster = new RecordingBroadcaster();
        handler = new CreateMeetingHandler(fakeClient, "Meetings", "Rooms", "People", broadcaster);
    }

    private static Map<String, Object> event(final String startTime, final String endTime, final String roomId) {
        final Map<String, Object> meeting = new HashMap<>();
        meeting.put("roomId", roomId);
        meeting.put("organiserId", "organiser-1");
        meeting.put("attendeeIds", List.of("attendee-1"));
        meeting.put("subject", "Team sync");
        meeting.put("startTime", startTime);
        meeting.put("endTime", endTime);
        return new HashMap<>(Map.of(
                "arguments", new HashMap<>(Map.of("meeting", meeting)),
                "identity", Map.of("sub", "test-user")));
    }

    @Test
    @DisplayName("a created meeting broadcasts its own date, once")
    void broadcastsTheDateOfACreatedMeeting() {
        handler.handleRequest(event(DATE + "T14:30:00", DATE + "T15:00:00", "room-1"), null);

        assertEquals(List.of(List.of(DATE)), broadcaster.broadcasts,
                "one broadcast, carrying exactly the day that changed");
    }

    @Test
    @DisplayName("a rejected meeting broadcasts nothing at all")
    void broadcastsNothingWhenRejected() {
        // An unknown room: rejected, so no day item is written and nothing changed for anyone.
        final Object result = handler.handleRequest(
                event(DATE + "T14:30:00", DATE + "T15:00:00", "room-does-not-exist"), null);

        assertTrue(broadcaster.broadcasts.isEmpty(),
                "a booking that was refused changed no day - waking every client to refetch an "
                        + "unchanged day is pure cost. Was: " + broadcaster.broadcasts);
        assertTrue(result.toString().contains("errors"), "precondition: the write really was rejected");
    }

    @Test
    @DisplayName("the date broadcast is the meeting's own date, not today")
    void broadcastsTheMeetingsDateRatherThanToday() {
        // Guards the substring(0, 10) that derives the date: a meeting booked for a future day must
        // invalidate THAT day, not the day the booking happened to be made on.
        //
        // Relative to today rather than a fixed date, and well inside the 180-day booking horizon.
        // A hardcoded future date is a slow-motion failure: it passes until the horizon moves past
        // it, then fails for a reason that has nothing to do with broadcasting. The first draft of
        // this test used 2027-11-23 and was rejected as OutsideBookableRange.
        final String future = LocalDate.now().plusDays(60).toString();
        handler.handleRequest(event(future + "T09:00:00", future + "T09:30:00", "room-1"), null);

        assertEquals(List.of(future), broadcaster.allDates(),
                "the day that changed is the meeting's own, not the day it was booked on");
    }
}
