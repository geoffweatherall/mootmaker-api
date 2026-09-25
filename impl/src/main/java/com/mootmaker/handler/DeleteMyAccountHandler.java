package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.deleteMyAccount}. Self-service only - always
 * deletes the caller's own account, resolved from the JWT's {@code sub} the same way {@link
 * MyPersonHandler} does. See mootmaker/designs/archive/delete-my-account.md in the workspace root
 * for the design this implements.
 *
 * <p><b>Order of operations matters here - meetings, then the Person, then the Cognito account(s)
 * LAST.</b> (A previous revision of this comment claimed the opposite order; it was wrong - this
 * paragraph now matches what {@link #handleRequest} actually does, checked directly rather than
 * assumed while writing {@code DeletePersonHandler} against the same ordering.) Deleting Cognito
 * first would risk the worse failure: a version-conflicted day mutation partway through the
 * meetings cascade would leave the caller locked out, unable to sign back in to retry, with their
 * meetings orphaned and no self-service recovery. Doing DynamoDB cleanup first instead means a
 * failure there still leaves a working login to retry with - and if the caller pushes through to
 * the Cognito delete succeeding while some DynamoDB cleanup is still incomplete, that is exactly
 * the state {@code database-repair} exists to reconcile, not a user-visible failure.
 *
 * <p>Every upcoming meeting the caller organises is cancelled, and every upcoming meeting the
 * caller only attends has them removed from its attendee list instead - see {@link
 * UpcomingMeetings#cancelUpcomingMeetingsFor}, shared with {@code DeletePersonHandler}'s identical
 * cascade (admin-invoked against someone else rather than the caller). Past meetings are left
 * untouched entirely.
 */
public class DeleteMyAccountHandler implements RequestHandler<Map<String, Object>, Object> {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteMyAccountHandler.class);

  private final DynamoDbClient dynamoDbClient;
  private final CognitoIdentityProviderClient cognitoClient;
  private final String peopleTableName;
  private final DayRepository days;
  private final String userPoolId;
  private final Set<String> reservedAccountEmails;

  public DeleteMyAccountHandler() {
    this(
        DynamoDbClientProvider.client(),
        CognitoIdentityProviderClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv("COGNITO_USER_POOL_ID"),
        parseReservedEmails(System.getenv("RESERVED_ACCOUNT_EMAILS")));
  }

  DeleteMyAccountHandler(
      final DynamoDbClient dynamoDbClient,
      final CognitoIdentityProviderClient cognitoClient,
      final String peopleTableName,
      final String meetingsTableName,
      final String userPoolId,
      final Set<String> reservedAccountEmails) {
    this.dynamoDbClient = dynamoDbClient;
    this.cognitoClient = cognitoClient;
    this.peopleTableName = peopleTableName;
    this.days = new DayRepository(dynamoDbClient, meetingsTableName);
    this.userPoolId = userPoolId;
    this.reservedAccountEmails = reservedAccountEmails;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final Map<String, Object> identity = castToMap(event.get("identity"));
    final String cognitoSub = (String) identity.get("sub");
    final Map<String, Object> claims = castToMap(identity.get("claims"));
    final String email = claims == null ? null : (String) claims.get("email");

    // Guards the demo/e2e Terraform-managed users (see cognito.tf) - there's no reasonable case
    // for letting the public demo login, or the Playwright e2e user, be deletable through this
    // self-service flow.
    if (email != null && reservedAccountEmails.contains(email.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException("Forbidden: this account cannot be deleted");
    }

    final Optional<Person> person =
        Identity.personId(event)
            .flatMap(new PersonRepository(dynamoDbClient, peopleTableName)::findById);

    // ORDER MATTERS, for the same reason the retention job advances its boundary before deleting.
    // Meetings, then the Person, then the Cognito accounts LAST. The intermediate state this leaves
    // - Person gone, login still works - is recoverable. The reverse is not: if Cognito went first
    // and a day rewrite then lost a version conflict, the user could no longer sign in to retry and
    // their meetings would be orphaned with no owner.
    person.ifPresent(this::cancelUpcomingMeetings);
    person.ifPresent(
        p ->
            dynamoDbClient.deleteItem(
                DeleteItemRequest.builder()
                    .tableName(peopleTableName)
                    .key(Map.of("id", AttributeValue.builder().s(p.id()).build()))
                    .build()));

    // Every linked account, not just the one that made the call - a person who signs in two ways
    // must not be left with one login still working after deleting their account. Falls back to the
    // calling sub when no Person is linked, so an account with no Person can still delete itself.
    final List<String> linkedSubs =
        person.map(Person::cognitoSubs).filter(subs -> !subs.isEmpty()).orElse(List.of(cognitoSub));
    for (final String linkedSub : linkedSubs) {
      cognitoClient.adminDeleteUser(
          AdminDeleteUserRequest.builder().userPoolId(userPoolId).username(linkedSub).build());
    }

    LOGGER.info(
        "Deleted account for cognitoSub '{}' ({} linked Cognito account(s))",
        cognitoSub,
        linkedSubs.size());
    return true;
  }

  /**
   * Cancels every upcoming meeting this person organises, and removes them as an attendee from
   * every other upcoming one. Past meetings are left untouched, as before. See {@link
   * UpcomingMeetings}, shared with {@code DeletePersonHandler}'s identical cascade.
   */
  private void cancelUpcomingMeetings(final Person person) {
    UpcomingMeetings.cancelUpcomingMeetingsFor(days, person.id(), UpcomingMeetings.now());
  }

  private static Set<String> parseReservedEmails(final String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
  }
}
