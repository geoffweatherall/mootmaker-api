package com.mootmaker.handler;

import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.model.Person;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MyPersonHandlerTest {

    private static Map<String, Object> authenticatedEvent(final String sub, final String personId) {
        return Map.of("identity", Map.of("sub", sub, "claims", Map.of("custom:personId", personId)));
    }

    @Test
    void returnsThePersonLinkedToTheCallersCognitoAccount() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("People", new ArrayList<>(List.of(
                new Person("person-1", "Ada Lovelace", "sub-1").toItem(),
                new Person("person-2", "Alan Turing", "sub-2").toItem())));
        final MyPersonHandler handler = new MyPersonHandler(fakeClient, "People");

        final Object result = handler.handleRequest(authenticatedEvent("sub-1", "person-1"), null);

        @SuppressWarnings("unchecked")
        final Map<String, Object> person = (Map<String, Object>) result;
        assertEquals("person-1", person.get("id"));
        assertEquals("Ada Lovelace", person.get("name"));
    }

    @Test
    void returnsNullWhenNoPersonIsLinkedToTheCallersAccount() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final MyPersonHandler handler = new MyPersonHandler(fakeClient, "People");

        final Object result = handler.handleRequest(authenticatedEvent("sub-1", "person-1"), null);

        assertNull(result);
    }

    @Test
    void returnsNullWhenTheTokenCarriesNoPersonIdClaim() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("People", new ArrayList<>(List.of(
                new Person("person-1", "Ada Lovelace", "sub-1").toItem())));
        final MyPersonHandler handler = new MyPersonHandler(fakeClient, "People");

        // A machine-to-machine client_credentials token has no user behind it, so it can never carry
        // a custom attribute - and a confirmed user whose PostConfirmation trigger failed has none
        // either. Both must resolve to null rather than throwing; the schema types myPerson nullable
        // for exactly this.
        final Object result = handler.handleRequest(Map.of("identity", Map.of("sub", "sub-1")), null);

        assertNull(result);
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final MyPersonHandler handler = new MyPersonHandler(fakeClient, "People");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));
    }
}
