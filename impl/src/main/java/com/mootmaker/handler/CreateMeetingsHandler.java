package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayItemTooLargeException;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.model.Boundaries;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.realtime.DayBroadcaster;
import com.mootmaker.realtime.DaysInvalidatedPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.createMeetings} - day-scoped bulk creation.
 *
 * <p>One call, one item write. mootmaker-demo-data's per-meeting fan-out collapses into one call
 * per seeded day, which is also what stops its parallel creates colliding on the day item's
 * version.
 *
 * <p><b>Meetings in one call are validated against each other, not just against what is already
 * stored.</b> That is the part a naive implementation gets wrong: two bookings for the same room at
 * the same time, sent together, would both pass a check that only looked at the existing day. Each
 * accepted meeting is folded into the running day before the next is checked.
 *
 * <p>Partial success is deliberate. A rejected input does not fail the call - it comes back as a
 * {@link MeetingError} list against its index, and everything valid is still written. Seeding a day
 * of demo data should not be all-or-nothing because one meeting overlapped.
 */
public class CreateMeetingsHandler implements RequestHandler<Map<String, Object>, Object> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CreateMeetingsHandler.class);

  private final DayRepository days;
  private final MeetingValidator validator;
  private final DayBroadcaster broadcaster;

  public CreateMeetingsHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  CreateMeetingsHandler(
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

  CreateMeetingsHandler(
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

  CreateMeetingsHandler(
      final DayRepository days, final RoomRepository rooms, final PersonRepository people) {
    this(days, rooms, people, DaysInvalidatedPublisher.fromEnvironment());
  }

  CreateMeetingsHandler(
      final DayRepository days,
      final RoomRepository rooms,
      final PersonRepository people,
      final DayBroadcaster broadcaster) {
    this.days = days;
    this.validator = new MeetingValidator(rooms, people);
    this.broadcaster = broadcaster;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final Map<String, Object> arguments = (Map<String, Object>) event.get("arguments");
    final String date = (String) arguments.get("date");
    final List<Map<String, Object>> inputs =
        (List<Map<String, Object>>) arguments.getOrDefault("meetings", List.of());

    if (inputs.size() > Limits.MAX_MEETINGS_PER_BULK_CREATE) {
      // Not a per-input failure: the CALL is too big, so no index identifies it.
      return rejectedWholeCall(inputs.size());
    }

    final Boundaries boundaries = days.boundaries();
    final List<Map<String, Object>> failures = new ArrayList<>();
    final List<MeetingRecord> accepted = new ArrayList<>();

    // Validated once, up front, against the day as it currently stands PLUS everything accepted
    // so far in this same call.
    final List<MeetingRecord> existing = new ArrayList<>(days.read(date).meetings());
    for (int index = 0; index < inputs.size(); index++) {
      final List<String> errors = errorsFor(inputs.get(index), date, boundaries, existing);
      if (errors.isEmpty()) {
        final MeetingRecord record = toRecord(inputs.get(index));
        accepted.add(record);
        existing.add(record);
      } else {
        failures.add(Map.of("index", index, "errors", errors));
      }
    }

    if (!accepted.isEmpty()) {
      write(date, accepted, failures);
      // One broadcast for the whole call, not one per meeting - which is the entire point of
      // Decision 5's day-scoped bulk creation. 99 meetings on one date change exactly one
      // day, so subscribers are woken once and refetch once.
      //
      // Conditional on something having been accepted: a call where every input failed wrote
      // nothing, so there is nothing for anyone to refetch.
      broadcaster.publish(List.of(date));
    }

    final Map<String, Object> result = new HashMap<>();
    result.put("day", dayResponse(date));
    result.put("failures", failures);
    return result;
  }

  /**
   * The write re-checks nothing per input: the batch was validated against a consistent snapshot,
   * and {@code mutate} re-runs the whole change on a version conflict, so the check that matters is
   * the one inside the retry - that the day still has room for all of them.
   */
  private void write(
      final String date,
      final List<MeetingRecord> accepted,
      final List<Map<String, Object>> failures) {
    try {
      days.mutate(
          date,
          day -> {
            if (day.meetings().size() + accepted.size() > Limits.MAX_MEETINGS_PER_DAY) {
              throw new MeetingRejected(MeetingError.DayIsFull);
            }
            return day.withMeetings(
                Stream.concat(day.meetings().stream(), accepted.stream()).toList());
          });
    } catch (final MeetingRejected rejected) {
      failuresForEveryAccepted(accepted, failures, rejected.errors());
    } catch (final DayItemTooLargeException e) {
      LOGGER.error(
          "Byte model drift: bulk write to day {} was rejected by the write-time size check",
          date,
          e);
      failuresForEveryAccepted(accepted, failures, List.of(MeetingError.DayIsFull.name()));
    }
  }

  /** Nothing was written, so every meeting that had passed validation is now a failure too. */
  private static void failuresForEveryAccepted(
      final List<MeetingRecord> accepted,
      final List<Map<String, Object>> failures,
      final List<String> errors) {
    for (int index = 0; index < accepted.size(); index++) {
      failures.add(Map.of("index", index, "errors", errors));
    }
    accepted.clear();
  }

  private List<String> errorsFor(
      final Map<String, Object> input,
      final String date,
      final Boundaries boundaries,
      final List<MeetingRecord> soFar) {
    final MeetingValidator.Validated validated = validator.validateRequest(input, boundaries);
    final List<String> errors = new ArrayList<>(validated.errors());
    if (validated.canInspectDay()) {
      if (!validated.date().equals(date)) {
        // The call names one day; a meeting for another belongs in another call.
        errors.add(MeetingError.SpansMultipleDays.name());
      }
      errors.addAll(MeetingValidator.dayStateErrors(soFar, validated));
    }
    return errors;
  }

  private static MeetingRecord toRecord(final Map<String, Object> input) {
    @SuppressWarnings("unchecked")
    final List<String> attendeeIds = (List<String>) input.getOrDefault("attendeeIds", List.of());
    return new MeetingRecord(
        UUID.randomUUID().toString(),
        (String) input.get("roomId"),
        (String) input.get("organiserId"),
        attendeeIds,
        com.mootmaker.limits.Subjects.normalise((String) input.get("subject")),
        canonical((String) input.get("startTime")),
        canonical((String) input.get("endTime")));
  }

  private static String canonical(final String dateTime) {
    return LocalDateTime.parse(dateTime).format(MeetingRecord.DATE_TIME_FORMAT);
  }

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

  private static Map<String, Object> rejectedWholeCall(final int size) {
    final Map<String, Object> result = new HashMap<>();
    result.put("day", null);
    result.put(
        "failures",
        List.of(
            Map.of("index", 0, "errors", List.of(MeetingError.TooManyMeetingsInOneCall.name()))));
    LOGGER.warn(
        "Rejected a bulk create of {} meetings; the limit is {}",
        size,
        Limits.MAX_MEETINGS_PER_BULK_CREATE);
    return result;
  }
}
