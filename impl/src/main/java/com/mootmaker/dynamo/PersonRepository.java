package com.mootmaker.dynamo;

import module java.base;

import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

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

  /** Enough to clear the negligible chance of a real collision; see {@link IdAllocator}. */
  private static final int MAX_CREATE_ATTEMPTS = 5;

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

  /**
   * Case- and whitespace-insensitive name lookup, for the createPerson/sign-up duplicate-name guard
   * (mootmaker-api#70) - "willy wombat" collides with " Willy Wombat" as much as an exact match
   * does, since a case/whitespace-only difference is exactly the kind of human error this exists to
   * catch, not a legitimate distinct person. A full scan, not an index - the same "small and
   * slow-moving" whole-table read every other Person listing in this codebase already does (see
   * {@code Query.workspace.people}).
   */
  public Optional<Person> findByNameIgnoringCaseAndWhitespace(final String name) {
    final String normalized = normalize(name);
    return listAll().stream()
        .filter(person -> normalize(person.name()).equals(normalized))
        .findFirst();
  }

  private static String normalize(final String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }

  public void put(final Person person) {
    BatchGet.put(dynamoDbClient, tableName, person.toItem());
  }

  /**
   * Writes exactly this {@code Person}, failing rather than substituting a different id, for a
   * caller whose id is already fixed before this call - e.g. one already written to a Cognito
   * {@code custom:personId} claim, where writing a different id here would silently strand that
   * claim. See {@link IdAllocator}'s javadoc and {@code PostConfirmationCreatePersonHandler}.
   *
   * @throws software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException if a
   *     Person with this id already exists
   */
  public void create(final Person person) {
    dynamoDbClient.putItem(
        PutItemRequest.builder()
            .tableName(tableName)
            .item(person.toItem())
            .conditionExpression("attribute_not_exists(id)")
            .build());
  }

  /**
   * Allocates a fresh id and creates a guest Person with it - no Cognito account, so nothing has
   * claimed the id in advance, and a collision (negligibly likely - see {@link IdAllocator}) can
   * simply be retried with a newly drawn one.
   */
  public Person createWithNewId(final String name) {
    for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
      final Person person = new Person(IdAllocator.newId(), name);
      try {
        create(person);
        return person;
      } catch (final ConditionalCheckFailedException e) {
        if (attempt == MAX_CREATE_ATTEMPTS) {
          throw new IllegalStateException(
              "Could not allocate a person id after " + MAX_CREATE_ATTEMPTS + " attempts", e);
        }
      }
    }
    throw new IllegalStateException("unreachable");
  }

  public void deleteById(final String id) {
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("id", AttributeValue.builder().s(id).build()))
            .build());
  }
}
