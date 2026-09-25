package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.renamePerson}. Admin only - see {@link
 * Identity#requireAdmin}. The admin-invoked counterpart to {@link UpdateMyNameHandler}'s self-only
 * rename - split into two mutations rather than one self-or-admin mutation (the old {@code
 * updatePerson}) so a mutation that can rename anyone is named, and findable, as exactly that.
 *
 * <p>If the target has a linked Cognito account, this also updates Cognito's own {@code name}
 * attribute to match - otherwise it would go stale relative to {@code Person.name}. A propagation
 * failure is logged and swallowed, not surfaced, same reasoning as {@link UpdateMyNameHandler}.
 */
public class RenamePersonHandler implements RequestHandler<Map<String, Object>, Object> {

  private static final Logger LOGGER = LoggerFactory.getLogger(RenamePersonHandler.class);

  private final CognitoIdentityProviderClient cognitoClient;
  private final PersonRepository people;
  private final String userPoolId;

  public RenamePersonHandler() {
    this(
        DynamoDbClientProvider.client(),
        CognitoIdentityProviderClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        System.getenv("COGNITO_USER_POOL_ID"));
  }

  RenamePersonHandler(
      final DynamoDbClient dynamoDbClient,
      final CognitoIdentityProviderClient cognitoClient,
      final String tableName,
      final String userPoolId) {
    this.cognitoClient = cognitoClient;
    this.people = new PersonRepository(dynamoDbClient, tableName);
    this.userPoolId = userPoolId;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAdmin(event);

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String id = (String) arguments.get("id");
    final String name = (String) arguments.get("name");

    final List<String> errors = new ArrayList<>();
    if (name == null || name.isBlank()) {
      errors.add(PersonError.NameRequired.name());
    }

    final Optional<Person> current = people.findById(id);
    if (current.isEmpty()) {
      errors.add(PersonError.PersonNotFound.name());
    }

    final Map<String, Object> result = new HashMap<>();
    if (!errors.isEmpty()) {
      result.put("person", null);
      result.put("people", allPeople());
      result.put("errors", errors);
      return result;
    }

    // Carries every field this mutation doesn't own forward - PutItem fully replaces the item, so
    // building this from just (id, name) would unlink the target's Cognito login, lose their
    // linked emails, and demote them. See mootmaker-api#71 for what forgetting this looks like.
    final Person updated =
        new Person(
            id,
            name,
            current.get().cognitoSubs(),
            current.get().cognitoEmails(),
            current.get().isAdmin(),
            current.get().dateFormat(),
            current.get().timeFormat());
    people.put(updated);

    for (final String cognitoSub : updated.cognitoSubs()) {
      propagateNameToCognito(cognitoSub, name);
    }

    result.put("person", updated.toResponseMap());
    result.put("people", allPeople());
    result.put("errors", errors);
    return result;
  }

  private void propagateNameToCognito(final String cognitoSub, final String name) {
    try {
      // Username and sub are the same value in this pool: username_attributes = ["email"] (see
      // cognito.tf) makes Cognito auto-generate a UUID Username identical to sub, with email set
      // as an alias - so cognitoSub can be used directly as AdminUpdateUserAttributes' Username
      // without a separate lookup.
      cognitoClient.adminUpdateUserAttributes(
          AdminUpdateUserAttributesRequest.builder()
              .userPoolId(userPoolId)
              .username(cognitoSub)
              .userAttributes(AttributeType.builder().name("name").value(name).build())
              .build());
    } catch (final RuntimeException e) {
      LOGGER.error("Failed to update Cognito name attribute for cognitoSub '{}'", cognitoSub, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }

  /** Every person after the change - see the schema's note on Person mutation results. */
  private List<Map<String, Object>> allPeople() {
    return people.listAll().stream().map(Person::toResponseMap).toList();
  }
}
