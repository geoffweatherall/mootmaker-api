package com.mootmaker.handler;

import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePersonHandlerTest {

    private static final String USER_POOL_ID = "pool-1";
    private static final String TABLE_NAME = "People";

    private static Map<String, Object> updateArguments(final String id, final String name, final String callerSub,
            final String callerClass) {
        final Map<String, Object> personInput = new HashMap<>();
        personInput.put("name", name);
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("id", id);
        arguments.put("person", personInput);
        final Map<String, Object> event = new HashMap<>();
        event.put("arguments", arguments);
        final Map<String, Object> identity = new HashMap<>();
        identity.put("sub", callerSub);
        if (callerClass != null) {
            identity.put("claims", Map.of("custom:class", callerClass));
        }
        event.put("identity", identity);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(final UpdatePersonHandler handler, final Map<String, Object> event) {
        return (Map<String, Object>) handler.handleRequest(event, null);
    }

    private static FakeDynamoDbClient clientWithPerson(final String id, final String name, final String cognitoSub) {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put(TABLE_NAME, new ArrayList<>(List.of(new Person(id, name, cognitoSub).toItem())));
        return fakeClient;
    }

    @Test
    void aUserCanRenameThemselvesAndTheirCognitoNameIsUpdatedToMatch() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("person-1", "Ada", "cognito-sub-123");
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> result =
                invoke(handler, updateArguments("person-1", "Ada Lovelace", "cognito-sub-123", "standard"));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());
        @SuppressWarnings("unchecked")
        final Map<String, Object> person = (Map<String, Object>) result.get("person");
        assertEquals("Ada Lovelace", person.get("name"));

        final Person persisted = Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst());
        assertEquals("Ada Lovelace", persisted.name());
        assertEquals("cognito-sub-123", persisted.cognitoSub(), "cognitoSub must survive the rename");

        assertEquals(1, cognitoClient.updateRequests.size());
        final var updateRequest = cognitoClient.updateRequests.getFirst();
        assertEquals(USER_POOL_ID, updateRequest.userPoolId());
        assertEquals("cognito-sub-123", updateRequest.username());
        assertEquals("name", updateRequest.userAttributes().getFirst().name());
        assertEquals("Ada Lovelace", updateRequest.userAttributes().getFirst().value());
    }

    @Test
    void adminCanRenameSomeoneElsesPerson() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("person-1", "Ada", "cognito-sub-123");
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> result =
                invoke(handler, updateArguments("person-1", "Ada Lovelace", "admin-sub", "admin"));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());
        assertEquals("Ada Lovelace", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
        assertEquals(1, cognitoClient.updateRequests.size());
    }

    @Test
    void adminRenamingAGuestWithNoCognitoAccountDoesNotCallCognito() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("guest-1", "Guest", null);
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> result = invoke(handler, updateArguments("guest-1", "Guest Renamed", "admin-sub", "admin"));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());
        assertEquals("Guest Renamed", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
        assertTrue(cognitoClient.updateRequests.isEmpty());
    }

    @Test
    void rejectsANonAdminEditingSomeoneElsesPerson() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("person-1", "Ada", "cognito-sub-123");
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> event = updateArguments("person-1", "Ada Lovelace", "someone-elses-sub", "standard");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
        assertEquals("Ada", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
        assertTrue(cognitoClient.updateRequests.isEmpty());
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("person-1", "Ada", "cognito-sub-123");
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> event = updateArguments("person-1", "Ada Lovelace", "cognito-sub-123", "standard");
        event.remove("identity");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    }

    @Test
    void returnsPersonNotFoundForAMissingId() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> result = invoke(handler, updateArguments("missing", "New Name", "admin-sub", "admin"));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(PersonError.PersonNotFound.name()));
        assertNull(result.get("person"));
    }

    @Test
    void rejectsABlankName() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("person-1", "Ada", "cognito-sub-123");
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> result = invoke(handler, updateArguments("person-1", "   ", "cognito-sub-123", "standard"));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.contains(PersonError.NameRequired.name()));
        assertNull(result.get("person"));
        assertEquals("Ada", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
    }

    @Test
    void personRecordUpdateSucceedsEvenWhenTheCognitoNameSyncFails() {
        final FakeDynamoDbClient dynamoDbClient = clientWithPerson("person-1", "Ada", "cognito-sub-123");
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.failNextUpdateWith(new RuntimeException("Cognito unavailable"));
        final UpdatePersonHandler handler = new UpdatePersonHandler(dynamoDbClient, cognitoClient, TABLE_NAME, USER_POOL_ID);

        final Map<String, Object> result =
                invoke(handler, updateArguments("person-1", "Ada Lovelace", "cognito-sub-123", "standard"));

        @SuppressWarnings("unchecked")
        final List<String> errors = (List<String>) result.get("errors");
        assertTrue(errors.isEmpty());
        assertEquals("Ada Lovelace", Person.fromItem(dynamoDbClient.tables.get(TABLE_NAME).getFirst()).name());
    }
}
