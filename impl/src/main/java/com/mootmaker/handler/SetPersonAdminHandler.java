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
 * AppSync direct-Lambda resolver for {@code Mutation.setPersonAdmin}. Admin only - see {@link
 * Identity#requireAdmin}. The only mutation that ever changes {@code Person.isAdmin} or the
 * underlying Cognito {@code custom:class} attribute that actually gates {@link Identity#isAdmin}.
 *
 * <p><b>Write order: DynamoDB first, Cognito second.</b> Mirrors {@link RenamePersonHandler}'s
 * order for name propagation, but here the direction is also the secure one, not just consistent.
 * If the Cognito call fails after the DynamoDB write succeeds, the failure mode is "the Persons
 * screen shows them as admin but their token doesn't grant it yet" - fail-closed, reported via
 * {@code cognitoSyncFailed} below. The reverse order would risk a token silently carrying real
 * admin access the DynamoDB record (and therefore the admin UI) doesn't yet reflect - fail-open,
 * invisible privilege escalation.
 *
 * <p><b>{@code cognitoSyncFailed} is surfaced, not swallowed</b> - deliberately unlike {@link
 * RenamePersonHandler}'s name-sync failure, which is "only a display convenience for the brief
 * window before {@code AuthProvider}'s {@code myPerson} lookup resolves." A stuck {@code
 * custom:class} sync is not cosmetic: the person still can't actually do admin things despite what
 * the UI now shows. True only alongside a successful DynamoDB write and an empty {@code errors}
 * list - a partial success, never a rejection.
 *
 * <p>Two guards, both admin-only-relevant and both checked before any write:
 *
 * <ul>
 *   <li>{@link PersonError#NoLinkedAccount} - {@code custom:class} lives on a Cognito account, not
 *       the {@code Person} record, so granting admin to a guest Person with none linked has nothing
 *       to actually flip.
 *   <li>{@link PersonError#CannotRevokeOwnAdminAccess} - no admin can remove their own admin access
 *       through this mutation (granting *someone else* admin, or acting on your own Person in the
 *       other direction, is unaffected).
 * </ul>
 */
public class SetPersonAdminHandler implements RequestHandler<Map<String, Object>, Object> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SetPersonAdminHandler.class);

  private final CognitoIdentityProviderClient cognitoClient;
  private final PersonRepository people;
  private final String userPoolId;

  public SetPersonAdminHandler() {
    this(
        DynamoDbClientProvider.client(),
        CognitoIdentityProviderClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        System.getenv("COGNITO_USER_POOL_ID"));
  }

  SetPersonAdminHandler(
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
    final boolean isAdmin = Boolean.TRUE.equals(arguments.get("isAdmin"));

    final Map<String, Object> result = new HashMap<>();
    final Optional<Person> current = people.findById(id);
    if (current.isEmpty()) {
      result.put("person", null);
      result.put("people", allPeople());
      result.put("cognitoSyncFailed", false);
      result.put("errors", List.of(PersonError.PersonNotFound.name()));
      return result;
    }

    if (!isAdmin && Identity.personId(event).map(id::equals).orElse(false)) {
      result.put("person", null);
      result.put("people", allPeople());
      result.put("cognitoSyncFailed", false);
      result.put("errors", List.of(PersonError.CannotRevokeOwnAdminAccess.name()));
      return result;
    }

    if (isAdmin && current.get().cognitoSubs().isEmpty()) {
      result.put("person", null);
      result.put("people", allPeople());
      result.put("cognitoSyncFailed", false);
      result.put("errors", List.of(PersonError.NoLinkedAccount.name()));
      return result;
    }

    // Carries every field this mutation doesn't own forward - see RenamePersonHandler's identical
    // comment; mootmaker-api#71 is what forgetting this looks like.
    final Person updated =
        new Person(
            id,
            current.get().name(),
            current.get().cognitoSubs(),
            current.get().cognitoEmails(),
            isAdmin,
            current.get().dateFormat(),
            current.get().timeFormat(),
            current.get().weekStart());
    people.put(updated);

    boolean cognitoSyncFailed = false;
    for (final String cognitoSub : updated.cognitoSubs()) {
      cognitoSyncFailed |= !propagateClassToCognito(cognitoSub, isAdmin);
    }

    result.put("person", updated.toResponseMap());
    result.put("people", allPeople());
    result.put("cognitoSyncFailed", cognitoSyncFailed);
    result.put("errors", List.of());
    return result;
  }

  /**
   * @return true on success, false if the Cognito call failed (logged, not thrown).
   */
  private boolean propagateClassToCognito(final String cognitoSub, final boolean isAdmin) {
    try {
      cognitoClient.adminUpdateUserAttributes(
          AdminUpdateUserAttributesRequest.builder()
              .userPoolId(userPoolId)
              .username(cognitoSub)
              .userAttributes(
                  AttributeType.builder()
                      .name("custom:class")
                      .value(isAdmin ? "admin" : "standard")
                      .build())
              .build());
      return true;
    } catch (final RuntimeException e) {
      LOGGER.error(
          "Failed to sync custom:class to Cognito for cognitoSub '{}' (isAdmin={})",
          cognitoSub,
          isAdmin,
          e);
      return false;
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
