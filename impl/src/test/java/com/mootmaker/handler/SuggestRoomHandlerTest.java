package com.mootmaker.handler;

import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestRoomHandlerTest {

    private FakeDynamoDbClient fakeClient;
    private SuggestRoomHandler handler;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeDynamoDbClient();
        handler = new SuggestRoomHandler(fakeClient, "Rooms", "Meetings");

        fakeClient.tables.put("Rooms", new ArrayList<>(List.of(
                new Room("small", "Small Room", 2).toItem(),
                new Room("medium", "Medium Room", 4).toItem(),
                new Room("large", "Large Room", 8).toItem())));
    }

    private static Map<String, Object> suggestArguments(final String startTime, final String endTime,
            final Integer requiredCapacity) {
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("startTime", startTime);
        arguments.put("endTime", endTime);
        arguments.put("requiredCapacity", requiredCapacity);
        final Map<String, Object> event = new HashMap<>();
        event.put("arguments", arguments);
        event.put("identity", Map.of("sub", "test-user"));
        return event;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invoke(final Map<String, Object> event) {
        return (List<Map<String, Object>>) handler.handleRequest(event, null);
    }

    private static List<Object> ids(final List<Map<String, Object>> rooms) {
        return rooms.stream().map(room -> room.get("id")).toList();
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final Map<String, Object> event = suggestArguments("2026-07-01T14:00:00", "2026-07-01T15:00:00", 3);
        event.remove("identity");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    }

    @Test
    void ranksRoomsBySufficientCapacityFirstThenSmallestSurplus() {
        final List<Map<String, Object>> result = invoke(
                suggestArguments("2026-07-01T14:00:00", "2026-07-01T15:00:00", 3));

        assertEquals(List.of("medium", "large"), ids(result));
    }

    @Test
    void breaksTiesInCapacityByRoomName() {
        fakeClient.tables.put("Rooms", new ArrayList<>(List.of(
                new Room("small", "Small Room", 2).toItem(),
                new Room("medium-b", "Room B", 4).toItem(),
                new Room("medium-a", "Room A", 4).toItem())));

        final List<Map<String, Object>> result = invoke(
                suggestArguments("2026-07-01T14:00:00", "2026-07-01T15:00:00", 3));

        assertEquals(List.of("medium-a", "medium-b"), ids(result));
    }

    @Test
    void returnsAnEmptyListWhenNoRoomHasSufficientCapacity() {
        final List<Map<String, Object>> result = invoke(
                suggestArguments("2026-07-01T14:00:00", "2026-07-01T15:00:00", 9));

        assertTrue(result.isEmpty());
    }

    @Test
    void omitsARoomThatIsAlreadyBookedOverTheRequestedTime() {
        final MeetingRecord existing = new MeetingRecord("existing-meeting", "medium", "organiser-1", List.of(),
                "Existing meeting", "2026-07-01T14:00:00", "2026-07-01T15:00:00");
        fakeClient.tables.put("Meetings", List.of(existing.toItem()));

        final List<Map<String, Object>> result = invoke(
                suggestArguments("2026-07-01T14:30:00", "2026-07-01T15:30:00", 3));

        assertEquals(List.of("large"), ids(result));
    }

    @Test
    void returnsAnEmptyListWhenEveryRoomWithSufficientCapacityIsBusy() {
        final MeetingRecord medium = new MeetingRecord("m1", "medium", "organiser-1", List.of(),
                "Meeting", "2026-07-01T14:00:00", "2026-07-01T15:00:00");
        final MeetingRecord large = new MeetingRecord("m2", "large", "organiser-1", List.of(),
                "Meeting", "2026-07-01T14:00:00", "2026-07-01T15:00:00");
        fakeClient.tables.put("Meetings", List.of(medium.toItem(), large.toItem()));

        final List<Map<String, Object>> result = invoke(
                suggestArguments("2026-07-01T14:30:00", "2026-07-01T15:30:00", 3));

        assertTrue(result.isEmpty());
    }

    @Test
    void returnsAnEmptyListWhenStartTimeIsMissingOrUnparseable() {
        assertTrue(invoke(suggestArguments(null, "2026-07-01T15:00:00", 3)).isEmpty());
        assertTrue(invoke(suggestArguments("not-a-time", "2026-07-01T15:00:00", 3)).isEmpty());
    }

    @Test
    void returnsAnEmptyListWhenEndTimeIsNotAfterStartTime() {
        assertTrue(invoke(suggestArguments("2026-07-01T15:00:00", "2026-07-01T15:00:00", 3)).isEmpty());
        assertTrue(invoke(suggestArguments("2026-07-01T15:00:00", "2026-07-01T14:00:00", 3)).isEmpty());
    }

    @Test
    void returnsAnEmptyListWhenRequiredCapacityIsMissingOrNotPositive() {
        assertTrue(invoke(suggestArguments("2026-07-01T14:00:00", "2026-07-01T15:00:00", null)).isEmpty());
        assertTrue(invoke(suggestArguments("2026-07-01T14:00:00", "2026-07-01T15:00:00", 0)).isEmpty());
    }
}
