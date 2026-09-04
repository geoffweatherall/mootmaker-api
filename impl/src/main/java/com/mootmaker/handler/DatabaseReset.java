package com.mootmaker.handler;

import com.mootmaker.concurrent.ConcurrencyUtils;
import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import module java.base;

/**
 * The actual reset logic behind {@link DatabaseResetHandler} - formerly {@code Mutation.reset} in
 * this API, then a standalone {@code mootmaker-admin-tools/database-reset} Lambda, now merged back
 * in here (see designs/admin-tools-into-api.md). Deletes every stored room and meeting, and - in
 * every environment except {@code production} - wipes the Cognito user pool down to the two
 * Terraform-managed reserved accounts (demo, e2e) and every Person not linked to one of them.
 *
 * <p>In {@code production} the Cognito wipe is skipped entirely (see {@link DatabaseResetHandler}'s
 * {@code allowCognitoWipe} gate) and Person survival falls back to the original, narrower rule: a
 * Person is preserved if it has *any* non-null {@code cognitoSub}, since production may have real
 * signed-up accounts whose Cognito user this Lambda never touches and therefore can't verify still
 * exists.
 */
final class DatabaseReset {

    private DatabaseReset() {
    }

    record CognitoWipeResult(int usersDeleted, Set<String> survivingSubs) {
    }

    /** Deletes every item in {@code tableName} (used for the Rooms table, which is emptied unconditionally). */
    static int deleteAllItems(final DynamoDbClient dynamoDbClient, final String tableName) {
        final List<Map<String, AttributeValue>> items = scan(dynamoDbClient, tableName);
        ConcurrencyUtils.runInParallel(items, item -> dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", item.get("id")))
                .build()));
        return items.size();
    }

    /**
     * Deletes every Cognito user except the ones whose email (case-insensitive) is in
     * {@code reservedEmails} - the same two Terraform-managed accounts (demo, e2e)
     * {@code DeleteMyAccountHandler} already refuses to self-delete. Logs each deleted user's email,
     * one line per deletion - the highest-consequence deletion this Lambda does. Returns the set of
     * the *reserved* users' actual current {@code sub}s, which is what determines which People
     * survive afterward (see {@link #deletePeopleNotLinkedTo}) - looked up fresh here rather than
     * trusted from a Person record, since a Person's stored {@code cognitoSub} says nothing about
     * whether that Cognito user still actually exists.
     */
    static CognitoWipeResult wipeCognitoPool(
            final CognitoIdentityProviderClient cognitoClient, final String userPoolId, final Set<String> reservedEmails) {
        final List<UserType> users = listAllUsers(cognitoClient, userPoolId);

        final Set<String> survivingSubs = new HashSet<>();
        final List<UserType> toDelete = new ArrayList<>();
        for (final UserType user : users) {
            final String email = attribute(user, "email");
            if (email != null && reservedEmails.contains(email.toLowerCase(Locale.ROOT))) {
                survivingSubs.add(attribute(user, "sub"));
            } else {
                toDelete.add(user);
            }
        }

        ConcurrencyUtils.runInParallel(toDelete, user -> {
            System.out.println("  deleting Cognito user: " + user.username());
            cognitoClient.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(user.username())
                    .build());
        });

        return new CognitoWipeResult(toDelete.size(), survivingSubs);
    }

    /**
     * Non-production rule: a Person survives only if its {@code cognitoSub} is one of
     * {@code survivingSubs} - the reserved accounts' actual current subs, per
     * {@link #wipeCognitoPool}. Unlike {@link #deleteUnlinkedPeople}, this deletes a Person even if
     * it has a {@code cognitoSub} set, as long as that Cognito user was just deleted (or never
     * existed) - which is what closes the "stray Person" gap a dangling {@code cognitoSub} used to
     * leave behind.
     */
    static int deletePeopleNotLinkedTo(final DynamoDbClient dynamoDbClient, final String peopleTableName,
            final Set<String> survivingSubs) {
        final List<Map<String, AttributeValue>> toDelete = scan(dynamoDbClient, peopleTableName).stream()
                .filter(item -> {
                    final AttributeValue cognitoSub = item.get("cognitoSub");
                    return cognitoSub == null || !survivingSubs.contains(cognitoSub.s());
                })
                .toList();
        ConcurrencyUtils.runInParallel(toDelete, item -> dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(peopleTableName)
                .key(Map.of("id", item.get("id")))
                .build()));
        return toDelete.size();
    }

    /**
     * Production-only fallback rule: deletes every person with no {@code cognitoSub} at all (guests
     * added directly, or leftover sample data). A person linked to a real Cognito account is their
     * only link back to that account (nothing recreates it after the fact), so it's preserved -
     * exactly the original {@code Mutation.reset}/{@code database-reset} rule, kept here because the
     * Cognito wipe that would let this Lambda verify those accounts still exist is refused in
     * production (see {@link DatabaseResetHandler}).
     */
    static int deleteUnlinkedPeople(final DynamoDbClient dynamoDbClient, final String peopleTableName) {
        final List<Map<String, AttributeValue>> unlinked = scan(dynamoDbClient, peopleTableName).stream()
                .filter(item -> !item.containsKey("cognitoSub"))
                .toList();
        ConcurrencyUtils.runInParallel(unlinked, item -> dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(peopleTableName)
                .key(Map.of("id", item.get("id")))
                .build()));
        return unlinked.size();
    }

    /**
     * meeting-participants is a derived index of the meetings table (see {@link MeetingParticipant}),
     * not a source of truth, so every meeting's participant rows are deleted alongside it here -
     * their keys are computed from the meeting item already being read, rather than needing a
     * separate scan of the participants table.
     */
    static int deleteAllMeetingsAndParticipants(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String meetingParticipantsTableName) {
        final List<MeetingRecord> meetings = scan(dynamoDbClient, meetingsTableName).stream()
                .map(MeetingRecord::fromItem)
                .toList();
        ConcurrencyUtils.runInParallel(meetings, meeting -> {
            for (final MeetingParticipant participant : MeetingParticipant.allFor(meeting)) {
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(meetingParticipantsTableName)
                        .key(Map.of(
                                "personId", AttributeValue.builder().s(participant.personId()).build(),
                                "sortKey", AttributeValue.builder().s(participant.sortKey()).build()))
                        .build());
            }
            dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                    .tableName(meetingsTableName)
                    .key(Map.of("id", AttributeValue.builder().s(meeting.id()).build()))
                    .build());
        });
        return meetings.size();
    }

    private static List<UserType> listAllUsers(final CognitoIdentityProviderClient cognitoClient, final String userPoolId) {
        final List<UserType> users = new ArrayList<>();
        String paginationToken = null;
        do {
            final ListUsersResponse response = cognitoClient.listUsers(ListUsersRequest.builder()
                    .userPoolId(userPoolId)
                    .paginationToken(paginationToken)
                    .build());
            users.addAll(response.users());
            paginationToken = response.paginationToken();
        } while (paginationToken != null && !paginationToken.isEmpty());
        return users;
    }

    private static String attribute(final UserType user, final String attributeName) {
        return user.attributes().stream()
                .filter(attribute -> attributeName.equals(attribute.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }

    private static List<Map<String, AttributeValue>> scan(final DynamoDbClient dynamoDbClient, final String tableName) {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).consistentRead(true).build()).items();
    }
}
