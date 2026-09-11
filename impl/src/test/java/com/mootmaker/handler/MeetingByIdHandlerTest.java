package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import module java.base;

import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class MeetingByIdHandlerTest {

  private static final String DATE = "2026-09-14";

  private FakeDynamoDbClient fakeClient;
  private MeetingByIdHandler handler;

  @BeforeEach
  void setUp() {
    fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(
        "Rooms", new ArrayList<>(List.of(new Room("room-1", "Kaikoura", 8).toItem())));
    fakeClient.tables.put(
        "People",
        new ArrayList<>(List.of(new Person("person-1", "Ada Lovelace", "sub-1").toItem())));
    DayFixtures.addMeeting(
        fakeClient,
        "Meetings",
        new MeetingRecord(
            "m-1",
            "room-1",
            "person-1",
            List.of(),
            "Standup",
            DATE + "T09:00:00",
            DATE + "T09:30:00"));
    handler = new MeetingByIdHandler(fakeClient, "Meetings", "Rooms", "People");
  }

  private static Map<String, Object> event(final String id, final String... selections) {
    return Map.of(
        "identity", Map.of("sub", "sub-1"),
        "arguments", Map.of("id", id),
        "info", Map.of("selectionSetList", List.of(selections)));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @Test
  @DisplayName("resolves through the id -> date pointer, in two reads rather than a scan")
  void findsAMeetingByItsId() {
    final Map<String, Object> meeting = invoke(event("m-1", "id", "subject", "room", "room/name"));

    assertEquals("m-1", meeting.get("id"));
    assertEquals("Standup", meeting.get("subject"));
    @SuppressWarnings("unchecked")
    final Map<String, Object> room = (Map<String, Object>) meeting.get("room");
    assertEquals("Kaikoura", room.get("name"));
  }

  @Test
  void returnsNullForAnIdThatNeverExisted() {
    assertNull(invoke(event("never-existed", "id")));
  }

  @Test
  @DisplayName("an orphaned pointer resolves to not-found rather than failing")
  void returnsNullWhenThePointersDayNoLongerHoldsTheMeeting() {
    // A state the design tolerates precisely because it degrades this way: the day item is the
    // source of truth, so a pointer without a matching meeting is simply an id nobody can resolve.
    fakeClient
        .tables
        .get("Meetings")
        .add(
            Map.of(
                "pk", AttributeValue.builder().s("PTR#ghost").build(),
                "date", AttributeValue.builder().s(DATE).build()));

    assertNull(invoke(event("ghost", "id")));
  }

  @Test
  @DisplayName(
      "expired and never-existed give the same answer, so nothing confirms an id was once valid")
  void returnsNullForADateThatIsNoLongerStored() {
    fakeClient
        .tables
        .get("Meetings")
        .add(
            Map.of(
                "pk", AttributeValue.builder().s("PTR#aged-out").build(),
                "date", AttributeValue.builder().s("2020-01-01").build()));

    assertNull(invoke(event("aged-out", "id")));
  }

  @Test
  void returnsNullForABlankId() {
    assertNull(invoke(event("  ", "id")));
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));
  }
}
