package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancelMeetingHandlerTest {

  private FakeDynamoDbClient fakeClient;
  private CancelMeetingHandler handler;

  @BeforeEach
  void setUp() {
    fakeClient = new FakeDynamoDbClient();
    handler = new CancelMeetingHandler(fakeClient, "Meetings", "People");
    fakeClient.tables.put(
        "People",
        new ArrayList<>(
            List.of(
                new Person("organiser-1", "Ada Lovelace", "organiser-sub").toItem(),
                new Person("admin-1", "Grace Hopper", "admin-sub").toItem(),
                new Person("stranger-1", "Alan Turing", "stranger-sub").toItem())));
  }

  private static MeetingRecord existingMeeting() {
    return new MeetingRecord(
        "m-1",
        "room-a",
        "organiser-1",
        List.of(),
        List.of(),
        "Standup",
        "2026-09-24T10:00:00",
        "2026-09-24T11:00:00");
  }

  private static Map<String, Object> cancelArguments(
      final String id, final String callerSub, final String callerClass) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("id", id);
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", arguments);
    final Map<String, Object> identity = new HashMap<>();
    identity.put("sub", callerSub);
    if (callerClass != null) {
      identity.put("claims", Map.of("custom:class", callerClass));
    }
    event.put("identity", identity);
    return event;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @Test
  @DisplayName("the organiser can cancel their own meeting - it is hard-deleted, no tombstone")
  void organiserCanCancelTheirOwnMeeting() {
    DayFixtures.seed(
        fakeClient, "Meetings", DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE, existingMeeting());

    final Map<String, Object> result = invoke(cancelArguments("m-1", "organiser-sub", "standard"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty(), "errors: " + errors);

    final DayRepository days = new DayRepository(fakeClient, "Meetings");
    assertTrue(days.read("2026-09-24").meetings().isEmpty());
    assertEquals(Optional.empty(), days.findDateOfMeeting("m-1"), "pointer must be gone too");
  }

  @Test
  @DisplayName("an admin can cancel a meeting they do not organise")
  void adminCanCancelSomeoneElsesMeeting() {
    DayFixtures.seed(
        fakeClient, "Meetings", DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE, existingMeeting());

    final Map<String, Object> result = invoke(cancelArguments("m-1", "admin-sub", "admin"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertTrue(errors.isEmpty(), "errors: " + errors);
  }

  @Test
  @DisplayName("someone who is neither the organiser nor admin is forbidden")
  void neitherOrganiserNorAdminIsForbidden() {
    DayFixtures.seed(
        fakeClient, "Meetings", DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE, existingMeeting());

    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> invoke(cancelArguments("m-1", "stranger-sub", "standard")));
    assertTrue(thrown.getMessage().contains("Forbidden"), thrown.getMessage());
  }

  @Test
  @DisplayName("an id that doesn't resolve to any meeting reports MeetingNotFound, not a crash")
  void unknownIdReportsMeetingNotFound() {
    fakeClient
        .tables
        .computeIfAbsent("Meetings", _ -> new ArrayList<>())
        .add(DayFixtures.retentionConfig(DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE));

    final Map<String, Object> result =
        invoke(cancelArguments("never-existed", "organiser-sub", "standard"));

    @SuppressWarnings("unchecked")
    final List<String> errors = (List<String>) result.get("errors");
    assertEquals(List.of(MeetingError.MeetingNotFound.name()), errors);
  }

  @Test
  @DisplayName("two admins cancelling the same meeting: the second gets a graceful error")
  void aSecondCancelOfAnAlreadyCancelledMeetingIsGraceful() {
    DayFixtures.seed(
        fakeClient, "Meetings", DayFixtures.DEFAULT_EARLIEST_RETAINED_DATE, existingMeeting());

    final Map<String, Object> first = invoke(cancelArguments("m-1", "admin-1sub", "admin"));
    @SuppressWarnings("unchecked")
    final List<String> firstErrors = (List<String>) first.get("errors");
    assertTrue(firstErrors.isEmpty());

    final Map<String, Object> second = invoke(cancelArguments("m-1", "admin-2sub", "admin"));
    @SuppressWarnings("unchecked")
    final List<String> secondErrors = (List<String>) second.get("errors");
    assertEquals(List.of(MeetingError.MeetingNotFound.name()), secondErrors);
  }
}
