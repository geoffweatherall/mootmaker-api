package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.RecordingBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers designs/attendee-response-status.md's Testing impacts: self-only enforcement,
 * valid/invalid callers, meeting-not-found, caller-not-an-attendee, and the two concurrency shapes
 * this design specifically calls out - see {@link RespondToMeetingHandler}'s own class javadoc.
 * Real DynamoDB conditional-write conflicts still need acceptance-layer coverage (this uses {@link
 * FakeDynamoDbClient}, not a real table) - these tests pin the handler's own re-lookup logic on
 * retry.
 */
class RespondToMeetingHandlerTest {

  private static final String DATE = "2026-07-01";

  private FakeDynamoDbClient fakeClient;
  private RecordingBroadcaster broadcaster;
  private RespondToMeetingHandler handler;

  @BeforeEach
  void setUp() {
    fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(
        "Rooms", new ArrayList<>(List.of(new Room("room-1", "Kaikoura", 8).toItem())));
    fakeClient.tables.put(
        "People",
        new ArrayList<>(
            List.of(
                new Person("organiser-1", "Ada Lovelace", "sub-organiser").toItem(),
                new Person("attendee-1", "Alan Turing", "sub-1").toItem(),
                new Person("attendee-2", "Grace Hopper", "sub-2").toItem(),
                new Person("unrelated-1", "Bystander", "sub-unrelated").toItem())));
    broadcaster = new RecordingBroadcaster();
    handler = new RespondToMeetingHandler(fakeClient, "Meetings", "Rooms", "People", broadcaster);
  }

  private static MeetingRecord meeting(final String id, final String... attendeeIds) {
    return new MeetingRecord(
        id,
        "room-1",
        "organiser-1",
        List.of(attendeeIds),
        Collections.nCopies(attendeeIds.length, AttendeeStatus.NoResponse),
        "Standup",
        DATE + "T09:00:00",
        DATE + "T09:30:00");
  }

  private static Map<String, Object> event(
      final String sub, final String meetingId, final String status) {
    return Map.of(
        "identity",
            Map.of(
                "sub", sub, "claims", Map.of("custom:personId", sub.replace("sub-", "attendee-"))),
        "arguments", Map.of("meetingId", meetingId, "status", status));
  }

  /** Organiser and unrelated-bystander subs don't follow the sub-N -> attendee-N convention. */
  private static Map<String, Object> eventAs(
      final String sub, final String personId, final String meetingId, final String status) {
    return Map.of(
        "identity", Map.of("sub", sub, "claims", Map.of("custom:personId", personId)),
        "arguments", Map.of("meetingId", meetingId, "status", status));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke(final Map<String, Object> event) {
    return (Map<String, Object>) handler.handleRequest(event, null);
  }

  @SuppressWarnings("unchecked")
  private static List<String> errorsOf(final Map<String, Object> result) {
    return (List<String>) result.get("errors");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> attendeesOf(final Map<String, Object> result) {
    return (List<Map<String, Object>>)
        ((Map<String, Object>) result.get("meeting")).get("attendees");
  }

  @SuppressWarnings("unchecked")
  private static String statusOf(final Map<String, Object> result, final String personId) {
    return attendeesOf(result).stream()
        .filter(a -> personId.equals(((Map<String, Object>) a.get("person")).get("id")))
        .map(a -> (String) a.get("status"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));
  }

  @Test
  void reportsNoLinkedPersonWhenTheCallerHasNoPersonOfTheirOwn() {
    final MeetingRecord meeting = meeting("m-1", "attendee-1");
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting);

    final Map<String, Object> result =
        invoke(eventAs("sub-ghost", "person-does-not-exist", "m-1", "Going"));

    assertNull(result.get("meeting"));
    assertEquals(List.of("NoLinkedPerson"), errorsOf(result));
  }

  @Test
  void reportsMeetingNotFoundForAnUnknownId() {
    final Map<String, Object> result = invoke(event("sub-1", "does-not-exist", "Going"));

    assertNull(result.get("meeting"));
    assertEquals(List.of("MeetingNotFound"), errorsOf(result));
  }

  @Test
  @DisplayName("the organiser has nothing to respond with - they are never in attendeeIds")
  void reportsNotAnAttendeeWhenTheCallerIsTheOrganiser() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1"));

    final Map<String, Object> result =
        invoke(eventAs("sub-organiser", "organiser-1", "m-1", "Going"));

    assertNull(result.get("meeting"));
    assertEquals(List.of("NotAnAttendee"), errorsOf(result));
  }

  @Test
  void reportsNotAnAttendeeWhenTheCallerIsUnrelatedToTheMeeting() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1"));

    final Map<String, Object> result =
        invoke(eventAs("sub-unrelated", "unrelated-1", "m-1", "Going"));

    assertNull(result.get("meeting"));
    assertEquals(List.of("NotAnAttendee"), errorsOf(result));
  }

  @Test
  void setsTheCallersOwnStatusAndReturnsTheUpdatedMeeting() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1", "attendee-2"));

    final Map<String, Object> result = invoke(event("sub-1", "m-1", "Going"));

    assertTrue(errorsOf(result).isEmpty());
    assertEquals("Going", statusOf(result, "attendee-1"));
  }

  @Test
  @DisplayName("only the caller's own index changes - everyone else's status is untouched")
  void onlyEverTouchesTheCallersOwnStatus() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1", "attendee-2"));

    final Map<String, Object> result = invoke(event("sub-1", "m-1", "Going"));

    assertEquals("NoResponse", statusOf(result, "attendee-2"));
  }

  @Test
  void changingAResponseAgainOverwritesRatherThanAccumulating() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1"));

    invoke(event("sub-1", "m-1", "Going"));
    final Map<String, Object> result = invoke(event("sub-1", "m-1", "Maybe"));

    assertEquals("Maybe", statusOf(result, "attendee-1"));
  }

  @Test
  void persistsTheStatusSoItSurvivesAReRead() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1"));

    invoke(event("sub-1", "m-1", "NotGoing"));

    final MeetingRecord stored = DayFixtures.meetingsIn(fakeClient, "Meetings").getFirst();
    assertEquals(AttendeeStatus.NotGoing, stored.attendeeStatuses().getFirst());
  }

  @Test
  @DisplayName("a successful response broadcasts the meeting's own date, once")
  void broadcastsOnSuccess() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1"));

    invoke(event("sub-1", "m-1", "Going"));

    assertEquals(List.of(List.of(DATE)), broadcaster.broadcasts);
  }

  @Test
  void broadcastsNothingWhenRejected() {
    invoke(event("sub-1", "does-not-exist", "Going"));

    assertTrue(broadcaster.broadcasts.isEmpty());
  }

  /**
   * Directly rewrites one meeting's status at the given attendee index, exactly as {@link
   * RespondToMeetingHandler} would - but via {@link DayRepository} alone, not a second handler
   * invocation. A full nested {@code handleRequest} call from inside {@code FakeDynamoDbClient}'s
   * {@code beforeWrite} hook deadlocks: that hook runs while the fake holds its own monitor (see
   * {@code transactWriteItems}), and {@code PersonRepository}'s batch-get is asynchronous, so the
   * nested call's worker thread blocks forever trying to re-enter it. Going straight through {@code
   * DayRepository} exercises the exact same day-item contention without that async hop.
   */
  private void interferingResponse(
      final String meetingId, final String attendeeId, final AttendeeStatus status) {
    new DayRepository(fakeClient, "Meetings")
        .mutate(
            DATE,
            day ->
                day.withMeetings(
                    day.meetings().stream()
                        .map(
                            m -> {
                              if (!m.id().equals(meetingId)) {
                                return m;
                              }
                              final int index = m.attendeeIds().indexOf(attendeeId);
                              final List<AttendeeStatus> statuses =
                                  new ArrayList<>(m.attendeeStatuses());
                              statuses.set(index, status);
                              return new MeetingRecord(
                                  m.id(),
                                  m.roomId(),
                                  m.organiserId(),
                                  m.attendeeIds(),
                                  statuses,
                                  m.subject(),
                                  m.startTime(),
                                  m.endTime());
                            })
                        .toList()));
  }

  /**
   * Two different attendees of the SAME meeting respond at (simulated) the same moment - different
   * indices, but one whole-day rewrite each. See the class javadoc's first concurrency shape.
   */
  @Test
  @DisplayName("two attendees of the same meeting responding concurrently both land")
  void survivesConcurrentResponsesToTheSameMeeting() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1", "attendee-2"));

    // attendee-2's response sneaks in between attendee-1's read and write, exactly once - so
    // attendee-1's write must retry against attendee-2's already-applied change, not clobber it.
    final AtomicBoolean interfered = new AtomicBoolean(false);
    fakeClient.beforeWrite =
        () -> {
          if (interfered.compareAndSet(false, true)) {
            interferingResponse("m-1", "attendee-2", AttendeeStatus.Maybe);
          }
        };

    final Map<String, Object> result = invoke(event("sub-1", "m-1", "Going"));

    assertTrue(errorsOf(result).isEmpty());
    assertEquals("Going", statusOf(result, "attendee-1"));
    assertEquals("Maybe", statusOf(result, "attendee-2"));
    assertTrue(fakeClient.transactionAttempts >= 2, "expected a retry after the interfering write");
  }

  /**
   * One person responds to two DIFFERENT meetings that share a calendar day. Because storage is one
   * item per day, these are not independent writes even though they look that way from the API -
   * both hit the exact same day item's optimistic lock. See the class javadoc's second concurrency
   * shape, called out as the more likely one to produce a real conflict.
   */
  @Test
  @DisplayName(
      "responding to two different meetings on the same day both land, despite sharing a day item")
  void survivesConcurrentResponsesToDifferentMeetingsOnTheSameDay() {
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-1", "attendee-1"));
    DayFixtures.addMeeting(fakeClient, "Meetings", meeting("m-2", "attendee-1"));

    final AtomicBoolean interfered = new AtomicBoolean(false);
    fakeClient.beforeWrite =
        () -> {
          if (interfered.compareAndSet(false, true)) {
            interferingResponse("m-2", "attendee-1", AttendeeStatus.NotGoing);
          }
        };

    final Map<String, Object> result = invoke(event("sub-1", "m-1", "Going"));

    assertTrue(errorsOf(result).isEmpty());
    assertEquals("Going", statusOf(result, "attendee-1"));
    final MeetingRecord m2 =
        DayFixtures.meetingsIn(fakeClient, "Meetings").stream()
            .filter(m -> m.id().equals("m-2"))
            .findFirst()
            .orElseThrow();
    assertEquals(AttendeeStatus.NotGoing, m2.attendeeStatuses().getFirst());
    assertTrue(fakeClient.transactionAttempts >= 2, "expected a retry after the interfering write");
  }
}
