package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.model.Attendee;
import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.Day;
import com.mootmaker.model.Meeting;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.RespondToMeetingError;
import com.mootmaker.model.Room;
import com.mootmaker.realtime.DayBroadcaster;
import com.mootmaker.realtime.DaysInvalidatedPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.respondToMeeting}. Self-only, mirroring {@link
 * UpdateMyPreferencesHandler}'s no-id-argument pattern - the caller can only ever set their own
 * response, never anyone else's (see designs/attendee-response-status.md's "Trade-offs and
 * decisions").
 *
 * <p><b>Concurrency is the whole reason this goes through {@code days.mutate} rather than a direct
 * write</b>, exactly like {@link CreateMeetingHandler}. Two shapes of race are possible on the same
 * day item, both re-validated on every retry attempt rather than assumed to still hold from the
 * first read:
 *
 * <ul>
 *   <li>Two different attendees of the <b>same meeting</b> responding at the same time - different
 *       indices into the same meeting's {@code attendeeStatuses}, but still one whole-day rewrite
 *       each.
 *   <li>One person responding to <b>several different meetings that share a calendar day</b> -
 *       because storage is one item per day, not per meeting, these contend on the exact same
 *       optimistic lock even though they look independent from the API surface.
 * </ul>
 *
 * <p>{@code days.mutate}'s existing retry loop (shared with every other write to this table) is
 * what makes both cases safe: a losing writer re-reads the day, re-locates the meeting and the
 * caller's index inside it, and re-applies its change against current state rather than a stale
 * snapshot.
 */
public class RespondToMeetingHandler implements RequestHandler<Map<String, Object>, Object> {

  private final DayRepository days;
  private final PersonRepository people;
  private final RoomRepository rooms;
  private final DayBroadcaster broadcaster;

  public RespondToMeetingHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  RespondToMeetingHandler(
      final DynamoDbClient dynamoDbClient,
      final String meetingsTableName,
      final String roomsTableName,
      final String peopleTableName) {
    this(
        dynamoDbClient,
        meetingsTableName,
        roomsTableName,
        peopleTableName,
        DaysInvalidatedPublisher.fromEnvironment());
  }

  RespondToMeetingHandler(
      final DynamoDbClient dynamoDbClient,
      final String meetingsTableName,
      final String roomsTableName,
      final String peopleTableName,
      final DayBroadcaster broadcaster) {
    this(
        new DayRepository(dynamoDbClient, meetingsTableName),
        new RoomRepository(dynamoDbClient, roomsTableName),
        new PersonRepository(dynamoDbClient, peopleTableName),
        broadcaster);
  }

  RespondToMeetingHandler(
      final DayRepository days,
      final RoomRepository rooms,
      final PersonRepository people,
      final DayBroadcaster broadcaster) {
    this.days = days;
    this.rooms = rooms;
    this.people = people;
    this.broadcaster = broadcaster;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final Optional<Person> caller = Identity.personId(event).flatMap(people::findById);
    if (caller.isEmpty()) {
      return rejected(RespondToMeetingError.NoLinkedPerson);
    }

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String meetingId = (String) arguments.get("meetingId");
    final AttendeeStatus status = AttendeeStatus.valueOf((String) arguments.get("status"));

    final Optional<String> date = days.findDateOfMeeting(meetingId);
    if (date.isEmpty()) {
      return rejected(RespondToMeetingError.MeetingNotFound);
    }

    // Re-checked inside mutate too (see class javadoc) - this pass exists only so a caller whose
    // request can never succeed (bad id, not an attendee) gets a fast, specific answer instead of
    // silently entering a retry loop that would keep failing the same way.
    final Day currentDay = days.read(date.get());
    if (currentDay.meetings().stream().noneMatch(m -> m.id().equals(meetingId))) {
      return rejected(RespondToMeetingError.MeetingNotFound);
    }

    try {
      final AtomicReference<MeetingRecord> written = new AtomicReference<>();
      days.mutate(
          date.get(),
          day -> {
            final MeetingRecord meeting =
                day.meetings().stream()
                    .filter(m -> m.id().equals(meetingId))
                    .findFirst()
                    .orElseThrow(
                        () -> new RespondToMeetingRejected(RespondToMeetingError.MeetingNotFound));
            final int index = meeting.attendeeIds().indexOf(caller.get().id());
            if (index < 0) {
              throw new RespondToMeetingRejected(RespondToMeetingError.NotAnAttendee);
            }
            final List<AttendeeStatus> statuses = new ArrayList<>(meeting.attendeeStatuses());
            statuses.set(index, status);
            final MeetingRecord updated =
                new MeetingRecord(
                    meeting.id(),
                    meeting.roomId(),
                    meeting.organiserId(),
                    meeting.attendeeIds(),
                    statuses,
                    meeting.subject(),
                    meeting.startTime(),
                    meeting.endTime());
            written.set(updated);
            return day.withMeetings(
                day.meetings().stream().map(m -> m.id().equals(meetingId) ? updated : m).toList());
          });

      // Only after the write has committed, same reasoning as CreateMeetingHandler - a rejected
      // response must broadcast nothing.
      broadcaster.publish(List.of(date.get()));
      return created(resolve(written.get()));
    } catch (final RespondToMeetingRejected rejected) {
      return rejected(rejected.error());
    }
  }

  /** Resolves room/organiser/every attendee, matching the other mutations' response shape. */
  private Meeting resolve(final MeetingRecord record) {
    final Map<String, Room> roomsById = rooms.loadByIds(Set.of(record.roomId()));
    final Map<String, Person> peopleById =
        people.loadByIds(
            Stream.concat(Stream.of(record.organiserId()), record.attendeeIds().stream())
                .collect(Collectors.toSet()));

    final Room room =
        roomsById.getOrDefault(record.roomId(), new Room(record.roomId(), "Deleted room", 0));
    final Person organiser =
        peopleById.getOrDefault(
            record.organiserId(), new Person(record.organiserId(), "Deleted user"));
    final List<Attendee> attendees =
        IntStream.range(0, record.attendeeIds().size())
            .mapToObj(
                i ->
                    new Attendee(
                        peopleById.getOrDefault(
                            record.attendeeIds().get(i),
                            new Person(record.attendeeIds().get(i), "Deleted user")),
                        record.attendeeStatuses().get(i)))
            .toList();

    return new Meeting(
        record.id(),
        room,
        organiser,
        attendees,
        record.subject(),
        record.startTime(),
        record.endTime());
  }

  private static Map<String, Object> created(final Meeting meeting) {
    final Map<String, Object> result = new HashMap<>();
    result.put("meeting", meeting.toResponseMap());
    result.put("errors", List.of());
    return result;
  }

  private static Map<String, Object> rejected(final RespondToMeetingError error) {
    final Map<String, Object> result = new HashMap<>();
    result.put("meeting", null);
    result.put("errors", List.of(error.name()));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }
}
