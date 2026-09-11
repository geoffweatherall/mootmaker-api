package com.mootmaker.dynamo;

import module java.base;

import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

/**
 * People, by id. An instance rather than the static utility it used to be, matching {@link
 * DayRepository} and {@link RoomRepository}: it owns its client and table name, and is constructed
 * once at initialisation so it lands in the SnapStart snapshot.
 *
 * <p>There is no lookup by Cognito sub any more. The caller's id arrives on the token as the {@code
 * custom:personId} claim, so the forward direction costs nothing - and that removed the last reader
 * of {@code cognitoSub-index}, the final {@code projection_type = "ALL"} duplicate in the system.
 * The reverse direction, which only account deletion needs, is a {@code cognitoSubs} list on the
 * Person itself: free to read, because deletion already holds the item.
 */
public final class PersonRepository {

  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public PersonRepository(final DynamoDbClient dynamoDbClient, final String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  /**
   * Deduplicates ids and batches over {@code BatchGetItem}; see {@link RoomRepository#loadByIds}.
   */
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
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("id", AttributeValue.builder().s(id).build()))
            .build());
  }
}
