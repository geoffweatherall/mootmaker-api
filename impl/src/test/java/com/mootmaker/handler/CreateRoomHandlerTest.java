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

class CreateRoomHandlerTest {

    private static Map<String, Object> roomArguments(final String name, final int capacity) {
        final Map<String, Object> roomInput = new HashMap<>();
        roomInput.put("name", name);
        roomInput.put("capacity", capacity);
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("room", roomInput);
        final Map<String, Object> event = new HashMap<>();
        event.put("arguments", arguments);
        event.put("identity", Map.of("sub", "test-user", "claims", Map.of("custom:class", "admin")));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(final CreateRoomHandler handler, final Map<String, Object> event) {
        return (Map<String, Object>) handler.handleRequest(event, null);
    }

    @Test
    void createsRoomAndPersistsIt() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("Conference A", 8));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());

        @SuppressWarnings("unchecked")
        final Map<String, Object> room = (Map<String, Object>) result.get("room");
        assertNotNull(room);
        assertNotNull(room.get("id"));
        assertEquals("Conference A", room.get("name"));
        assertEquals(8, room.get("capacity"));
        assertEquals(1, fakeClient.tables.get("Rooms").size());

        final Room persisted = Room.fromItem(fakeClient.tables.get("Rooms").getFirst());
        assertEquals(room.get("id"), persisted.id());
        assertEquals("Conference A", persisted.name());
        assertEquals(8, persisted.capacity());
    }

    @Test
    void rejectsWhenNameIsMissing() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments(null, 8));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.NameRequired.name()));
        assertNull(result.get("room"));
        assertTrue(fakeClient.tables.getOrDefault("Rooms", List.of()).isEmpty());
    }

    @Test
    void rejectsWhenNameIsBlank() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("   ", 8));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.NameRequired.name()));
        assertNull(result.get("room"));
        assertTrue(fakeClient.tables.getOrDefault("Rooms", List.of()).isEmpty());
    }

    @Test
    void rejectsWhenNameIsEmpty() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("", 8));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.NameRequired.name()));
        assertNull(result.get("room"));
    }

    @Test
    void rejectsWhenCapacityIsZero() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("Conference A", 0));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.CapacityTooLow.name()));
        assertNull(result.get("room"));
        assertTrue(fakeClient.tables.getOrDefault("Rooms", List.of()).isEmpty());
    }

    @Test
    void rejectsWhenCapacityIsOne() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("Conference A", 1));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.CapacityTooLow.name()));
        assertNull(result.get("room"));
    }

    @Test
    void allowsCapacityOfExactlyTwo() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("Conference A", 2));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());
        assertNotNull(result.get("room"));
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> event = roomArguments("Conference A", 8);
        event.remove("identity");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertTrue(fakeClient.tables.getOrDefault("Rooms", List.of()).isEmpty());
    }

    @Test
    void rejectsARequestFromANonAdminUser() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> event = roomArguments("Conference A", 8);
        event.put("identity", Map.of("sub", "test-user", "claims", Map.of("custom:class", "standard")));

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertTrue(fakeClient.tables.getOrDefault("Rooms", List.of()).isEmpty());
    }

    @Test
    void rejectsWithBothErrorsWhenNameIsBlankAndCapacityIsTooLow() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreateRoomHandler handler = new CreateRoomHandler(fakeClient, "Rooms");

        final Map<String, Object> result = invoke(handler, roomArguments("", 1));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(RoomError.NameRequired.name()));
        assertTrue(errors.contains(RoomError.CapacityTooLow.name()));
        assertNull(result.get("room"));
    }
}
