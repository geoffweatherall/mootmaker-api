package com.mootmaker.handler;

import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseResetTest {

    private static final String ROOMS_TABLE = "Rooms";
    private static final String PEOPLE_TABLE = "People";
    private static final String MEETINGS_TABLE = "Meetings";
    private static final String PARTICIPANTS_TABLE = "MeetingParticipants";
    private static final String USER_POOL_ID = "pool-1";

    private static Map<String, AttributeValue> room(final String id) {
        return Map.of("id", AttributeValue.builder().s(id).build());
    }

    private static Map<String, AttributeValue> person(final String id, final String cognitoSub) {
        return new Person(id, "Someone", cognitoSub).toItem();
    }

    private static MeetingRecord meeting(final String id, final String organiserId, final List<String> attendeeIds) {
        return new MeetingRecord(id, "room-1", organiserId, attendeeIds, "Subject", "2026-07-01T09:00:00", "2026-07-01T10:00:00");
    }

    private static UserType user(final String username, final String sub, final String email) {
        return UserType.builder()
                .username(username)
                .attributes(
                        AttributeType.builder().name("sub").value(sub).build(),
                        AttributeType.builder().name("email").value(email).build())
                .build();
    }

    @Test
    void deleteAllItemsEmptiesTheTable() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(ROOMS_TABLE, new ArrayList<>(List.of(room("room-1"), room("room-2"))));

        final int deleted = DatabaseReset.deleteAllItems(dynamoDbClient, ROOMS_TABLE);

        assertEquals(2, deleted);
        assertTrue(dynamoDbClient.tables.get(ROOMS_TABLE).isEmpty());
    }

    @Test
    void deleteAllItemsSucceedsWhenTableIsAlreadyEmpty() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final int deleted = DatabaseReset.deleteAllItems(dynamoDbClient, ROOMS_TABLE);

        assertEquals(0, deleted);
    }

    @Test
    void deleteUnlinkedPeopleRemovesOnlyPeopleWithNoCognitoSub() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(
                person("guest-1", null),
                person("linked-1", "cognito-sub-123"))));

        final int deleted = DatabaseReset.deleteUnlinkedPeople(dynamoDbClient, PEOPLE_TABLE);

        assertEquals(1, deleted);
        final List<Map<String, AttributeValue>> remaining = dynamoDbClient.tables.get(PEOPLE_TABLE);
        assertEquals(1, remaining.size());
        assertEquals("linked-1", remaining.getFirst().get("id").s());
    }

    @Test
    void deleteAllMeetingsAndParticipantsRemovesEveryMeetingAndItsParticipantRows() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final MeetingRecord meeting = meeting("meeting-1", "organiser-1", List.of("attendee-1"));
        dynamoDbClient.tables.put(MEETINGS_TABLE, new ArrayList<>(List.of(meeting.toItem())));
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(
                MeetingParticipant.allFor(meeting).stream().map(MeetingParticipant::toItem).toList()));

        final int deleted = DatabaseReset.deleteAllMeetingsAndParticipants(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE);

        assertEquals(1, deleted);
        assertTrue(dynamoDbClient.tables.get(MEETINGS_TABLE).isEmpty());
        assertTrue(dynamoDbClient.tables.get(PARTICIPANTS_TABLE).isEmpty());
    }

    @Test
    void deleteAllMeetingsAndParticipantsSucceedsWhenTablesAreAlreadyEmpty() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final int deleted = DatabaseReset.deleteAllMeetingsAndParticipants(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE);

        assertEquals(0, deleted);
    }

    @Test
    void wipeCognitoPoolDeletesEveryUserExceptReservedEmails() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.addAll(List.of(
                user("demo@mootmaker.com", "demo-sub", "demo@mootmaker.com"),
                user("e2e-tests@example.com", "e2e-sub", "e2e-tests@example.com"),
                user("stray-signup@example.com", "stray-sub", "stray-signup@example.com")));
        final Set<String> reservedEmails = Set.of("demo@mootmaker.com", "e2e-tests@example.com");

        final DatabaseReset.CognitoWipeResult result = DatabaseReset.wipeCognitoPool(cognitoClient, USER_POOL_ID, reservedEmails);

        assertEquals(1, result.usersDeleted());
        assertEquals(Set.of("demo-sub", "e2e-sub"), result.survivingSubs());
        assertEquals(1, cognitoClient.deleteRequests.size());
        assertEquals("stray-signup@example.com", cognitoClient.deleteRequests.getFirst().username());
    }

    @Test
    void wipeCognitoPoolMatchesReservedEmailsCaseInsensitively() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.add(user("demo@mootmaker.com", "demo-sub", "Demo@Mootmaker.com"));
        final Set<String> reservedEmails = Set.of("demo@mootmaker.com");

        final DatabaseReset.CognitoWipeResult result = DatabaseReset.wipeCognitoPool(cognitoClient, USER_POOL_ID, reservedEmails);

        assertEquals(0, result.usersDeleted());
        assertEquals(Set.of("demo-sub"), result.survivingSubs());
    }

    @Test
    void wipeCognitoPoolFollowsPaginationAcrossMultiplePages() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.addAll(List.of(
                user("one@example.com", "sub-1", "one@example.com"),
                user("two@example.com", "sub-2", "two@example.com"),
                user("three@example.com", "sub-3", "three@example.com")));
        cognitoClient.listUsersPageSize = 1;

        final DatabaseReset.CognitoWipeResult result = DatabaseReset.wipeCognitoPool(cognitoClient, USER_POOL_ID, Set.of());

        assertEquals(3, result.usersDeleted());
        assertEquals(3, cognitoClient.deleteRequests.size());
    }

    @Test
    void deletePeopleNotLinkedToRemovesPeopleWithNoCognitoSubAndPeopleWithAStaleOne() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(
                person("guest-1", null),
                person("stray-1", "deleted-sub"),
                person("demo-person", "demo-sub"))));

        final int deleted = DatabaseReset.deletePeopleNotLinkedTo(dynamoDbClient, PEOPLE_TABLE, Set.of("demo-sub"));

        assertEquals(2, deleted);
        final List<Map<String, AttributeValue>> remaining = dynamoDbClient.tables.get(PEOPLE_TABLE);
        assertEquals(1, remaining.size());
        assertEquals("demo-person", remaining.getFirst().get("id").s());
    }
}
