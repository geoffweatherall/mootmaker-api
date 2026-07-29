package com.mootmaker.handler;

import com.mootmaker.model.Person;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreatePersonHandlerTest {

    private static Map<String, Object> personArguments(final String name) {
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("person", Map.of("name", name));
        final Map<String, Object> event = new HashMap<>();
        event.put("arguments", arguments);
        event.put("identity", Map.of("sub", "test-user", "claims", Map.of("custom:class", "admin")));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(final CreatePersonHandler handler, final Map<String, Object> event) {
        return (Map<String, Object>) handler.handleRequest(event, null);
    }

    @Test
    void createsPersonAndPersistsIt() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreatePersonHandler handler = new CreatePersonHandler(fakeClient, "People");

        final Map<String, Object> result = invoke(handler, personArguments("Ada Lovelace"));

        assertNotNull(result.get("id"));
        assertEquals("Ada Lovelace", result.get("name"));
        assertEquals(1, fakeClient.tables.get("People").size());

        final Person persisted = Person.fromItem(fakeClient.tables.get("People").getFirst());
        assertEquals(result.get("id"), persisted.id());
        assertEquals("Ada Lovelace", persisted.name());
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreatePersonHandler handler = new CreatePersonHandler(fakeClient, "People");

        final Map<String, Object> event = personArguments("Ada Lovelace");
        event.remove("identity");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertTrue(fakeClient.tables.getOrDefault("People", List.of()).isEmpty());
    }

    @Test
    void rejectsARequestFromANonAdminUser() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CreatePersonHandler handler = new CreatePersonHandler(fakeClient, "People");

        final Map<String, Object> event = personArguments("Ada Lovelace");
        event.put("identity", Map.of("sub", "test-user", "claims", Map.of("custom:class", "standard")));

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertTrue(fakeClient.tables.getOrDefault("People", List.of()).isEmpty());
    }
}
