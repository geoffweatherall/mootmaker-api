package com.mootmaker.dynamo;

import module java.base;

import com.mootmaker.model.DateFormat;
import com.mootmaker.model.Person;
import com.mootmaker.model.TimeFormat;
import com.mootmaker.model.WeekStart;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

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

  /**
   * Writes the whole item, replacing every attribute. Only for callers that genuinely own the
   * entire Person - creation, and {@code database-repair}. Anything changing a subset of fields
   * wants one of the {@code update*} methods below instead; see their shared javadoc for why.
   */
  public void put(final Person person) {
    BatchGet.put(dynamoDbClient, tableName, person.toItem());
  }

  /** Sets a person's name, leaving every other attribute untouched. */
  public Person updateName(final String id, final String name) {
    return update(id, "SET #name = :name", Map.of("#name", "name"), Map.of(":name", string(name)));
  }

  /** Sets a person's admin flag, leaving every other attribute untouched. */
  public Person updateIsAdmin(final String id, final boolean isAdmin) {
    return update(
        id,
        "SET #isAdmin = :isAdmin",
        Map.of("#isAdmin", "isAdmin"),
        Map.of(":isAdmin", AttributeValue.builder().bool(isAdmin).build()));
  }

  /**
   * Sets all three display preferences together, leaving every other attribute untouched. They move
   * as a set because {@code updateMyPreferences} submits them as one, and because {@link Person}'s
   * compact constructor normalises a null preference to its default - so there is no "unset" state
   * to express, and every write is a SET rather than a REMOVE.
   */
  public Person updatePreferences(
      final String id,
      final DateFormat dateFormat,
      final TimeFormat timeFormat,
      final WeekStart weekStart) {
    return update(
        id,
        "SET #dateFormat = :dateFormat, #timeFormat = :timeFormat, #weekStart = :weekStart",
        Map.of(
            "#dateFormat", "dateFormat",
            "#timeFormat", "timeFormat",
            "#weekStart", "weekStart"),
        Map.of(
            ":dateFormat", string(dateFormat.name()),
            ":timeFormat", string(timeFormat.name()),
            ":weekStart", string(weekStart.name())));
  }

  /**
   * Applies an attribute-level {@code UpdateItem}, returning the person as stored afterwards.
   *
   * <p>This exists so a mutation can change the fields it owns without naming the ones it does not.
   * Every one of these used to be a read-modify-{@code PutItem}, which fully replaces the item, so
   * each handler had to rebuild the whole record and carry every unrelated field forward by hand -
   * and forgetting one silently erased it. That is mootmaker-api#71, and it recurred when {@code
   * photoUrl} was added and four separate handlers each needed the same line. An {@code UpdateItem}
   * cannot express that bug: an attribute nobody names is an attribute nobody can lose.
   *
   * <p><b>The condition is not optional.</b> {@code UpdateItem} on a key that does not exist
   * <em>creates</em> the item from whatever the expression sets - which would leave a Person with,
   * say, only an id and an isAdmin flag, and no name. {@code attribute_exists(id)} turns that into
   * a {@link ConditionalCheckFailedException} instead. {@code PutItem} had no equivalent trap,
   * which is exactly why it is worth stating here.
   *
   * <p>Attribute names go through {@code #placeholders} throughout. {@code name} is a DynamoDB
   * reserved word and genuinely requires it; the others do not, today, and are aliased anyway so
   * that adding a field which happens to be reserved cannot quietly break a new expression.
   *
   * @throws ConditionalCheckFailedException if no person with this id exists
   */
  private Person update(
      final String id,
      final String updateExpression,
      final Map<String, String> attributeNames,
      final Map<String, AttributeValue> attributeValues) {
    final UpdateItemResponse response =
        dynamoDbClient.updateItem(
            UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", string(id)))
                .updateExpression(updateExpression)
                .conditionExpression("attribute_exists(id)")
                .expressionAttributeNames(attributeNames)
                .expressionAttributeValues(attributeValues)
                // The stored item is the authority on what the caller should be told, and it costs
                // nothing extra here - so handlers report what was written rather than what they
                // hoped was written.
                .returnValues(ReturnValue.ALL_NEW)
                .build());
    return Person.fromItem(response.attributes());
  }

  private static AttributeValue string(final String value) {
    return AttributeValue.builder().s(value).build();
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
    return createWithNewId(name, null);
  }

  /**
   * As {@link #createWithNewId(String)}, with an avatar photo - see
   * designs/archive/person-avatar-photos.md. {@code photoUrl} is null for every caller except
   * mootmaker-demo-data today.
   */
  public Person createWithNewId(final String name, final String photoUrl) {
    for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
      final Person person =
          new Person(
              IdAllocator.newId(), name, List.of(), List.of(), false, null, null, null, photoUrl);
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
