package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.DateFormat;
import com.mootmaker.model.Person;
import com.mootmaker.model.PreferencesError;
import com.mootmaker.model.TimeFormat;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.updateMyPreferences}: sets the caller's own
 * date/time display preferences.
 *
 * <p>Self-only, with no admin override - deliberately unlike {@link UpdatePersonHandler}, which an
 * admin may use on someone else's Person to rename them. A personal display preference is not
 * profile data an admin has any business setting on someone else's behalf, so the target is always
 * the Person linked to {@code identity.sub} and there is no id argument to get wrong.
 *
 * <p>Both formats are non-null in {@code PreferencesInput}, so AppSync rejects a missing or null
 * one before this runs: the mutation replaces the whole preference pair rather than patching one
 * of them, and the only failure left to report is having no linked Person at all.
 *
 * <p>Purely presentational. This does not affect the ISO-8601 format the API uses for every
 * date/time it accepts and returns.
 */
public class UpdateMyPreferencesHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public UpdateMyPreferencesHandler() {
        this(DynamoDbClientProvider.client(), System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    UpdateMyPreferencesHandler(final DynamoDbClient dynamoDbClient, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final Map<String, Object> identity = castToMap(event.get("identity"));
        final String callerSub = (String) identity.get("sub");

        final Map<String, Object> result = new HashMap<>();
        final Optional<Person> current = PersonRepository.findByCognitoSub(dynamoDbClient, tableName, callerSub);
        if (current.isEmpty()) {
            result.put("person", null);
            result.put("errors", List.of(PreferencesError.NoLinkedPerson.name()));
            return result;
        }

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final Map<String, Object> preferences = castToMap(arguments.get("preferences"));
        final DateFormat dateFormat = DateFormat.valueOf((String) preferences.get("dateFormat"));
        final TimeFormat timeFormat = TimeFormat.valueOf((String) preferences.get("timeFormat"));

        // Carries name and cognitoSub forward - PutItem fully replaces the item, so building this
        // from the preferences alone would wipe the caller's name and unlink their Cognito login.
        // The mirror image of UpdatePersonHandler's care in the other direction.
        final Person updated = new Person(
                current.get().id(), current.get().name(), current.get().cognitoSub(), dateFormat, timeFormat);
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(updated.toItem())
                .build());

        result.put("person", updated.toResponseMap());
        result.put("errors", List.of());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
