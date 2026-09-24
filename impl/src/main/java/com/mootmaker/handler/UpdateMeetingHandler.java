package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayItemTooLargeException;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.model.Attendee;
import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.Day;
import com.mootmaker.model.Meeting;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.realtime.DayBroadcaster;
import com.mootmaker.realtime.DaysInvalidatedPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.updateMeeting}. Allowed for the meeting's
 * organiser, or for anyone if the caller is admin - mirroring {@code UpdatePersonHandler}'s
 * self-or-admin shape exactly (existence checked first, so a not-found id reports {@code
 * MeetingNotFound} rather than leaking a 403; authorisation checked against the CURRENT record's
 * organiser, before any field-level errors are reported, so a non-authorised caller always gets
 * {@code Forbidden} rather than a typed validation list they were never entitled to see). Full
 * field replacement, exactly like {@code updateRoom}/{@code updatePerson}: every field in {@code
 * MeetingInput} is applied, including reassigning the organiser or attendees.
 *
 * <p>Same-date edits reuse {@link DayRepository#mutate} exactly like {@code CreateMeetingHandler} -
 * a version conflict re-validates against the day as it now is, and re-checks the meeting still
 * exists, on every retry. A requested date different from the meeting's current one instead goes
 * through {@link DayRepository#moveMeeting} - see its own doc comment for why that needs two
 * separately committed writes rather than one transaction. <b>Known limitation</b>: unlike the
 * same-date path, {@code moveMeeting}'s own internal retry (on a version conflict for the
 * destination day alone) does not re-run room-availability/day-full validation - the window is a
 * version conflict on that exact day item landing at the exact moment a room double-booking also
 * became true, which is narrow enough that re-validating up front (this class still does, before
 * calling {@code moveMeeting} at all) was judged sufficient for a first cut rather than threading
 * validation into {@code moveMeeting}'s own retry the way {@code mutate}'s caller-supplied function
 * does.
 */
public class UpdateMeetingHandler implements RequestHandler<Map<String, Object>, Object> {

  private final DayRepository days;
  private final PersonRepository people;
  private final MeetingValidator validator;
  private final DayBroadcaster broadcaster;

  public UpdateMeetingHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  UpdateMeetingHandler(
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

  UpdateMeetingHandler(
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

  UpdateMeetingHandler(
      final DayRepository days, final RoomRepository rooms, final PersonRepository people) {
    this(days, rooms, people, DaysInvalidatedPublisher.fromEnvironment());
  }

  UpdateMeetingHandler(
      final DayRepository days,
      final RoomRepository rooms,
      final PersonRepository people,
      final DayBroadcaster broadcaster) {
    this.days = days;
    this.people = people;
    this.validator = new MeetingValidator(rooms, people);
    this.broadcaster = broadcaster;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String meetingId = (String) arguments.get("id");
    final Map<String, Object> meetingInput = castToMap(arguments.get("meeting"));

    final MeetingValidator.Validated validated =
        validator.validateRequest(meetingInput, days.boundaries());
    final List<String> errors = new ArrayList<>(validated.errors());

    final Optional<String> currentDate = days.findDateOfMeeting(meetingId);
    if (currentDate.isEmpty()) {
      errors.add(MeetingError.MeetingNotFound.name());
      return rejected(errors);
    }

    final Day currentDay = days.read(currentDate.get());
    final Optional<MeetingRecord> existing =
        currentDay.meetings().stream().filter(m -> m.id().equals(meetingId)).findFirst();
    if (existing.isEmpty()) {
      // The pointer resolved but the day's own list doesn't have it - an inconsistency that
      // should not happen, but treated the same as a genuine not-found rather than risking an
      // NPE below, for the same reason MeetingNotFound exists at all.
      errors.add(MeetingError.MeetingNotFound.name());
      return rejected(errors);
    }

    final Map<String, Object> identity = castToMap(event.get("identity"));
    final String callerSub = (String) identity.get("sub");
    final Person organiser = people.findById(existing.get().organiserId()).orElse(null);
    final boolean isSelf =
        callerSub != null && organiser != null && organiser.cognitoSubs().contains(callerSub);
    if (!isSelf && !Identity.isAdmin(event)) {
      throw new IllegalStateException(
          "Forbidden: can only edit a meeting you organise unless you are admin");
    }

    if (!validated.canInspectDay()) {
      return rejected(errors.isEmpty() ? List.of(MeetingError.EndBeforeStart.name()) : errors);
    }

    final String requestedDate = validated.date();
    final boolean movingDay = !requestedDate.equals(currentDate.get());
    final List<MeetingRecord> dayStateComparisonList =
        movingDay ? days.read(requestedDate).meetings() : currentDay.meetings();
    errors.addAll(MeetingValidator.dayStateErrors(dayStateComparisonList, validated, meetingId));
    if (!errors.isEmpty()) {
      return rejected(errors);
    }

    final MeetingRecord newRecord =
        new MeetingRecord(
            meetingId,
            validated.roomId(),
            validated.organiserId(),
            validated.attendeeIds(),
            preservedAttendeeStatuses(existing.get(), validated.attendeeIds()),
            validated.subject(),
            validated.startTime().format(MeetingRecord.DATE_TIME_FORMAT),
            validated.endTime().format(MeetingRecord.DATE_TIME_FORMAT));

    try {
      if (movingDay) {
        days.moveMeeting(meetingId, currentDate.get(), requestedDate, newRecord);
        broadcaster.publish(List.of(currentDate.get(), requestedDate));
      } else {
        days.mutate(
            requestedDate,
            day -> {
              final boolean stillExists =
                  day.meetings().stream().anyMatch(m -> m.id().equals(meetingId));
              if (!stillExists) {
                throw new MeetingRejected(List.of(MeetingError.MeetingNotFound.name()));
              }
              final List<String> conflicts =
                  MeetingValidator.dayStateErrors(day.meetings(), validated, meetingId);
              if (!conflicts.isEmpty()) {
                throw new MeetingRejected(conflicts);
              }
              return day.withMeetings(
                  day.meetings().stream()
                      .map(m -> m.id().equals(meetingId) ? newRecord : m)
                      .toList());
            });
        broadcaster.publish(List.of(requestedDate));
      }
    } catch (final MeetingRejected rejected) {
      return rejected(rejected.errors());
    } catch (final DayItemTooLargeException e) {
      return rejected(List.of(MeetingError.DayIsFull.name()));
    }

    return updated(newRecord, validated);
  }

  private Map<String, Object> updated(
      final MeetingRecord record, final MeetingValidator.Validated validated) {
    final Meeting meeting =
        new Meeting(
            record.id(),
            validated.room(),
            validated.organiser(),
            IntStream.range(0, validated.attendees().size())
                .mapToObj(
                    i ->
                        new Attendee(
                            validated.attendees().get(i), record.attendeeStatuses().get(i)))
                .toList(),
            record.subject(),
            record.startTime(),
            record.endTime());
    final Map<String, Object> result = new HashMap<>();
    result.put("meeting", meeting.toResponseMap());
    result.put("day", dayResponse(record.startTime().substring(0, 10)));
    result.put("errors", List.of());
    return result;
  }

  /**
   * An attendee who remains on the meeting keeps their existing response; only a newly added
   * attendee starts at {@code NoResponse}. Without this, every edit would silently reset every
   * attendee's RSVP - the webapp's {@code UPDATE_MEETING} call never sends {@code
   * MeetingInput.attendeeStatuses} (that override exists only for {@code mootmaker-demo-data}, see
   * {@code MeetingValidator}'s own doc comment), so {@link MeetingValidator.Validated#
   * attendeeStatuses()} would otherwise default every one of them to {@code NoResponse} on every
   * single update, including one that only changed the subject or time. Order matches {@code
   * newAttendeeIds} exactly, the same index-parity convention {@code MeetingRecord} and this
   * handler's own {@link #updated} already rely on.
   */
  private static List<AttendeeStatus> preservedAttendeeStatuses(
      final MeetingRecord existing, final List<String> newAttendeeIds) {
    final Map<String, AttendeeStatus> previousStatusByAttendeeId = new HashMap<>();
    final List<String> oldAttendeeIds = existing.attendeeIds();
    final List<AttendeeStatus> oldStatuses = existing.attendeeStatuses();
    for (int i = 0; i < oldAttendeeIds.size(); i++) {
      previousStatusByAttendeeId.put(oldAttendeeIds.get(i), oldStatuses.get(i));
    }
    return newAttendeeIds.stream()
        .map(id -> previousStatusByAttendeeId.getOrDefault(id, AttendeeStatus.NoResponse))
        .toList();
  }

  private static Map<String, Object> rejected(final List<String> errors) {
    final Map<String, Object> result = new HashMap<>();
    result.put("meeting", null);
    result.put("day", null);
    result.put("errors", errors);
    return result;
  }

  /**
   * Every meeting on {@code date}, id-only for room/organiser/attendee-person - see
   * MeetingResponse.
   */
  private Map<String, Object> dayResponse(final String date) {
    final Map<String, Object> response = new HashMap<>();
    response.put("date", date);
    response.put(
        "meetings",
        days.read(date).meetings().stream()
            .map(record -> MeetingResponse.of(record, Map.of(), Map.of(), false, false))
            .toList());
    return response;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }
}
