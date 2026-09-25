package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.PersonError;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeCognitoIdentityProviderClient;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;

class DeletePersonHandlerTest {

  private static final String USER_POOL_ID = "pool-1";
  private static final String PEOPLE_TABLE = "People";
  private static final String MEETINGS_TABLE = "Meetings";

  private static final String FUTURE = "2099-01-01T09:00:00";
  private static final String FUTURE_END = "2099-01-01T09:30:00";
  private static final String PAST = "2020-01-01T09:00:00";
  private static final String PAST_END = "2020-01-01T09:30:00";

  private static Map<String, Object> event(final String targetId, final String callerPersonId) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", targetId);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    final Map<String, Object> claims = new HashMap<>();
    claims.put("custom:class", "admin");
    claims.put("custom:personId", callerPersonId);
    event.put("identity", Map.of("claims", claims));
    return event;
  }

  private static Map<String, Object> eventAsNonAdmin(final String targetId) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", targetId);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    event.put("identity", Map.of("claims", Map.of("custom:class", "standard")));
    return event;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> invoke(
      final DeletePersonHandler handler, final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  private static FakeDynamoDbClient clientWithPerson(final Person person) {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(PEOPLE_TABLE, new ArrayList<>(List.of(person.toItem())));
    return fakeClient;
  }

  private List<MeetingRecord> meetingsRemaining(final FakeDynamoDbClient client) {
    return DayFixtures.meetingsIn(client, MEETINGS_TABLE);
  }

  @Test
  void deletesThePersonAndTheirCognitoAccounts() {
    final Person target =
        new Person(
            "person-a",
            "Ada",
            List.of("sub-a1", "sub-a2"),
            List.of("ada@example.com"),
            false,
            null,
            null);
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(target);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient, cognitoClient, PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

    final Map<String, Object> result = invoke(handler, event("person-a", "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty());
    assertTrue(dynamoDbClient.tables.get(PEOPLE_TABLE).isEmpty());
    assertEquals(2, cognitoClient.deleteRequests.size());
  }

  @Test
  void cancelsAnUpcomingMeetingTheTargetOrganisesAndRemovesThemFromOnesTheyOnlyAttend() {
    final Person target = new Person("person-a", "Ada", "sub-a", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(target);
    DayFixtures.addMeeting(
        dynamoDbClient,
        MEETINGS_TABLE,
        new MeetingRecord(
            "meeting-1",
            "room-1",
            "person-a",
            List.of("person-b"),
            List.of(AttendeeStatus.NoResponse),
            "Standup",
            FUTURE,
            FUTURE_END));
    DayFixtures.addMeeting(
        dynamoDbClient,
        MEETINGS_TABLE,
        new MeetingRecord(
            "meeting-2",
            "room-1",
            "person-c",
            List.of("person-a"),
            List.of(AttendeeStatus.NoResponse),
            "Planning",
            FUTURE,
            FUTURE_END));
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient, cognitoClient, PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

    invoke(handler, event("person-a", "admin-person"));

    final List<MeetingRecord> remaining = meetingsRemaining(dynamoDbClient);
    assertEquals(1, remaining.size(), "the organised meeting is cancelled entirely");
    assertEquals("meeting-2", remaining.getFirst().id());
    assertTrue(remaining.getFirst().attendeeIds().isEmpty(), "the target is removed as attendee");
  }

  @Test
  void leavesPastMeetingsUntouched() {
    final Person target = new Person("person-a", "Ada", "sub-a", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(target);
    DayFixtures.addMeeting(
        dynamoDbClient,
        MEETINGS_TABLE,
        new MeetingRecord(
            "meeting-1",
            "room-1",
            "person-a",
            List.of(),
            List.of(),
            "Old standup",
            PAST,
            PAST_END));
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient, cognitoClient, PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

    invoke(handler, event("person-a", "admin-person"));

    assertEquals(1, meetingsRemaining(dynamoDbClient).size(), "past meetings are untouched");
  }

  @Test
  void refusesToLetAnAdminDeleteTheirOwnPersonAndChangesNothing() {
    final Person self = new Person("admin-1", "Grace", "sub-1", "grace@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(self);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient, cognitoClient, PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

    final Map<String, Object> result = invoke(handler, event("admin-1", "admin-1"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.CannotDeleteSelf.name()));
    assertFalse(dynamoDbClient.tables.get(PEOPLE_TABLE).isEmpty());
    assertTrue(cognitoClient.deleteRequests.isEmpty());
  }

  @Test
  void refusesToDeleteAReservedAccountAndChangesNothing() {
    final Person demo = new Person("demo-person", "Demo", "sub-demo", "demo@mootmaker.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(demo);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient,
            cognitoClient,
            PEOPLE_TABLE,
            MEETINGS_TABLE,
            USER_POOL_ID,
            Set.of("demo@mootmaker.com"));

    final Map<String, Object> result = invoke(handler, event("demo-person", "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.ReservedAccount.name()));
    assertFalse(dynamoDbClient.tables.get(PEOPLE_TABLE).isEmpty());
    assertTrue(cognitoClient.deleteRequests.isEmpty());
  }

  @Test
  void returnsPersonNotFoundForAMissingId() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient, cognitoClient, PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

    final Map<String, Object> result = invoke(handler, event("missing", "admin-person"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.contains(PersonError.PersonNotFound.name()));
  }

  @Test
  void rejectsANonAdminCaller() {
    final Person target = new Person("person-a", "Ada", "sub-a", "ada@example.com");
    final FakeDynamoDbClient dynamoDbClient = clientWithPerson(target);
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    final DeletePersonHandler handler =
        new DeletePersonHandler(
            dynamoDbClient, cognitoClient, PEOPLE_TABLE, MEETINGS_TABLE, USER_POOL_ID, Set.of());

    final Map<String, Object> event = eventAsNonAdmin("person-a");

    assertThrows(IllegalStateException.class, () -> handler.handleRequest(event, null));
    assertFalse(dynamoDbClient.tables.get(PEOPLE_TABLE).isEmpty());
  }
}
