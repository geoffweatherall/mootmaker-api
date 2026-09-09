package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayItemTooLargeException;
import com.mootmaker.dynamo.DayRepository;
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
    private final RoomRepository rooms;
    private final PersonRepository people;

    public CreateMeetingHandler() {
        this(DynamoDbClientProvider.client(),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
                System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    CreateMeetingHandler(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String roomsTableName, final String peopleTableName) {
        this(new DayRepository(dynamoDbClient, meetingsTableName),
                new RoomRepository(dynamoDbClient, roomsTableName),
                new PersonRepository(dynamoDbClient, peopleTableName));
    }

    CreateMeetingHandler(final DayRepository days, final RoomRepository rooms, final PersonRepository people) {
        this.days = days;
        this.rooms = rooms;
        this.people = people;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final Map<String, Object> meetingInput = castToMap(arguments.get("meeting"));

        try {
            return created(create(meetingInput, days.boundaries()));
        } catch (final MeetingRejected rejected) {
            return rejected(rejected.errors());
        }
    }

    /** Shared with bulk creation, which applies the same rules to each input against one day. */
    Meeting create(final Map<String, Object> meetingInput, final Boundaries boundaries) {
        final Validated validated = validateRequest(meetingInput, boundaries);

        // Day-state rules are evaluated HERE as well as inside the write, and the duplication is
        // deliberate. This pass exists so a caller learns everything wrong with their request at
        // once - a missing organiser AND an unavailable room, not the first of them. The pass inside
        // the write exists so the answer is correct under contention. Reporting and correctness are
        // different jobs; one check cannot do both, because the reporting one has to run even when
        // other rules have already failed.
        final List<String> errors = new ArrayList<>(validated.errors());
        if (validated.canInspectDay()) {
            errors.addAll(dayStateErrors(days.read(validated.date()).meetings(), validated));
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
                final List<String> conflicts = dayStateErrors(day.meetings(), validated);
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

    /**
     * The request after checking, valid or not. Carries the errors rather than throwing them so the
     * day-state pass can add its own before anything is reported.
     */
    private record Validated(List<String> errors, String roomId, String organiserId, List<String> attendeeIds,
            String subject, LocalDateTime startTime, LocalDateTime endTime, Room room, Person organiser,
            List<Person> attendees) {

        /** Enough of the request survived to ask the day a question. */
        boolean canInspectDay() {
            return startTime != null && endTime != null && startTime.toLocalDate().equals(endTime.toLocalDate());
        }

        String date() {
            return startTime.toLocalDate().toString();
        }
    }

    /** The rules that depend on what the day already holds. */
    private static List<String> dayStateErrors(final List<MeetingRecord> meetingsThatDay, final Validated validated) {
        final List<String> errors = new ArrayList<>();
        if (meetingsThatDay.size() >= Limits.MAX_MEETINGS_PER_DAY) {
            errors.add(MeetingError.DayIsFull.name());
        }
        if (validated.roomId() != null && !validated.roomId().isBlank()
                && !RoomAvailability.isFree(meetingsThatDay, validated.roomId(), validated.startTime(), validated.endTime())) {
            errors.add(MeetingError.TimeRangeUnavailable.name());
        }
        return errors;
    }

    /** Everything checkable without reading the day. Collects every broken rule rather than stopping at the first. */
    private Validated validateRequest(final Map<String, Object> meetingInput, final Boundaries boundaries) {
        final String roomId = (String) meetingInput.get("roomId");
        final String organiserId = (String) meetingInput.get("organiserId");
        @SuppressWarnings("unchecked")
        final List<String> attendeeIds = (List<String>) meetingInput.get("attendeeIds");
        final String subject = Subjects.normalise((String) meetingInput.get("subject"));

        final List<String> errors = new ArrayList<>();

        if (isBlank(subject)) {
            errors.add(MeetingError.SubjectRequired.name());
        } else if (!Subjects.isWithinLimit(subject)) {
            errors.add(MeetingError.SubjectTooLong.name());
        }

        final LocalDateTime startTime =
                parseOnFifteenMinuteBoundary((String) meetingInput.get("startTime"), MeetingError.StartMissaligned, errors);
        final LocalDateTime endTime =
                parseOnFifteenMinuteBoundary((String) meetingInput.get("endTime"), MeetingError.EndMissaligned, errors);

        if (startTime != null && endTime != null) {
            if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
                errors.add(MeetingError.SpansMultipleDays.name());
            } else if (!endTime.isAfter(startTime)) {
                errors.add(MeetingError.EndBeforeStart.name());
            } else if (!boundaries.contains(startTime.toLocalDate().toString())) {
                errors.add(MeetingError.OutsideBookableRange.name());
            }
        }

        if (attendeeIds != null && attendeeIds.size() > Limits.MAX_ATTENDEES_PER_MEETING) {
            errors.add(MeetingError.TooManyAttendees.name());
        }

        Room room = null;
        if (isBlank(roomId)) {
            errors.add(MeetingError.RoomRequired.name());
        } else {
            room = rooms.findById(roomId).orElse(null);
            if (room == null) {
                errors.add(MeetingError.RoomNotFound.name());
            }
        }

        Person organiser = null;
        if (isBlank(organiserId)) {
            errors.add(MeetingError.OrganiserRequired.name());
        } else {
            organiser = people.findById(organiserId).orElse(null);
            if (organiser == null) {
                errors.add(MeetingError.OrganiserNotFound.name());
            }
        }

        final List<String> safeAttendeeIds = attendeeIds == null ? List.of() : attendeeIds;
        final Map<String, Person> attendeesById = people.loadByIds(Set.copyOf(safeAttendeeIds));
        final List<Person> attendees = new ArrayList<>();
        for (final String attendeeId : safeAttendeeIds) {
            final Person attendee = attendeesById.get(attendeeId);
            if (attendee == null) {
                errors.add(MeetingError.AttendeeNotFound.name());
            } else {
                attendees.add(attendee);
            }
        }

        if (!isBlank(organiserId) && safeAttendeeIds.contains(organiserId)) {
            errors.add(MeetingError.OrganiserIsAttendee.name());
        }

        if (room != null) {
            // A Set, not "1 + attendeeIds.size()", so a duplicated organiserId (already separately rejected
            // above via OrganiserIsAttendee) can't also inflate this count and raise a spurious, misleading
            // InsufficientCapacity alongside it.
            final Set<String> distinctParticipantIds = new HashSet<>(safeAttendeeIds);
            if (!isBlank(organiserId)) {
                distinctParticipantIds.add(organiserId);
            }
            if (room.capacity() < distinctParticipantIds.size()) {
                errors.add(MeetingError.InsufficientCapacity.name());
            }
        }

        return new Validated(errors, roomId, organiserId, safeAttendeeIds, subject, startTime, endTime,
                room, organiser, attendees);
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

    private static LocalDateTime parseOnFifteenMinuteBoundary(final String text, final MeetingError error, final List<String> errors) {
        if (text == null) {
            errors.add(error.name());
            return null;
        }
        final LocalDateTime dateTime;
        try {
            dateTime = LocalDateTime.parse(text);
        } catch (final DateTimeParseException _) {
            errors.add(error.name());
            return null;
        }
        if (dateTime.getSecond() != 0 || dateTime.getNano() != 0 || dateTime.getMinute() % 15 != 0) {
            errors.add(error.name());
            return null;
        }
        return dateTime;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
