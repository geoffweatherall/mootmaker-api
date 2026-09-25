package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.deletePerson}. Admin only - see {@link
 * Identity#requireAdmin}. The admin-invoked counterpart to {@code deleteMyAccount}: same cascade
 * (cancel every upcoming meeting this person organises, remove them from every upcoming meeting
 * they only attend, leave the past untouched - see {@link UpcomingMeetings}), same write order
 * (meetings, then the Person, then the Cognito account(s) last - see {@code
 * DeleteMyAccountHandler}'s own doc comment for why that direction fails toward the safer outcome),
 * just targeting someone else's Person instead of the caller's own.
 *
 * <p>Two guards, both checked before any write:
 *
 * <ul>
 *   <li>{@link PersonError#CannotDeleteSelf} - an admin deleting themselves through this path would
 *       skip {@code deleteMyAccount}'s own guarantees entirely (its reserved-account check, and its
 *       specific ordering reasoning). Point them at {@code deleteMyAccount} instead.
 *   <li>{@link PersonError#ReservedAccount} - reuses the same {@code RESERVED_ACCOUNT_EMAILS}
 *       mechanism {@code DeleteMyAccountHandler} already uses, checked against the target's {@code
 *       linkedEmails} rather than the caller's own. Concretely protects the demo user's Person,
 *       which is Terraform-managed and not something an app mutation should remove out from under a
 *       running {@code production} environment.
 * </ul>
 */
public class DeletePersonHandler implements RequestHandler<Map<String, Object>, Object> {

  private final CognitoIdentityProviderClient cognitoClient;
  private final PersonRepository people;
  private final DayRepository days;
  private final String userPoolId;
  private final Set<String> reservedAccountEmails;

  public DeletePersonHandler() {
    this(
        DynamoDbClientProvider.client(),
        CognitoIdentityProviderClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv("COGNITO_USER_POOL_ID"),
        parseReservedEmails(System.getenv("RESERVED_ACCOUNT_EMAILS")));
  }

  DeletePersonHandler(
      final DynamoDbClient dynamoDbClient,
      final CognitoIdentityProviderClient cognitoClient,
      final String peopleTableName,
      final String meetingsTableName,
      final String userPoolId,
      final Set<String> reservedAccountEmails) {
    this.cognitoClient = cognitoClient;
    this.people = new PersonRepository(dynamoDbClient, peopleTableName);
    this.days = new DayRepository(dynamoDbClient, meetingsTableName);
    this.userPoolId = userPoolId;
    this.reservedAccountEmails = reservedAccountEmails;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAdmin(event);

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String id = (String) arguments.get("id");

    final Map<String, Object> result = new HashMap<>();
    final Optional<Person> target = people.findById(id);
    if (target.isEmpty()) {
      result.put("people", allPeople());
      result.put("errors", List.of(PersonError.PersonNotFound.name()));
      return result;
    }

    if (Identity.personId(event).map(id::equals).orElse(false)) {
      result.put("people", allPeople());
      result.put("errors", List.of(PersonError.CannotDeleteSelf.name()));
      return result;
    }

    final boolean isReserved =
        target.get().cognitoEmails().stream()
            .map(email -> email.toLowerCase(Locale.ROOT))
            .anyMatch(reservedAccountEmails::contains);
    if (isReserved) {
      result.put("people", allPeople());
      result.put("errors", List.of(PersonError.ReservedAccount.name()));
      return result;
    }

    // ORDER MATTERS - meetings, then the Person, then the Cognito account(s) LAST. See this
    // handler's own doc comment, and DeleteMyAccountHandler's identical reasoning for why.
    UpcomingMeetings.cancelUpcomingMeetingsFor(days, id, UpcomingMeetings.now());
    people.deleteById(id);
    for (final String cognitoSub : target.get().cognitoSubs()) {
      cognitoClient.adminDeleteUser(
          AdminDeleteUserRequest.builder().userPoolId(userPoolId).username(cognitoSub).build());
    }

    result.put("people", allPeople());
    result.put("errors", List.of());
    return result;
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
    return (Map<String, Object>) value;
  }

  /** Every person after the change - see the schema's note on Person mutation results. */
  private List<Map<String, Object>> allPeople() {
    return people.listAll().stream().map(Person::toResponseMap).toList();
  }
}
