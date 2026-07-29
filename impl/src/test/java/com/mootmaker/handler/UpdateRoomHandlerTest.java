package com.mootmaker.handler;

import com.mootmaker.model.Room;
import com.mootmaker.model.RoomError;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateRoomHandlerTest {

    private static Map<String, Object> updateArguments(final String id, final String name, final int capacity) {
        final Map<String, Object> roomInput = new HashMap<>();
        roomInput.put("name", name);
        roomInput.put("capacity", capacity);
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("id", id);
        arguments.put("room", roomInput);
        final Map<String, Object> event = new HashMap<>();
        event.put("arguments", arguments);
        event.put("identity", Map.of("sub", "test-user", "claims", Map.of("custom:class", "admin")));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(final UpdateRoomHandler handler, final Map<String, Object> event) {
        return (Map<String, Object>) handler.handleRequest(event, null);
    }

    private static FakeDynamoDbClient clientWithRoom(final String id, final String name, final int capacity) {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("Rooms", new ArrayList<>(List.of(new Room(id, name, capacity).toItem())));
        return fakeClient;
    }

    @Test
    void updatesAnExistingRoomsNameAndCapacity() {
        final FakeDynamoDbClient fakeClient = clientWithRoom("room-1", "Conference A", 8);
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, updateArguments("room-1", "Conference B", 12));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());

        @SuppressWarnings("unchecked")
        final Map<String, Object> room = (Map<String, Object>) result.get("room");
        assertEquals("room-1", room.get("id"));
        assertEquals("Conference B", room.get("name"));
        assertEquals(12, room.get("capacity"));

        assertEquals(1, fakeClient.tables.get("Rooms").size());
        final Room persisted = Room.fromItem(fakeClient.tables.get("Rooms").getFirst());
        assertEquals("Conference B", persisted.name());
        assertEquals(12, persisted.capacity());
    }

    @Test
    void rejectsWhenRoomDoesNotExist() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, updateArguments("missing-room", "Conference B", 12));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.RoomNotFound.name()));
        assertNull(result.get("room"));
    }

    @Test
    void rejectsWhenNameIsBlank() {
        final FakeDynamoDbClient fakeClient = clientWithRoom("room-1", "Conference A", 8);
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, updateArguments("room-1", "   ", 8));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.NameRequired.name()));
        assertNull(result.get("room"));
        // Unchanged.
        assertEquals("Conference A", Room.fromItem(fakeClient.tables.get("Rooms").getFirst()).name());
    }

    @Test
    void rejectsWhenCapacityIsTooLow() {
        final FakeDynamoDbClient fakeClient = clientWithRoom("room-1", "Conference A", 8);
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, updateArguments("room-1", "Conference A", 1));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.CapacityTooLow.name()));
        assertNull(result.get("room"));
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient fakeClient = clientWithRoom("room-1", "Conference A", 8);
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> event = updateArguments("room-1", "Conference B", 12);
        event.remove("identity");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertEquals("Conference A", Room.fromItem(fakeClient.tables.get("Rooms").getFirst()).name());
    }

    @Test
    void rejectsARequestFromANonAdminUser() {
        final FakeDynamoDbClient fakeClient = clientWithRoom("room-1", "Conference A", 8);
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> event = updateArguments("room-1", "Conference B", 12);
        event.put("identity", Map.of("sub", "test-user", "claims", Map.of("custom:class", "standard")));

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertEquals("Conference A", Room.fromItem(fakeClient.tables.get("Rooms").getFirst()).name());
    }

    @Test
    void roomIdIsUnchangedByAnUpdate() {
        final FakeDynamoDbClient fakeClient = clientWithRoom("room-1", "Conference A", 8);
        final UpdateRoomHandler handler = new UpdateRoomHandler(fakeClient, "Rooms");

        invoke(handler, updateArguments("room-1", "Conference B", 12));

        assertNotNull(fakeClient.tables.get("Rooms").getFirst().get("id"));
        assertEquals("room-1", fakeClient.tables.get("Rooms").getFirst().get("id").s());
    }
}
