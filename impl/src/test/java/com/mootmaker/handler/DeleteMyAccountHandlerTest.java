package com.mootmaker.handler;

import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteMyAccountHandlerTest {

    private static final String USER_POOL_ID = "pool-1";
    private static final String PEOPLE_TABLE = "People";
    private static final String MEETINGS_TABLE = "Meetings";
    private static final String PARTICIPANTS_TABLE = "MeetingParticipants";

    private static final String PAST = "2020-01-01T09:00:00";
    private static final String PAST_END = "2020-01-01T09:30:00";
    private static final String FUTURE = "2099-01-01T09:00:00";
    private static final String FUTURE_END = "2099-01-01T09:30:00";

    private static Map<String, Object> deleteEvent(final String callerSub, final String email) {
        final Map<String, Object> identity = new HashMap<>();
        identity.put("sub", callerSub);
        final Map<String, Object> claims = new HashMap<>();
        if (email != null) {
            claims.put("email", email);
        }
        identity.put("claims", claims);
        final Map<String, Object> event = new HashMap<>();
        event.put("identity", identity);
        return event;
    }

    private void putMeetingAndParticipants(final FakeDynamoDbClient client, final MeetingRecord meeting) {
        client.tables.computeIfAbsent(MEETINGS_TABLE, _ -> new ArrayList<>()).add(meeting.toItem());
        for (final MeetingParticipant participant : MeetingParticipant.allFor(meeting)) {
            client.tables.computeIfAbsent(PARTICIPANTS_TABLE, _ -> new ArrayList<>()).add(participant.toItem());
        }
    }

    private List<MeetingRecord> meetingsRemaining(final FakeDynamoDbClient client) {
        return client.tables.getOrDefault(MEETINGS_TABLE, List.of()).stream().map(MeetingRecord::fromItem).toList();
    }

    private List<MeetingParticipant> participantsRemaining(final FakeDynamoDbClient client) {
        return client.tables.getOrDefault(PARTICIPANTS_TABLE, List.of()).stream().map(MeetingParticipant::fromItem).toList();
    }

    @Test
    void deletesThePersonAndTheCognitoUser() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("person-a", "Ada", "sub-a").toItem())));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, PARTICIPANTS_TABLE, USER_POOL_ID, Set.of());

        final Object result = handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        assertEquals(Boolean.TRUE, result);
        assertTrue(dynamoDbClient.tables.get(PEOPLE_TABLE).isEmpty());
        assertEquals(1, cognitoClient.deleteRequests.size());
        assertEquals(USER_POOL_ID, cognitoClient.deleteRequests.getFirst().userPoolId());
        assertEquals("sub-a", cognitoClient.deleteRequests.getFirst().username());
    }

    @Test
    void cancelsAnUpcomingMeetingTheCallerOrganisesAndRemovesEveryParticipantRow() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("person-a", "Ada", "sub-a").toItem())));
        putMeetingAndParticipants(dynamoDbClient,
                new MeetingRecord("meeting-1", "room-1", "person-a", List.of("person-b"), "Standup", FUTURE, FUTURE_END));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, PARTICIPANTS_TABLE, USER_POOL_ID, Set.of());

        handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        assertTrue(meetingsRemaining(dynamoDbClient).isEmpty(), "the organised meeting should be deleted entirely");
        assertTrue(participantsRemaining(dynamoDbClient).isEmpty(), "every participant row for that meeting should be gone");
    }

    @Test
    void removesTheCallerFromAnUpcomingMeetingTheyOnlyAttendWithoutTouchingTheMeetingItself() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("person-a", "Ada", "sub-a").toItem())));
        putMeetingAndParticipants(dynamoDbClient,
                new MeetingRecord("meeting-2", "room-1", "person-b", List.of("person-a", "person-c"), "Planning", FUTURE, FUTURE_END));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, PARTICIPANTS_TABLE, USER_POOL_ID, Set.of());

        handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        final List<MeetingRecord> remainingMeetings = meetingsRemaining(dynamoDbClient);
        assertEquals(1, remainingMeetings.size(), "the meeting itself must survive - the caller only attended it");
        assertEquals(List.of("person-c"), remainingMeetings.getFirst().attendeeIds());

        final List<String> remainingParticipantIds = participantsRemaining(dynamoDbClient).stream()
                .map(MeetingParticipant::personId)
                .toList();
        assertFalse(remainingParticipantIds.contains("person-a"), "the caller's own participant row must be gone");
        assertTrue(remainingParticipantIds.contains("person-b"), "the organiser's participant row must survive");
        assertTrue(remainingParticipantIds.contains("person-c"), "the other attendee's participant row must survive");
    }

    @Test
    void leavesPastMeetingsCompletelyUntouched() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("person-a", "Ada", "sub-a").toItem())));
        putMeetingAndParticipants(dynamoDbClient,
                new MeetingRecord("meeting-3", "room-1", "person-a", List.of("person-b"), "Old standup", PAST, PAST_END));
        putMeetingAndParticipants(dynamoDbClient,
                new MeetingRecord("meeting-4", "room-1", "person-b", List.of("person-a"), "Old planning", PAST, PAST_END));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, PARTICIPANTS_TABLE, USER_POOL_ID, Set.of());

        handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        assertEquals(2, meetingsRemaining(dynamoDbClient).size(), "past meetings must not be touched at all");
        assertEquals(4, participantsRemaining(dynamoDbClient).size(), "past meetings' participant rows must not be touched either");
    }

    @Test
    void refusesToDeleteAReservedAccountAndChangesNothing() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("demo-person", "Demo Strater", "sub-demo").toItem())));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, PARTICIPANTS_TABLE, USER_POOL_ID, Set.of("demo@mootmaker.com"));

        final Map<String, Object> event = deleteEvent("sub-demo", "demo@mootmaker.com");
        assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));

        assertFalse(dynamoDbClient.tables.get(PEOPLE_TABLE).isEmpty());
        assertTrue(cognitoClient.deleteRequests.isEmpty());
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, PARTICIPANTS_TABLE, USER_POOL_ID, Set.of());

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(new HashMap<>(), null));
        assertTrue(cognitoClient.deleteRequests.isEmpty());
    }
}
