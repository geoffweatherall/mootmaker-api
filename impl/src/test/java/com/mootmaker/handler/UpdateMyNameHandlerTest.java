package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import com.mootmaker.testsupport.FakeCognitoIdentityProviderClient;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;

class UpdateMyNameHandlerTest {

  private static final String USER_POOL_ID = "pool-1";
  private static final String TABLE_NAME = "People";

  private static Map<String, Object> event(final String name, final String callerPersonId) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("name", name);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    if (callerPersonId != null) {
      final Map<String, Object> identity = new HashMap<>();
      identity.put("claims", Map.of("custom:personId", callerPersonId));
      event.put("identity", identity);
    }
    return event;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(
      final UpdateMyNameHandler handler, final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  private static FakeDynamoDbClient clientWithPerson(final Person person) {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(TABLE_NAME, new ArrayList<>(List.of(person.toItem())));
    return fakeClient;
  }

  @Test
  void renamesTheCallerAndSyncsCognitoName() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final UpdateMyNameHandler handler =
        new UpdateMyNameHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("Ada Lovelace", "person-1"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    final Person persisted = Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst());
    assertEquals("Ada Lovelace", persisted.name());
    assertEquals(1, cognitoClient.updateRequests.size());
    assertEquals(
        "name", cognitoClient.updateRequests.getFirst().userAttributes().getFirst().name());
  }

  @Test
  void regressionMootmakerApi71CarriesLinkedAccountsEmailsAndAdminStatusForward() {
    final Person person =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1", "sub-2"),
            List.of("ada@example.com", "ada@work.example.com"),
            true,
            com.mootmaker.model.DateFormat.British,
            com.mootmaker.model.TimeFormat.AmPm);
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final UpdateMyNameHandler handler =
        new UpdateMyNameHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    invoke(handler, event("Ada Lovelace", "person-1"));

    final Person persisted = Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst());
    assertEquals(List.of("sub-1", "sub-2"), persisted.cognitoSubs());
    assertEquals(List.of("ada@example.com", "ada@work.example.com"), persisted.cognitoEmails());
    assertTrue(persisted.isAdmin());
    assertEquals(com.mootmaker.model.DateFormat.British, persisted.dateFormat());
    assertEquals(com.mootmaker.model.TimeFormat.AmPm, persisted.timeFormat());
  }

  @Test
  void returnsNoLinkedPersonWhenTheCallerHasNoLinkedPerson() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final UpdateMyNameHandler handler =
        new UpdateMyNameHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("Ada Lovelace", "no-such-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.NoLinkedPerson.name()));
    assertNull(result.get("person"));
  }

  @Test
  void rejectsABlankName() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final UpdateMyNameHandler handler =
        new UpdateMyNameHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("   ", "person-1"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.NameRequired.name()));
    assertEquals("Ada", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
  }

  @Test
  void personRecordUpdateSucceedsEvenWhenTheCognitoNameSyncFails() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    cognitoClient.failNextUpdateWith(new RuntimeException("Cognito unavailable"));
    final UpdateMyNameHandler handler =
        new UpdateMyNameHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("Ada Lovelace", "person-1"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertEquals(
        "Ada Lovelace", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final UpdateMyNameHandler handler =
        new UpdateMyNameHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> event = event("Ada Lovelace", "person-1");
    event.remove("identity");

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
  }
}
