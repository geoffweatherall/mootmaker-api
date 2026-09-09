package com.mootmaker.handler;

import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.FakeCognitoIdentityProviderClient;
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
        return confirmSignUpEvent(sub, name, null);
    }

    /** A non-null personId is what a RETRIED invocation sees: the claim the first attempt already set. */
    private static Map<String, Object> confirmSignUpEvent(final String sub, final String name, final String personId) {
        final Map<String, Object> userAttributes = new HashMap<>();
        userAttributes.put("sub", sub);
        userAttributes.put("name", name);
        userAttributes.put("email", "ada@example.com");
        if (personId != null) {
            userAttributes.put("custom:personId", personId);
        }
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
        assertEquals(List.of("sub-1"), persisted.cognitoSubs());
    }

    @Test
    void setsTheDefaultClassToStandardForTheConfirmedUser() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, cognitoClient, "People");

        handler.handleRequest(confirmSignUpEvent("sub-1", "Ada Lovelace"), null);

        // Two updates now: the personId claim, then the class.
        assertEquals(2, cognitoClient.updateRequests.size());
        final var updateRequest = cognitoClient.updateRequests.stream()
                .filter(r -> r.userAttributes().stream().anyMatch(a -> a.name().equals("custom:class")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no custom:class update was issued"));
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
    void isIdempotentWhenTheClaimIsAlreadySet() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("People", new ArrayList<>(List.of(new Person("person-1", "Ada Lovelace", "sub-1").toItem())));
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, new FakeCognitoIdentityProviderClient(), "People");

        // The CLAIM is the idempotency marker, not the presence of a Person - which is what let
        // cognitoSub-index be deleted, since "does a Person exist for this sub" was its last reader.
        handler.handleRequest(confirmSignUpEvent("sub-1", "Ada Lovelace", "person-1"), null);

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
        cognitoClient.failUpdateOf("custom:class", new RuntimeException("Cognito unavailable"));
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, cognitoClient, "People");

        final Map<String, Object> event = confirmSignUpEvent("sub-1", "Ada Lovelace");
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result, "must still return the event even when the class update fails");
        assertEquals(1, fakeClient.tables.get("People").size(), "Person creation must still have succeeded");
    }

    /**
     * The other failure, and the reason the claim is written BEFORE the Person.
     *
     * <p>If the claim cannot be set, no Person is created either - so nothing is stranded. The
     * opposite order would leave a Person that nothing can find, now that the index is gone, and
     * CreateMissingPersonsRepair would create a second one it has no way to detect as a duplicate.
     */
    @Test
    void createsNoPersonWhenTheClaimCannotBeSet() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.failUpdateOf("custom:personId", new RuntimeException("Cognito unavailable"));
        final PostConfirmationCreatePersonHandler handler =
                new PostConfirmationCreatePersonHandler(fakeClient, cognitoClient, "People");

        final Map<String, Object> event = confirmSignUpEvent("sub-1", "Ada Lovelace");
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result, "sign-up must not be blocked by this trigger failing");
        assertTrue(fakeClient.tables.getOrDefault("People", List.of()).isEmpty(),
                "a Person with no claim would be unreachable and would be duplicated by the repair");
    }
}
