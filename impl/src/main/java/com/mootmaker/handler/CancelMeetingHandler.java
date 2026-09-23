package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.realtime.DayBroadcaster;
import com.mootmaker.realtime.DaysInvalidatedPublisher;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.cancelMeeting}. Allowed for the meeting's
 * organiser, or for anyone if the caller is admin - same self-or-admin shape as {@link
 * UpdateMeetingHandler}, including checking existence before authorisation so a not-found id
 * reports {@code MeetingNotFound} rather than leaking a 403.
 *
 * <p>A hard delete: the meeting is removed from its day's {@code meetings} list entirely, with no
 * cancelled/tombstone state left behind - {@code DayRepository.mutate}'s existing pointer diff
 * deletes the meeting's {@code PTR#} row automatically, in the same transaction, as soon as the
 * day's own before/after id sets show it as lost. No restriction based on whether the meeting is in
 * the past, in progress, or upcoming.
 */
public class CancelMeetingHandler implements RequestHandler<Map<String, Object>, Object> {

  private final DayRepository days;
  private final PersonRepository people;
  private final DayBroadcaster broadcaster;

  public CancelMeetingHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  CancelMeetingHandler(
      final DynamoDbClient dynamoDbClient,
      final String meetingsTableName,
      final String peopleTableName) {
    this(
        dynamoDbClient,
        meetingsTableName,
        peopleTableName,
        DaysInvalidatedPublisher.fromEnvironment());
  }

  CancelMeetingHandler(
      final DynamoDbClient dynamoDbClient,
      final String meetingsTableName,
      final String peopleTableName,
      final DayBroadcaster broadcaster) {
    this(
        new DayRepository(dynamoDbClient, meetingsTableName),
        new PersonRepository(dynamoDbClient, peopleTableName),
        broadcaster);
  }

  CancelMeetingHandler(final DayRepository days, final PersonRepository people) {
    this(days, people, DaysInvalidatedPublisher.fromEnvironment());
  }

  CancelMeetingHandler(
      final DayRepository days, final PersonRepository people, final DayBroadcaster broadcaster) {
    this.days = days;
    this.people = people;
    this.broadcaster = broadcaster;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String meetingId = (String) arguments.get("id");

    final Optional<String> date = days.findDateOfMeeting(meetingId);
    if (date.isEmpty()) {
      return rejected(List.of(MeetingError.MeetingNotFound.name()));
    }

    final Day currentDay = days.read(date.get());
    final Optional<MeetingRecord> existing =
        currentDay.meetings().stream().filter(m -> m.id().equals(meetingId)).findFirst();
    if (existing.isEmpty()) {
      return rejected(List.of(MeetingError.MeetingNotFound.name()));
    }

    final Map<String, Object> identity = castToMap(event.get("identity"));
    final String callerSub = (String) identity.get("sub");
    final Person organiser = people.findById(existing.get().organiserId()).orElse(null);
    final boolean isSelf =
        callerSub != null && organiser != null && organiser.cognitoSubs().contains(callerSub);
    if (!isSelf && !Identity.isAdmin(event)) {
      throw new IllegalStateException(
          "Forbidden: can only cancel a meeting you organise unless you are admin");
    }

    try {
      days.mutate(
          date.get(),
          day -> {
            final boolean stillExists =
                day.meetings().stream().anyMatch(m -> m.id().equals(meetingId));
            if (!stillExists) {
              throw new MeetingRejected(List.of(MeetingError.MeetingNotFound.name()));
            }
            return day.withMeetings(
                day.meetings().stream().filter(m -> !m.id().equals(meetingId)).toList());
          });
    } catch (final MeetingRejected rejected) {
      return rejected(rejected.errors());
    }

    broadcaster.publish(List.of(date.get()));
    return cancelled(date.get());
  }

  private Map<String, Object> cancelled(final String date) {
    final Map<String, Object> result = new HashMap<>();
    result.put("day", dayResponse(date));
    result.put("errors", List.of());
    return result;
  }

  private static Map<String, Object> rejected(final List<String> errors) {
    final Map<String, Object> result = new HashMap<>();
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
