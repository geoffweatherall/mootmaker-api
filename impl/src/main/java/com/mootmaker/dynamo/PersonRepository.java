package com.mootmaker.dynamo;

import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import module java.base;

/**
 * People, by id. An instance rather than the static utility it used to be, matching
 * {@link DayRepository} and {@link RoomRepository}: it owns its client and table name, and is
 * constructed once at initialisation so it lands in the SnapStart snapshot.
 *
 * <p>{@link #findByCognitoSub} is on borrowed time. Once {@code custom:personId} is a Cognito claim
 * the caller's id arrives in the token, so the forward lookup costs nothing and this GSI query - and
 * the {@code cognitoSub-index} behind it, the last {@code projection_type = "ALL"} duplicate in the
 * system - both go. The reverse direction, which account deletion needs, becomes a {@code cognitoSubs}
 * list attribute on the Person itself: free to read, because deletion already holds the item.
 */
public final class PersonRepository {

    private static final String COGNITO_SUB_INDEX = "cognitoSub-index";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public PersonRepository(final DynamoDbClient dynamoDbClient, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    /** Deduplicates ids and batches over {@code BatchGetItem}; see {@link RoomRepository#loadByIds}. */
    public Map<String, Person> loadByIds(final Set<String> ids) {
        return BatchGet.byId(dynamoDbClient, tableName, ids).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Person.fromItem(entry.getValue())));
    }

    public Optional<Person> findById(final String id) {
        return Optional.ofNullable(loadByIds(Set.of(id)).get(id));
    }

    public List<Person> listAll() {
        return BatchGet.scan(dynamoDbClient, tableName).stream().map(Person::fromItem).toList();
    }

    public void put(final Person person) {
        BatchGet.put(dynamoDbClient, tableName, person.toItem());
    }

    public void deleteById(final String id) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", AttributeValue.builder().s(id).build()))
                .build());
    }

    /**
     * Looks up the Person linked to a Cognito user via the {@code cognitoSub-index} GSI.
     *
     * <p>Note the consistency hazard this carries, and which the claim removes: a GSI rejects
     * {@code ConsistentRead}, so a Person written moments ago may not be visible here yet.
     */
    public Optional<Person> findByCognitoSub(final String cognitoSub) {
        final List<Map<String, AttributeValue>> items = dynamoDbClient.query(QueryRequest.builder()
                        .tableName(tableName)
                        .indexName(COGNITO_SUB_INDEX)
                        .keyConditionExpression("cognitoSub = :cognitoSub")
                        .expressionAttributeValues(Map.of(":cognitoSub", AttributeValue.builder().s(cognitoSub).build()))
                        .limit(1)
                        .build())
                .items();
        return items.isEmpty() ? Optional.empty() : Optional.of(Person.fromItem(items.getFirst()));
    }
}
