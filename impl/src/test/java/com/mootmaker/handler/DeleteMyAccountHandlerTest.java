package com.mootmaker.handler;

import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.FakeCognitoIdentityProviderClient;
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
        DayFixtures.addMeeting(client, MEETINGS_TABLE, meeting);
    }

    private List<MeetingRecord> meetingsRemaining(final FakeDynamoDbClient client) {
        return DayFixtures.meetingsIn(client, MEETINGS_TABLE);
    }

    /**
     * There is no participants table any more, so "was this person's row removed" is asked of the
     * meetings themselves. That is a better question than the old one: it checks the fact users care
     * about - who is on the meeting - rather than the state of a derived index that no longer exists.
     */
    private List<String> participantIdsRemaining(final FakeDynamoDbClient client) {
        return meetingsRemaining(client).stream()
                .flatMap(meeting -> Stream.concat(Stream.of(meeting.organiserId()), meeting.attendeeIds().stream()))
                .distinct()
                .toList();
    }

    @Test
    void deletesThePersonAndTheCognitoUser() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("person-a", "Ada", "sub-a").toItem())));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

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
                PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

        handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        assertTrue(meetingsRemaining(dynamoDbClient).isEmpty(), "the organised meeting should be deleted entirely");
        assertTrue(participantIdsRemaining(dynamoDbClient).isEmpty(), "nobody should be left on any meeting");
    }

    @Test
    void removesTheCallerFromAnUpcomingMeetingTheyOnlyAttendWithoutTouchingTheMeetingItself() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("person-a", "Ada", "sub-a").toItem())));
        putMeetingAndParticipants(dynamoDbClient,
                new MeetingRecord("meeting-2", "room-1", "person-b", List.of("person-a", "person-c"), "Planning", FUTURE, FUTURE_END));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

        handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        final List<MeetingRecord> remainingMeetings = meetingsRemaining(dynamoDbClient);
        assertEquals(1, remainingMeetings.size(), "the meeting itself must survive - the caller only attended it");
        assertEquals(List.of("person-c"), remainingMeetings.getFirst().attendeeIds());

        final List<String> remainingParticipantIds = participantIdsRemaining(dynamoDbClient);
        assertFalse(remainingParticipantIds.contains("person-a"), "the caller must be off the meeting");
        assertTrue(remainingParticipantIds.contains("person-b"), "the organiser must remain");
        assertTrue(remainingParticipantIds.contains("person-c"), "the other attendee must remain");
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
                PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

        handler.handleRequest(deleteEvent("sub-a", "ada@example.com"), null);

        assertEquals(2, meetingsRemaining(dynamoDbClient).size(), "past meetings must not be touched at all");
        assertEquals(List.of("person-a", "person-b"), participantIdsRemaining(dynamoDbClient).stream().sorted().toList(),
                "past meetings must keep everyone who was on them");
    }

    @Test
    void refusesToDeleteAReservedAccountAndChangesNothing() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(new Person("demo-person", "Demo Strater", "sub-demo").toItem())));
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        final DeleteMyAccountHandler handler = new DeleteMyAccountHandler(dynamoDbClient, cognitoClient,
                PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of("demo@mootmaker.com"));

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
                PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(new HashMap<>(), null));
        assertTrue(cognitoClient.deleteRequests.isEmpty());
    }
}
