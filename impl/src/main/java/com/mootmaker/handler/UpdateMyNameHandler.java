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
 * AppSync direct-Lambda resolver for {@code Mutation.updateMyName}. Self-only - sets the caller's
 * own name, resolved from {@code custom:personId} the same way {@code MyPersonHandler} does. No id
 * argument, no admin override - deliberately unlike {@link RenamePersonHandler}, which an admin
 * uses on someone else's Person. Mirrors {@code updateMyPreferences}'s self-only shape.
 *
 * <p>If the caller has a linked Cognito account, this also updates Cognito's own {@code name}
 * attribute to match - otherwise it would go stale relative to {@code Person.name} (Cognito sets it
 * once at sign-up and nothing else ever touches it), and {@code AuthProvider} on the webapp briefly
 * shows that stale name from the ID token on next sign-in, before its {@code myPerson} lookup
 * overrides it with the current one. A propagation failure is logged and swallowed, not surfaced -
 * Cognito's {@code name} is only a display convenience for that brief window, unlike {@code
 * setPersonAdmin}'s {@code custom:class}, which is the caller's actual authorization.
 */
public class UpdateMyNameHandler implements RequestHandler<Map<String, Object>, Object> {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpdateMyNameHandler.class);

  private final CognitoIdentityProviderClient cognitoClient;
  private final PersonRepository people;
  private final String userPoolId;

  public UpdateMyNameHandler() {
    this(
        DynamoDbClientProvider.client(),
        CognitoIdentityProviderClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        System.getenv("COGNITO_USER_POOL_ID"));
  }

  UpdateMyNameHandler(
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
    Identity.requireAuthenticated(event);

    final Optional<Person> current = Identity.personId(event).flatMap(people::findById);
    final Map<String, Object> result = new HashMap<>();
    if (current.isEmpty()) {
      result.put("person", null);
      result.put("errors", List.of(PersonError.NoLinkedPerson.name()));
      return result;
    }

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String name = (String) arguments.get("name");
    if (name == null || name.isBlank()) {
      result.put("person", null);
      result.put("errors", List.of(PersonError.NameRequired.name()));
      return result;
    }

    // Carries every field this mutation doesn't own forward - PutItem fully replaces the item, so
    // building this from just (id, name) would unlink the caller's Cognito login, lose their
    // linked emails, and demote them. See mootmaker-api#71 for what forgetting this looks like.
    final Person updated =
        new Person(
            current.get().id(),
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
    result.put("errors", List.of());
    return result;
  }

  private void propagateNameToCognito(final String cognitoSub, final String name) {
    try {
      // Username and sub are the same value in this pool - see RenamePersonHandler's identical
      // comment on its own AdminUpdateUserAttributes call for why.
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
}
