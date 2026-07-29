package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.updatePerson}. Allowed for the caller's own
 * linked Person (a self-rename, identified the same way {@link MyPersonHandler} does - matching
 * {@code identity.sub} against the target's {@code cognitoSub}) or, for any person, if the caller
 * is admin (see {@link Identity#isAdmin}).
 *
 * <p>If the target person has a linked Cognito account, this also updates Cognito's own {@code
 * name} attribute to match - otherwise it would go stale relative to {@code Person.name} (Cognito
 * sets it once at sign-up and nothing else ever touches it), and {@code AuthProvider} on the
 * webapp briefly shows that stale name from the ID token on next sign-in, before its {@code
 * myPerson} lookup overrides it with the current one.
 */
public class UpdatePersonHandler implements RequestHandler<Map<String, Object>, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdatePersonHandler.class);

    private final DynamoDbClient dynamoDbClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final String tableName;
    private final String userPoolId;

    public UpdatePersonHandler() {
        this(DynamoDbClientProvider.client(), CognitoIdentityProviderClientProvider.client(),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
                System.getenv("COGNITO_USER_POOL_ID"));
    }

    UpdatePersonHandler(final DynamoDbClient dynamoDbClient, final CognitoIdentityProviderClient cognitoClient,
            final String tableName, final String userPoolId) {
        this.dynamoDbClient = dynamoDbClient;
        this.cognitoClient = cognitoClient;
        this.tableName = tableName;
        this.userPoolId = userPoolId;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final String id = (String) arguments.get("id");
        final Map<String, Object> personInput = castToMap(arguments.get("person"));
        final String name = (String) personInput.get("name");

        final List<String> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(PersonError.NameRequired.name());
        }

        final Map<String, Object> result = new HashMap<>();
        final Optional<Person> current = findById(id);
        if (current.isEmpty()) {
            errors.add(PersonError.PersonNotFound.name());
            result.put("person", null);
            result.put("errors", errors);
            return result;
        }

        final Map<String, Object> identity = castToMap(event.get("identity"));
        final String callerSub = (String) identity.get("sub");
        final boolean isSelf = callerSub != null && callerSub.equals(current.get().cognitoSub());
        if (!isSelf && !Identity.isAdmin(event)) {
            throw new IllegalStateException("Forbidden: can only update your own name unless you are admin");
        }

        if (!errors.isEmpty()) {
            result.put("person", null);
            result.put("errors", errors);
            return result;
        }

        // Carries the existing cognitoSub forward - PutItem fully replaces the item, and
        // PersonInput has no cognitoSub field, so building this from just (id, name) would
        // silently unlink a real user's account from their Cognito login.
        final Person updated = new Person(id, name, current.get().cognitoSub());
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(updated.toItem())
                .build());

        if (updated.cognitoSub() != null) {
            propagateNameToCognito(updated.cognitoSub(), name);
        }

        result.put("person", updated.toResponseMap());
        result.put("errors", errors);
        return result;
    }

    private Optional<Person> findById(final String id) {
        final GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", AttributeValue.builder().s(id).build()))
                .build());
        return response.hasItem() ? Optional.of(Person.fromItem(response.item())) : Optional.empty();
    }

    private void propagateNameToCognito(final String cognitoSub, final String name) {
        try {
            // Username and sub are the same value in this pool: username_attributes = ["email"]
            // (see cognito.tf) makes Cognito auto-generate a UUID Username identical to sub, with
            // email set as an alias - so cognitoSub can be used directly as AdminUpdateUserAttributes'
            // Username without a separate lookup.
            cognitoClient.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                    .userPoolId(userPoolId)
                    .username(cognitoSub)
                    .userAttributes(AttributeType.builder().name("name").value(name).build())
                    .build());
        } catch (final RuntimeException e) {
            // The Person record (the source of truth) has already been updated successfully at
            // this point; Cognito's name is only a display convenience for the brief window before
            // AuthProvider's myPerson lookup resolves, so a failure here shouldn't fail the whole
            // mutation.
            LOGGER.error("Failed to update Cognito name attribute for cognitoSub '{}'", cognitoSub, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
