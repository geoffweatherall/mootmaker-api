package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Query.myPerson}: looks up the Person linked to the
 * caller's own Cognito account (via {@code identity.sub}, which AppSync's Cognito user-pool
 * authoriser populates from the caller's JWT), rather than trusting a client-supplied id.
 */
public class MyPersonHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public MyPersonHandler() {
        this(DynamoDbClientProvider.client(), System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    MyPersonHandler(final DynamoDbClient dynamoDbClient, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        // One ConsistentRead, and zero lookups to know WHICH person to read - the id is on the token.
        // Null for a machine-to-machine caller, and for a confirmed user whose PostConfirmation trigger
        // failed; both are legitimate, and the schema types this field nullable for exactly that reason.
        return Identity.personId(event)
                .flatMap(new PersonRepository(dynamoDbClient, tableName)::findById)
                .map(Person::toResponseMap)
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
