package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.Person;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;

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
  private static Map<String, Object> invoke(
      final CreatePersonHandler handler, final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @Test
  void createsPersonAndPersistsIt() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final CreatePersonHandler handler = new CreatePersonHandler(fakeClient, "People");

    final Map<String, Object> result = invoke(handler, personArguments("Ada Lovelace"));

    // createPerson now returns a result type rather than a bare Person, so it can carry errors
    // the way every other mutation does.
    @SuppressWarnings("unchecked")
    final Map<String, Object> person = (Map<String, Object>) result.get("person");
    assertNotNull(person.get("id"));
    assertEquals("Ada Lovelace", person.get("name"));
    assertEquals(List.of(), result.get("errors"));
    assertEquals(1, fakeClient.tables.get("People").size());

    // The whole collection comes back too, so a client can replace its cached list wholesale.
    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> people = (List<Map<String, Object>>) result.get("people");
    assertEquals(1, people.size());

    final Person persisted = Person.fromItem(fakeClient.tables.get("People").getFirst());
    assertEquals(person.get("id"), persisted.id());
    assertEquals("Ada Lovelace", persisted.name());
  }

  @Test
  void rejectsABlankName() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final CreatePersonHandler handler = new CreatePersonHandler(fakeClient, "People");

    final Map<String, Object> result = invoke(handler, personArguments("   "));

    assertNull(result.get("person"));
    assertEquals(List.of("NameRequired"), result.get("errors"));
    assertTrue(
        fakeClient.tables.getOrDefault("People", List.of()).isEmpty(),
        "a rejected person must not be written");
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
