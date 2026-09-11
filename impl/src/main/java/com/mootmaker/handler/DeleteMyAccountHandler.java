package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingRecord;
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
 * <p><b>Order of operations matters here.</b> {@link #handleRequest} deletes the Cognito user
 * first, before touching any DynamoDB data, and only proceeds to the DynamoDB cleanup if that
 * succeeds. If it were the other way round and the DynamoDB cleanup ran first, a transient Cognito
 * failure partway through would leave someone with a fully working login and no data at all - a
 * confusing empty-account state. This ordering instead fails toward the safer outcome: on any
 * failure, the account either still works with all its data intact (nothing attempted yet), or is
 * already unusable for sign-in with some data cleanup still pending, which is recoverable via
 * {@code database-repair} rather than user-visible.
 *
 * <p>Every upcoming meeting the caller organises is cancelled (deleted, along with its
 * meeting-participants rows) - other attendees simply lose that meeting from their view, with no
 * notification (a known gap, deliberately deferred - see the design doc). Every upcoming meeting
 * the caller only attends has them removed from its attendee list instead, leaving the meeting
 * itself intact for its organiser and remaining attendees. Past meetings are left untouched
 * entirely; {@link ListMeetingsHandler} already resolves a since-deleted participant to a
 * placeholder rather than breaking, so leaving a dangling id in historical data is safe.
 *
 * <p>Each meeting's cascade (its own delete-or-update plus its participant row(s)) runs as one
 * DynamoDB transaction, matching the granularity {@link CreateMeetingHandler} already uses when
 * creating a meeting - but the meetings are not all one single transaction with each other, since a
 * prolific organiser's meeting count has no fixed upper bound and DynamoDB transactions cap at 100
 * items. A failure partway through leaves some meetings cleaned up and others not, reconcilable by
 * the same database-repair tooling referenced above.
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
   * every other upcoming one. Past meetings are left untouched, as before.
   *
   * <p>Found by <b>scanning day items</b>, which is what replaced the {@code meeting-participants}
   * join table. That table existed to answer one question - "every meeting this person is in, with
   * no date range" - for this one rare operation, at the cost of a row per participant per meeting
   * kept consistent on every write. The booking horizon and retention bound the table at 217 day
   * items, so a scan is both trivial and, unlike a computed range of horizon dates, exact: it
   * cannot miss a day the arithmetic got wrong.
   *
   * <p>Each affected day goes through {@code mutate}, so the filtering re-runs against freshly-read
   * state and the {@code PTR#} pointers of cancelled meetings are removed in the same transaction
   * as the day - the repository diffs the meeting ids, so no caller has to remember to.
   */
  private void cancelUpcomingMeetings(final Person person) {
    final String now = LocalDateTime.now().format(MeetingRecord.DATE_TIME_FORMAT);
    final List<String> affectedDates =
        days.scanDays().stream()
            .filter(
                day ->
                    day.meetings().stream()
                        .anyMatch(meeting -> isUpcomingAndInvolves(meeting, person.id(), now)))
            .map(Day::date)
            .toList();

    for (final String date : affectedDates) {
      days.mutate(
          date,
          day ->
              day.withMeetings(
                  day.meetings().stream()
                      .filter(meeting -> !isUpcomingOrganisedBy(meeting, person.id(), now))
                      .map(
                          meeting ->
                              isUpcoming(meeting, now)
                                  ? withoutAttendee(meeting, person.id())
                                  : meeting)
                      .toList()));
    }
  }

  private static boolean isUpcoming(final MeetingRecord meeting, final String now) {
    return meeting.startTime().compareTo(now) >= 0;
  }

  private static boolean isUpcomingOrganisedBy(
      final MeetingRecord meeting, final String personId, final String now) {
    return isUpcoming(meeting, now) && meeting.organiserId().equals(personId);
  }

  private static boolean isUpcomingAndInvolves(
      final MeetingRecord meeting, final String personId, final String now) {
    return isUpcoming(meeting, now)
        && (meeting.organiserId().equals(personId) || meeting.attendeeIds().contains(personId));
  }

  private static MeetingRecord withoutAttendee(final MeetingRecord meeting, final String personId) {
    if (!meeting.attendeeIds().contains(personId)) {
      return meeting;
    }
    return new MeetingRecord(
        meeting.id(),
        meeting.roomId(),
        meeting.organiserId(),
        meeting.attendeeIds().stream().filter(id -> !id.equals(personId)).toList(),
        meeting.subject(),
        meeting.startTime(),
        meeting.endTime());
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
