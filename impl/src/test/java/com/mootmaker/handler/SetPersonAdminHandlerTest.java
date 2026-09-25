package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import com.mootmaker.testsupport.FakeCognitoIdentityProviderClient;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;

class SetPersonAdminHandlerTest {

  private static final String USER_POOL_ID = "pool-1";
  private static final String TABLE_NAME = "People";

  private static Map<String, Object> event(
      final String id, final boolean isAdmin, final String callerPersonId) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", id);
    arguments.put("isAdmin", isAdmin);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    final Map<String, Object> claims = new HashMap<>();
    claims.put("custom:class", "admin");
    if (callerPersonId != null) {
      claims.put("custom:personId", callerPersonId);
    }
    event.put("identity", Map.of("claims", claims));
    return event;
  }

  private static Map<String, Object> eventAsNonAdmin(final String id, final boolean isAdmin) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", id);
    arguments.put("isAdmin", isAdmin);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    event.put("identity", Map.of("claims", Map.of("custom:class", "standard")));
    return event;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(
      final SetPersonAdminHandler handler, final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  private static FakeDynamoDbClient clientWithPerson(final Person person) {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(TABLE_NAME, new ArrayList<>(List.of(person.toItem())));
    return fakeClient;
  }

  @Test
  void grantsAdminAndSyncsCustomClassToEveryLinkedAccount() {
    final Person person =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1", "sub-2"),
            List.of("ada@example.com", "ada@work.example.com"),
            false,
            null,
            null,
            null);
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("person-1", true, "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertEquals(false, result.get("cognitoSyncFailed"));
    assertTrue(Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).isAdmin());
    assertEquals(2, cognitoClient.updateRequests.size());
    assertEquals(
        "custom:class", cognitoClient.updateRequests.getFirst().userAttributes().getFirst().name());
    assertEquals(
        "admin", cognitoClient.updateRequests.getFirst().userAttributes().getFirst().value());
  }

  @Test
  void revokesAdminFromSomeoneElse() {
    final Person person =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1"),
            List.of("ada@example.com"),
            true,
            null,
            null,
            null);
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("person-1", false, "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertFalse(Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).isAdmin());
    assertEquals(
        "standard", cognitoClient.updateRequests.getFirst().userAttributes().getFirst().value());
  }

  @Test
  void refusesToGrantAdminToAGuestWithNoLinkedAccount() {
    final Person guest = new Person("guest-1", "Guest");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(guest);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("guest-1", true, "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.NoLinkedAccount.name()));
    assertFalse(Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).isAdmin());
    assertTrue(cognitoClient.updateRequests.isEmpty());
  }

  @Test
  void refusesToLetAnAdminRevokeTheirOwnAdminAccess() {
    final Person self = new Person("admin-1", "Grace", "sub-1", "grace@example.com");
    final Person selfWithAdmin =
        new Person(
            self.id(),
            self.name(),
            self.cognitoSubs(),
            self.cognitoEmails(),
            true,
            null,
            null,
            null);
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(selfWithAdmin);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("admin-1", false, "admin-1"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.CannotRevokeOwnAdminAccess.name()));
    assertTrue(Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).isAdmin());
    assertTrue(cognitoClient.updateRequests.isEmpty());
  }

  @Test
  void grantingAdminToSomeoneElseIsUnaffectedByTheSelfGuard() {
    final Person person = new Person("person-1", "Ada", "sub-1", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    // The caller (admin-1) grants admin to a DIFFERENT person - the self-guard must not fire.
    final Map<String, Object> result = invoke(handler, event("person-1", true, "admin-1"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
  }

  @Test
  void reportsCognitoSyncFailedWhenTheCustomClassSyncFailsButTheGrantStillPersists() {
    final Person person = new Person("person-1", "Ada", "sub-1", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    cognitoClient.failUpdateOf("custom:class", new RuntimeException("Cognito unavailable"));
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("person-1", true, "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty(), "a sync failure is a partial success, never a rejection");
    assertEquals(true, result.get("cognitoSyncFailed"));
    assertTrue(
        Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).isAdmin(),
        "the DynamoDB write must still have happened");
  }

  @Test
  void returnsPersonNotFoundForAMissingId() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> result = invoke(handler, event("missing", true, "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.PersonNotFound.name()));
    assertNull(result.get("person"));
  }

  @Test
  void rejectsANonAdminCaller() {
    final Person person = new Person("person-1", "Ada", "sub-1", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(person);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final SetPersonAdminHandler handler =
        new SetPersonAdminHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

    final Map<String, Object> event = eventAsNonAdmin("person-1", true);

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    assertFalse(Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).isAdmin());
  }
}
