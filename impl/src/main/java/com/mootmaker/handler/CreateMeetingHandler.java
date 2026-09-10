package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayItemTooLargeException;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.realtime.DayBroadcaster;
import com.mootmaker.realtime.DaysInvalidatedPublisher;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomAvailability;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.limits.Subjects;
import com.mootmaker.model.Boundaries;
import com.mootmaker.model.Meeting;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.createMeeting}.
 *
 * <p>Validation happens in two places, and the split is the important part of this class. Rules that
 * depend only on the request - times, existence, capacity, lengths, the bookable window - are checked
 * once, up front. Rules that depend on the <i>state of the day</i> - is the room still free, is the
 * day now full - are checked <b>inside</b> the read-modify-write, so a booking that loses a version
 * conflict re-validates against the day as it now is rather than as it was when the request arrived.
 *
 * <p>Getting that wrong is the classic form of this bug: two concurrent bookings both read a day
 * holding 319 meetings, both conclude there is room, and both write. The conditional write makes one
 * of them retry; re-running the check on retry is what makes the retry mean anything.
 */
public class CreateMeetingHandler implements RequestHandler<Map<String, Object>, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateMeetingHandler.class);

    private final DayRepository days;
    private final MeetingValidator validator;
    private final DayBroadcaster broadcaster;

    public CreateMeetingHandler() {
        this(DynamoDbClientProvider.client(),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
                System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    CreateMeetingHandler(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String roomsTableName, final String peopleTableName) {
        this(dynamoDbClient, meetingsTableName, roomsTableName, peopleTableName,
                DaysInvalidatedPublisher.fromEnvironment());
    }

    CreateMeetingHandler(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String roomsTableName, final String peopleTableName, final DayBroadcaster broadcaster) {
        this(new DayRepository(dynamoDbClient, meetingsTableName),
                new RoomRepository(dynamoDbClient, roomsTableName),
                new PersonRepository(dynamoDbClient, peopleTableName),
                broadcaster);
    }

    CreateMeetingHandler(final DayRepository days, final RoomRepository rooms, final PersonRepository people) {
        this(days, rooms, people, DaysInvalidatedPublisher.fromEnvironment());
    }

    CreateMeetingHandler(final DayRepository days, final RoomRepository rooms, final PersonRepository people,
            final DayBroadcaster broadcaster) {
        this.days = days;
        this.validator = new MeetingValidator(rooms, people);
        this.broadcaster = broadcaster;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final Map<String, Object> meetingInput = castToMap(arguments.get("meeting"));

        try {
            final Meeting meeting = create(meetingInput, days.boundaries());
            // Only after the write has committed, and only for a write that succeeded. A rejected
            // booking must broadcast nothing: it changed no day, and waking every connected client
            // to refetch an unchanged day is pure cost. See DaysInvalidatedPublisher for why this
            // is a separate publish rather than a subscription on createMeeting itself - AppSync
            // broadcasts a rejected mutation exactly like a successful one.
            broadcaster.publish(List.of(meeting.startTime().substring(0, 10)));
            return created(meeting);
        } catch (final MeetingRejected rejected) {
            return rejected(rejected.errors());
        }
    }

    /** Shared with bulk creation, which applies the same rules to each input against one day. */
    Meeting create(final Map<String, Object> meetingInput, final Boundaries boundaries) {
        final MeetingValidator.Validated validated = validator.validateRequest(meetingInput, boundaries);

        // Day-state rules are evaluated HERE as well as inside the write, and the duplication is
        // deliberate. This pass exists so a caller learns everything wrong with their request at
        // once - a missing organiser AND an unavailable room, not the first of them. The pass inside
        // the write exists so the answer is correct under contention. Reporting and correctness are
        // different jobs; one check cannot do both, because the reporting one has to run even when
        // other rules have already failed.
        final List<String> errors = new ArrayList<>(validated.errors());
        if (validated.canInspectDay()) {
            errors.addAll(MeetingValidator.dayStateErrors(days.read(validated.date()).meetings(), validated));
        }
        if (!errors.isEmpty()) {
            throw new MeetingRejected(errors);
        }

        final MeetingRecord record = new MeetingRecord(UUID.randomUUID().toString(), validated.roomId(),
                validated.organiserId(), validated.attendeeIds(), validated.subject(),
                validated.startTime().format(MeetingRecord.DATE_TIME_FORMAT),
                validated.endTime().format(MeetingRecord.DATE_TIME_FORMAT));

        try {
            days.mutate(record.date(), day -> {
                // Re-run on every attempt, against freshly-read state. This is the whole point of
                // mutate taking a function rather than a value: a booking that loses a version
                // conflict must answer "is the room still free" about the day as it NOW is.
                final List<String> conflicts = MeetingValidator.dayStateErrors(day.meetings(), validated);
                if (!conflicts.isEmpty()) {
                    throw new MeetingRejected(conflicts);
                }
                return day.withMeetings(Stream.concat(day.meetings().stream(), Stream.of(record)).toList());
            });
        } catch (final DayItemTooLargeException e) {
            // Layer 3 fired, so the byte model in Limits has drifted from reality. Loud in the log,
            // because that needs fixing - but an ordinary "the day is full" to the user, which is
            // true, renderable, and does not leak that an internal estimate was wrong.
            LOGGER.error("Byte model drift: day {} was rejected by the write-time size check", record.date(), e);
            throw new MeetingRejected(MeetingError.DayIsFull);
        }

        return new Meeting(record.id(), validated.room(), validated.organiser(), validated.attendees(),
                record.subject(), record.startTime(), record.endTime());
    }

    private static Map<String, Object> created(final Meeting meeting) {
        final Map<String, Object> result = new HashMap<>();
        result.put("meeting", meeting.toResponseMap());
        result.put("errors", List.of());
        return result;
    }

    private static Map<String, Object> rejected(final List<String> errors) {
        final Map<String, Object> result = new HashMap<>();
        result.put("meeting", null);
        result.put("errors", errors);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
