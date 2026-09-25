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

class RenamePersonHandlerTest {

  private static final String USER_POOL_ID = "pool-1";
  private static final String TABLE_NAME = "People";

  private static Map<String, Object> event(
      final String id, final String name, final boolean admin) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", id);
    arguments.put("name", name);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    final Map<String, Object> identity = new HashMap<>();
    identity.put(
        "claims", admin ? Map.of("custom:class", "admin") : Map.of("custom:class", "standard"));
    event.put("identity", identity);
    return event;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(
      final RenamePersonHandler handler, final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  private static FakeDynamoDbClient clientWithPerson(final Person person) {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(TABLE_NAME, new ArrayList<>(List.of(person.toItem())));
    return fakeClient;
  }

  @Test
  void adminCanRenameSomeoneElsesPersonAndCognitoNameSyncs() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("person-1", "Ada Lovelace", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertEquals(
        "Ada Lovelace", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
    assertEquals(1, cognitoClient.updateRequests.size());
  }

  @Test
  void adminRenamingAGuestWithNoCognitoAccountDoesNotCallCognito() {
    final Person guest = new Person("guest-1", "Guest");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(guest);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("guest-1", "Guest Renamed", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertTrue(cognitoClient.updateRequests.isEmpty());
  }

  @Test
  void regressionMootmakerApi71CarriesLinkedAccountsEmailsAndAdminStatusForward() {
    final Person person =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1"),
            List.of("ada@example.com"),
            true,
            com.mootmaker.model.DateFormat.Usa,
            com.mootmaker.model.TimeFormat.AmPm);
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    invoke(handler, event("person-1", "Ada Lovelace", true));

    final Person persisted = Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst());
    assertEquals(List.of("ada@example.com"), persisted.cognitoEmails());
    assertTrue(persisted.isAdmin());
    assertEquals(com.mootmaker.model.DateFormat.Usa, persisted.dateFormat());
    assertEquals(com.mootmaker.model.TimeFormat.AmPm, persisted.timeFormat());
  }

  @Test
  void rejectsANonAdminCaller() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> event = event("person-1", "Ada Lovelace", false);

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    assertEquals("Ada", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
  }

  @Test
  void returnsPersonNotFoundForAMissingId() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("missing", "New Name", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.PersonNotFound.name()));
    assertNull(result.get("person"));
  }

  @Test
  void rejectsABlankName() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("person-1", "   ", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.NameRequired.name()));
    assertNull(result.get("person"));
  }

  @Test
  void personRecordUpdateSucceedsEvenWhenTheCognitoNameSyncFails() {
    final Person person = new Person("person-1", "Ada", "cognito-sub-123", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    cognitoClient.failNextUpdateWith(new RuntimeException("Cognito unavailable"));
    final RenamePersonHandler handler =
        new RenamePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("person-1", "Ada Lovelace", true));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertEquals(
        "Ada Lovelace", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
  }
}
