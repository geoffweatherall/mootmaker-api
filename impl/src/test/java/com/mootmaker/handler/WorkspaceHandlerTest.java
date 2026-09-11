package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.limits.Limits;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class WorkspaceHandlerTest {

  private static final String DATE = "2026-09-14";

  private FakeDynamoDbClient fakeClient;
  private WorkspaceHandler handler;

  @BeforeEach
  void setUp() {
    fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(
        "Rooms", new ArrayList<>(List.of(new Room("room-1", "Kaikoura", 8).toItem())));
    fakeClient.tables.put(
        "People",
        new ArrayList<>(
            List.of(
                new Person("person-1", "Ada Lovelace", "sub-1").toItem(),
                new Person("person-2", "Alan Turing", "sub-2").toItem())));
    handler = new WorkspaceHandler(fakeClient, "Meetings", "Rooms", "People");
  }

  private static MeetingRecord meeting(final String id, final String date) {
    return new MeetingRecord(
        id,
        "room-1",
        "person-1",
        List.of("person-2"),
        "Standup",
        date + "T09:00:00",
        date + "T09:30:00");
  }

  /** selectionSetList paths are relative to the resolved field, so they start below `workspace`. */
  private static Map<String, Object> event(final List<String> dates, final String... selections) {
    final Map<String, Object> arguments = new HashMap<>();
    if (dates != null) {
      arguments.put("dates", dates);
    }
    return Map.of(
        "identity", Map.of("sub", "sub-1", "claims", Map.of("custom:personId", "person-1")),
        "arguments", arguments,
        "info", Map.of("selectionSetList", List.of(selections)));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @Nested
  @DisplayName("fetches only what was selected")
  class SelectionAware {

    @Test
    @DisplayName("a calendar paging to a new week reads days and nothing else")
    void doesNoReferenceDataWorkWhenOnlyDaysAreSelected() {
      DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", DATE));

      final Map<String, Object> workspace = invoke(event(List.of(DATE), "days", "days/date"));

      assertNotNull(workspace.get("days"));
      assertNull(workspace.get("people"), "people was not selected, so it must not be fetched");
      assertNull(workspace.get("rooms"));
      assertNull(workspace.get("me"));
      assertNull(workspace.get("boundaries"));
    }

    @Test
    @DisplayName("a reference-data refresh omits dates entirely and reads no day items")
    void readsNoDaysWhenNoDatesAreGiven() {
      final Map<String, Object> workspace =
          invoke(event(null, "rooms", "rooms/id", "people", "people/id"));

      assertEquals(1, ((List<?>) workspace.get("rooms")).size());
      assertEquals(2, ((List<?>) workspace.get("people")).size());
      assertNull(workspace.get("days"));
    }

    @Test
    @DisplayName("id-only meeting selections resolve no rooms or people")
    void resolvesNoNamesWhenOnlyIdsAreAsked() {
      DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", DATE));

      final Map<String, Object> workspace =
          invoke(
              event(
                  List.of(DATE),
                  "days",
                  "days/date",
                  "days/meetings",
                  "days/meetings/id",
                  "days/meetings/room",
                  "days/meetings/room/id"));

      final Map<String, Object> room = firstMeeting(workspace, "room");
      assertEquals("room-1", room.get("id"));
      assertNull(
          room.get("name"), "the name was not asked for, so no room lookup should have happened");
    }

    @Test
    void resolvesNamesWhenTheyAreAsked() {
      DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", DATE));

      final Map<String, Object> workspace =
          invoke(
              event(
                  List.of(DATE),
                  "days",
                  "days/meetings",
                  "days/meetings/room",
                  "days/meetings/room/name",
                  "days/meetings/attendees",
                  "days/meetings/attendees/name"));

      assertEquals("Kaikoura", firstMeeting(workspace, "room").get("name"));
      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> attendees =
          (List<Map<String, Object>>) firstMeetingMap(workspace).get("attendees");
      assertEquals("Alan Turing", attendees.getFirst().get("name"));
    }
  }

  @Nested
  @DisplayName("days")
  class Days {

    @Test
    @DisplayName("come back in the order asked for, including dates holding nothing")
    void areNeverSparse() {
      DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "2026-09-15"));

      final Map<String, Object> workspace =
          invoke(event(List.of("2026-09-14", "2026-09-15", "2026-09-16"), "days", "days/date"));

      @SuppressWarnings("unchecked")
      final List<Map<String, Object>> days = (List<Map<String, Object>>) workspace.get("days");
      assertEquals(
          List.of("2026-09-14", "2026-09-15", "2026-09-16"),
          days.stream().map(d -> d.get("date")).toList());
      // An empty day means empty, not unfetched - the distinction a day-keyed cache depends on.
      assertTrue(((List<?>) days.getFirst().get("meetings")).isEmpty());
    }
  }

  @Nested
  @DisplayName("bounds the response")
  class Bounds {

    @Test
    @DisplayName("refuses more dates than the limit rather than truncating")
    void rejectsTooManyDates() {
      final List<String> tooMany =
          IntStream.rangeClosed(1, Limits.MAX_DATES_PER_REQUEST + 1)
              .mapToObj(i -> "2026-%02d-%02d".formatted((i % 12) + 1, (i % 28) + 1))
              .toList();

      final IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> invoke(event(tooMany, "days")));
      assertTrue(thrown.getMessage().contains("Too many dates"), thrown.getMessage());
    }

    @Test
    @DisplayName(
        "fails fast once the running meeting count would exceed the cap, before building the"
            + " response")
    void rejectsAResponseThatWouldBeTooLarge() {
      // The static limits alone permit a request that cannot be answered, which is exactly why
      // the check accumulates rather than multiplying limits together.
      final List<String> dates = new ArrayList<>();
      for (int day = 1; day <= 10; day++) {
        final int dayNumber = day;
        final String date = "2026-09-%02d".formatted(dayNumber);
        dates.add(date);
        final List<MeetingRecord> many =
            IntStream.range(0, 300)
                .mapToObj(i -> meeting("m-%d-%d".formatted(dayNumber, i), date))
                .toList();
        fakeClient
            .tables
            .computeIfAbsent("Meetings", _ -> new ArrayList<>())
            .addAll(DayFixtures.dayItems(many.toArray(new MeetingRecord[0])));
      }

      final IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> invoke(event(dates, "days", "days/meetings")));
      assertTrue(thrown.getMessage().contains("too large"), thrown.getMessage());
    }
  }

  @Nested
  @DisplayName("me")
  class Me {

    @Test
    void comesFromThePersonIdClaim() {
      final Map<String, Object> workspace = invoke(event(null, "me", "me/id", "me/name"));

      @SuppressWarnings("unchecked")
      final Map<String, Object> me = (Map<String, Object>) workspace.get("me");
      assertEquals("person-1", me.get("id"));
      assertEquals("Ada Lovelace", me.get("name"));
    }

    @Test
    @DisplayName(
        "is null for a token with no claim - a machine-to-machine caller has no user at all")
    void isNullWithoutAClaim() {
      final Map<String, Object> event =
          Map.of(
              "identity", Map.of("sub", "client-id"),
              "arguments", Map.of(),
              "info", Map.of("selectionSetList", List.of("me", "me/id")));

      assertNull(invoke(event).get("me"));
    }
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstMeetingMap(final Map<String, Object> workspace) {
    final List<Map<String, Object>> days = (List<Map<String, Object>>) workspace.get("days");
    return ((List<Map<String, Object>>) days.getFirst().get("meetings")).getFirst();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstMeeting(
      final Map<String, Object> workspace, final String field) {
    return (Map<String, Object>) firstMeetingMap(workspace).get(field);
  }
}
