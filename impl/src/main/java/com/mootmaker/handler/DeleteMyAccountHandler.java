package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.BatchLoader;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.deleteMyAccount}. Self-service only - always
 * deletes the caller's own account, resolved from the JWT's {@code sub} the same way
 * {@link MyPersonHandler} does. See mootmaker/designs/archive/delete-my-account.md in the workspace root for the
 * design this implements.
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
    private final String meetingsTableName;
    private final String meetingParticipantsTableName;
    private final String userPoolId;
    private final Set<String> reservedAccountEmails;

    public DeleteMyAccountHandler() {
        this(DynamoDbClientProvider.client(), CognitoIdentityProviderClientProvider.client(),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
                System.getenv().getOrDefault("MEETING_PARTICIPANTS_TABLE_NAME", "MeetingParticipants"),
                System.getenv("COGNITO_USER_POOL_ID"),
                parseReservedEmails(System.getenv("RESERVED_ACCOUNT_EMAILS")));
    }

    DeleteMyAccountHandler(final DynamoDbClient dynamoDbClient, final CognitoIdentityProviderClient cognitoClient,
            final String peopleTableName, final String meetingsTableName, final String meetingParticipantsTableName,
            final String userPoolId, final Set<String> reservedAccountEmails) {
        this.dynamoDbClient = dynamoDbClient;
        this.cognitoClient = cognitoClient;
        this.peopleTableName = peopleTableName;
        this.meetingsTableName = meetingsTableName;
        this.meetingParticipantsTableName = meetingParticipantsTableName;
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

        cognitoClient.adminDeleteUser(AdminDeleteUserRequest.builder()
                .userPoolId(userPoolId)
                .username(cognitoSub)
                .build());

        final Optional<Person> person = PersonRepository.findByCognitoSub(dynamoDbClient, peopleTableName, cognitoSub);
        person.ifPresent(this::cancelUpcomingMeetings);
        person.ifPresent(p -> dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(peopleTableName)
                .key(Map.of("id", AttributeValue.builder().s(p.id()).build()))
                .build()));

        LOGGER.info("Deleted account for cognitoSub '{}'", cognitoSub);
        return true;
    }

    private void cancelUpcomingMeetings(final Person person) {
        final String now = LocalDateTime.now().format(MeetingRecord.DATE_TIME_FORMAT);
        final List<String> meetingIds = queryParticipantMeetingIds(person.id());
        final Map<String, Map<String, AttributeValue>> itemsById =
                BatchLoader.loadById(dynamoDbClient, meetingsTableName, Set.copyOf(meetingIds));

        for (final Map<String, AttributeValue> item : itemsById.values()) {
            final MeetingRecord meeting = MeetingRecord.fromItem(item);
            if (meeting.startTime().compareTo(now) < 0) {
                continue; // past meeting - left untouched, see class javadoc
            }
            if (meeting.organiserId().equals(person.id())) {
                cancelMeeting(meeting);
            } else {
                removeAttendee(meeting, person.id());
            }
        }
    }

    /** Deletes the meeting and every one of its meeting-participants rows in one transaction. */
    private void cancelMeeting(final MeetingRecord meeting) {
        final List<TransactWriteItem> transactItems = new ArrayList<>();
        transactItems.add(TransactWriteItem.builder()
                .delete(Delete.builder()
                        .tableName(meetingsTableName)
                        .key(Map.of("id", AttributeValue.builder().s(meeting.id()).build()))
                        .build())
                .build());
        for (final MeetingParticipant participant : MeetingParticipant.allFor(meeting)) {
            transactItems.add(TransactWriteItem.builder()
                    .delete(Delete.builder()
                            .tableName(meetingParticipantsTableName)
                            .key(participantKey(participant))
                            .build())
                    .build());
        }
        dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    }

    /** Removes personId from the meeting's attendee list and deletes just their own participant row. */
    private void removeAttendee(final MeetingRecord meeting, final String personId) {
        final List<String> remainingAttendeeIds = meeting.attendeeIds().stream()
                .filter(attendeeId -> !attendeeId.equals(personId))
                .toList();
        final MeetingRecord updated = new MeetingRecord(meeting.id(), meeting.roomId(), meeting.organiserId(),
                remainingAttendeeIds, meeting.subject(), meeting.startTime(), meeting.endTime());
        final MeetingParticipant ownParticipantRow =
                new MeetingParticipant(personId, meeting.id(), meeting.startTime(), meeting.endTime());

        dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(List.of(
                        TransactWriteItem.builder()
                                .put(Put.builder().tableName(meetingsTableName).item(updated.toItem()).build())
                                .build(),
                        TransactWriteItem.builder()
                                .delete(Delete.builder()
                                        .tableName(meetingParticipantsTableName)
                                        .key(participantKey(ownParticipantRow))
                                        .build())
                                .build()))
                .build());
    }

    private static Map<String, AttributeValue> participantKey(final MeetingParticipant participant) {
        return Map.of(
                "personId", AttributeValue.builder().s(participant.personId()).build(),
                "sortKey", AttributeValue.builder().s(participant.sortKey()).build());
    }

    private List<String> queryParticipantMeetingIds(final String personId) {
        final QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(meetingParticipantsTableName)
                .keyConditionExpression("personId = :personId")
                .expressionAttributeValues(Map.of(":personId", AttributeValue.builder().s(personId).build()))
                .build());
        return response.items().stream()
                .map(MeetingParticipant::fromItem)
                .map(MeetingParticipant::meetingId)
                .distinct()
                .toList();
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
