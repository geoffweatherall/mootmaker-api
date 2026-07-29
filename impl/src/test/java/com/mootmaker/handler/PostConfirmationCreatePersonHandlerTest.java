package com.mootmaker.handler;

import com.mootmaker.model.Person;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostConfirmationCreatePersonHandlerTest {

    private static final String USER_POOL_ID = "pool-1";

    private static Map<String, Object> confirmSignUpEvent(final String sub, final String name) {
        final Map<String, Object> userAttributes = new HashMap<>();
        userAttributes.put("sub", sub);
        userAttributes.put("name", name);
        userAttributes.put("email", "ada@example.com");
        final Map<String, Object> request = new HashMap<>();
        request.put("userAttributes", userAttributes);
        final Map<String, Object> event = new HashMap<>();
        event.put("triggerSource", "PostConfirmation_ConfirmSignUp");
        event.put("userPoolId", USER_POOL_ID);
        event.put("request", request);
        return event;
    }

    @Test
    void createsPersonLinkedToTheConfirmedUser() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, new FakeCognitoIdentityProviderClient(), "People");

        final Map<String, Object> event = confirmSignUpEvent("sub-1", "Ada Lovelace");
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result, "Cognito requires the trigger to return the event unmodified");
        assertEquals(1, fakeClient.tables.get("People").size());

        final Person persisted = Person.fromItem(fakeClient.tables.get("People").getFirst());
        assertEquals("Ada Lovelace", persisted.name());
        assertEquals("sub-1", persisted.cognitoSub());
    }

    @Test
    void setsTheDefaultClassToStandardForTheConfirmedUser() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, cognitoClient, "People");

        handler.handleRequest(confirmSignUpEvent("sub-1", "Ada Lovelace"), null);

        assertEquals(1, cognitoClient.updateRequests.size());
        final var updateRequest = cognitoClient.updateRequests.getFirst();
        assertEquals(USER_POOL_ID, updateRequest.userPoolId());
        assertEquals("sub-1", updateRequest.username());
        assertEquals("custom:class", updateRequest.userAttributes().getFirst().name());
        assertEquals("standard", updateRequest.userAttributes().getFirst().value());
    }

    @Test
    void ignoresTriggersOtherThanConfirmSignUp() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, cognitoClient, "People");

        final Map<String, Object> event = confirmSignUpEvent("sub-1", "Ada Lovelace");
        event.put("triggerSource", "PostConfirmation_ConfirmForgotPassword");

        handler.handleRequest(event, null);

        assertTrue(fakeClient.tables.getOrDefault("People", List.of()).isEmpty());
        assertTrue(cognitoClient.updateRequests.isEmpty());
    }

    @Test
    void isIdempotentWhenAPersonAlreadyExistsForThatSub() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("People", new ArrayList<>(List.of(new Person("person-1", "Ada Lovelace", "sub-1").toItem())));
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, new FakeCognitoIdentityProviderClient(), "People");

        handler.handleRequest(confirmSignUpEvent("sub-1", "Ada Lovelace"), null);

        assertEquals(1, fakeClient.tables.get("People").size(), "must not create a duplicate Person on a retried invocation");
    }

    @Test
    void swallowsFailuresInsteadOfThrowing() {
        final DynamoDbClient failingClient = new FakeDynamoDbClient() {
            @Override
            public software.amazon.awssdk.services.dynamodb.model.QueryResponse query(
                    final software.amazon.awssdk.services.dynamodb.model.QueryRequest request) {
                throw new RuntimeException("DynamoDB unavailable");
            }
        };
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(failingClient, new FakeCognitoIdentityProviderClient(), "People");

        final Map<String, Object> event = confirmSignUpEvent("sub-1", "Ada Lovelace");
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result, "must still return the event even when Person creation fails");
    }

    @Test
    void swallowsAClassUpdateFailureInsteadOfThrowing() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.failNextUpdateWith(new RuntimeException("Cognito unavailable"));
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, cognitoClient, "People");

        final Map<String, Object> event = confirmSignUpEvent("sub-1", "Ada Lovelace");
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result, "must still return the event even when the class update fails");
        assertEquals(1, fakeClient.tables.get("People").size(), "Person creation must still have succeeded");
    }
}
