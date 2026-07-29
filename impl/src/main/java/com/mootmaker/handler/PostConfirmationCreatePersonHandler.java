package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/**
 * Cognito PostConfirmation trigger: creates a Person linked to the newly confirmed user via
 * {@code cognitoSub}, so a future account-deletion flow can find and remove it, and sets the new
 * user's {@code custom:class} attribute to {@code "standard"}. The client must never be trusted to
 * set its own class (it could otherwise self-promote to admin), so this is done server-side via
 * the Admin API - reusing this trigger, which already fires exactly once per confirmed sign-up
 * before the user's first sign-in/token, rather than adding a separate {@code PreSignUp} trigger
 * (which has no reliable way to inject an attribute value the way this trigger's Admin API access
 * does). Unlike the AppSync resolver handlers in this package, Cognito requires trigger Lambdas to
 * return the event unmodified, and treats a thrown exception as a failure of the confirm-sign-up
 * call itself even though the account is already confirmed by the time this trigger runs - so
 * failures here are logged and swallowed rather than thrown, to avoid blocking sign-up.
 */
public class PostConfirmationCreatePersonHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostConfirmationCreatePersonHandler.class);
    private static final String TRIGGER_SOURCE_CONFIRM_SIGN_UP = "PostConfirmation_ConfirmSignUp";
    private static final String DEFAULT_CLASS = "standard";

    private final DynamoDbClient dynamoDbClient;
    private final CognitoIdentityProviderClient cognitoClient;
    private final String tableName;

    public PostConfirmationCreatePersonHandler() {
        this(DynamoDbClientProvider.client(), CognitoIdentityProviderClientProvider.client(),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    PostConfirmationCreatePersonHandler(final DynamoDbClient dynamoDbClient, final CognitoIdentityProviderClient cognitoClient,
            final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.cognitoClient = cognitoClient;
        this.tableName = tableName;
    }

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        try {
            createPersonIfNeeded(event);
        } catch (final RuntimeException e) {
            // The name isn't known yet if extracting it from the event is itself what failed;
            // createPersonIfNeeded logs the name-specific outcome for every other failure.
            LOGGER.error("Failed to create Person for confirmed sign-up", e);
        }
        try {
            setDefaultClassIfNeeded(event);
        } catch (final RuntimeException e) {
            LOGGER.error("Failed to set default class for confirmed sign-up", e);
        }
        return event;
    }

    private void createPersonIfNeeded(final Map<String, Object> event) {
        if (!TRIGGER_SOURCE_CONFIRM_SIGN_UP.equals(event.get("triggerSource"))) {
            return;
        }

        final Map<String, Object> request = castToMap(event.get("request"));
        final Map<String, Object> userAttributes = castToMap(request.get("userAttributes"));
        final String cognitoSub = (String) userAttributes.get("sub");
        final String name = (String) userAttributes.get("name");

        try {
            if (PersonRepository.findByCognitoSub(dynamoDbClient, tableName, cognitoSub).isPresent()) {
                LOGGER.info("Person already exists for confirmed sign-up '{}', skipping creation", name);
                return;
            }

            final Person person = new Person(UUID.randomUUID().toString(), name, cognitoSub);
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(person.toItem())
                    .build());

            LOGGER.info("Created Person for confirmed sign-up '{}'", name);
        } catch (final RuntimeException e) {
            LOGGER.error("Failed to create Person for confirmed sign-up '{}'", name, e);
        }
    }

    /**
     * Idempotent (setting the same attribute value twice is harmless), so this runs unconditionally
     * on every confirmation event for this trigger source rather than being gated on whether
     * {@link #createPersonIfNeeded} actually created something this time.
     */
    private void setDefaultClassIfNeeded(final Map<String, Object> event) {
        if (!TRIGGER_SOURCE_CONFIRM_SIGN_UP.equals(event.get("triggerSource"))) {
            return;
        }

        final Map<String, Object> request = castToMap(event.get("request"));
        final Map<String, Object> userAttributes = castToMap(request.get("userAttributes"));
        final String cognitoSub = (String) userAttributes.get("sub");
        // Read from the event rather than an env var: this Lambda's ARN is itself referenced by
        // aws_cognito_user_pool.this's own lambda_config, so an env var built from that same user
        // pool's id would be a circular Terraform dependency. Every Cognito trigger event carries
        // its invoking pool's id at this top-level field regardless.
        final String userPoolId = (String) event.get("userPoolId");

        // Username and sub are the same value in this pool - see UpdatePersonHandler's identical
        // comment on its own AdminUpdateUserAttributes call for why.
        cognitoClient.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                .userPoolId(userPoolId)
                .username(cognitoSub)
                .userAttributes(AttributeType.builder().name("custom:class").value(DEFAULT_CLASS).build())
                .build());
        LOGGER.info("Set default class '{}' for confirmed sign-up (sub '{}')", DEFAULT_CLASS, cognitoSub);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
