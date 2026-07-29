package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/** AppSync direct-Lambda resolver for {@code Mutation.createPerson}. Admin only - see {@link Identity#requireAdmin}. */
public class CreatePersonHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public CreatePersonHandler() {
        this(DynamoDbClientProvider.client(), System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    CreatePersonHandler(final DynamoDbClient dynamoDbClient, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAdmin(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final Map<String, Object> personInput = castToMap(arguments.get("person"));

        final String name = (String) personInput.get("name");
        final Person person = new Person(UUID.randomUUID().toString(), name);

        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(person.toItem())
                .build());

        return person.toResponseMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
