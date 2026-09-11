package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.createPerson}. Admin only - see {@link
 * Identity#requireAdmin}.
 */
public class CreatePersonHandler implements RequestHandler<Map<String, Object>, Object> {

  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public CreatePersonHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
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

    // Now returns a result type rather than a bare Person, so it can carry validation errors the
    // way every other mutation does - and the name rule that was silently absent is now stated.
    final Map<String, Object> result = new HashMap<>();
    if (name == null || name.isBlank()) {
      result.put("person", null);
      result.put("errors", List.of(PersonError.NameRequired.name()));
      result.put("people", allPeople());
      return result;
    }

    final Person person = new Person(UUID.randomUUID().toString(), name);
    dynamoDbClient.putItem(
        PutItemRequest.builder().tableName(tableName).item(person.toItem()).build());

    result.put("person", person.toResponseMap());
    result.put("errors", List.of());
    // The whole collection, on success and on failure alike. A normalising client cache updates an
    // entity by id everywhere it is referenced, but returning one person does NOT add it to a
    // cached list - that is a separate cache field - so the list is what makes this mutation
    // self-sufficient.
    result.put("people", allPeople());
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }

  /** Every peopl after the change - see the schema's note on Person mutation results. */
  private List<Map<String, Object>> allPeople() {
    return new PersonRepository(dynamoDbClient, tableName)
        .listAll().stream().map(Person::toResponseMap).toList();
  }
}
