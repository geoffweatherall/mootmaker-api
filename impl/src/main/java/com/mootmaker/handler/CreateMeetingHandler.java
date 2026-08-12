package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.RoomAvailability;
import com.mootmaker.model.Meeting;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.createMeeting}.
 * Validates the request and either persists the meeting or returns the list of broken rules.
 */
public class CreateMeetingHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String roomsTableName;
    private final String peopleTableName;
    private final String meetingsTableName;
    private final String meetingParticipantsTableName;

    public CreateMeetingHandler() {
        this(DynamoDbClientProvider.client(),
                System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
                System.getenv().getOrDefault("MEETING_PARTICIPANTS_TABLE_NAME", "MeetingParticipants"));
    }

    CreateMeetingHandler(final DynamoDbClient dynamoDbClient, final String roomsTableName, final String peopleTableName,
            final String meetingsTableName, final String meetingParticipantsTableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.roomsTableName = roomsTableName;
        this.peopleTableName = peopleTableName;
        this.meetingsTableName = meetingsTableName;
        this.meetingParticipantsTableName = meetingParticipantsTableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final Map<String, Object> meetingInput = castToMap(arguments.get("meeting"));

        final String roomId = (String) meetingInput.get("roomId");
        final String organiserId = (String) meetingInput.get("organiserId");
        @SuppressWarnings("unchecked")
        final List<String> attendeeIds = (List<String>) meetingInput.get("attendeeIds");
        final String subject = (String) meetingInput.get("subject");
        final String startTimeText = (String) meetingInput.get("startTime");
        final String endTimeText = (String) meetingInput.get("endTime");

        final List<String> errors = new ArrayList<>();

        if (isBlank(subject)) {
            errors.add(MeetingError.SubjectRequired.name());
        }

        final LocalDateTime startTime = parseOnFiveMinuteBoundary(startTimeText, MeetingError.StartMissaligned, errors);
        final LocalDateTime endTime = parseOnFiveMinuteBoundary(endTimeText, MeetingError.EndMissaligned, errors);

        if (startTime != null && endTime != null && !startTime.toLocalDate().equals(endTime.toLocalDate())) {
            errors.add(MeetingError.SpansMultipleDays.name());
        }

        Room room = null;
        if (isBlank(roomId)) {
            errors.add(MeetingError.RoomRequired.name());
        } else {
            room = getRoom(roomId);
            if (room == null) {
                errors.add(MeetingError.RoomNotFound.name());
            }
        }

        Person organiser = null;
        if (isBlank(organiserId)) {
            errors.add(MeetingError.OrganiserRequired.name());
        } else {
            organiser = getPerson(organiserId);
            if (organiser == null) {
                errors.add(MeetingError.OrganiserNotFound.name());
            }
        }

        final List<Person> attendees = new ArrayList<>();
        for (final String attendeeId : attendeeIds) {
            final Person attendee = getPerson(attendeeId);
            if (attendee == null) {
                errors.add(MeetingError.AttendeeNotFound.name());
            } else {
                attendees.add(attendee);
            }
        }

        if (!isBlank(organiserId) && attendeeIds.contains(organiserId)) {
            errors.add(MeetingError.OrganiserIsAttendee.name());
        }

        if (room != null) {
            // A Set, not "1 + attendeeIds.size()", so a duplicated organiserId (already separately rejected
            // above via OrganiserIsAttendee) can't also inflate this count and raise a spurious, misleading
            // InsufficientCapacity alongside it.
            final Set<String> distinctParticipantIds = new HashSet<>(attendeeIds);
            if (!isBlank(organiserId)) {
                distinctParticipantIds.add(organiserId);
            }
            if (room.capacity() < distinctParticipantIds.size()) {
                errors.add(MeetingError.InsufficientCapacity.name());
            }
        }

        if (room != null && startTime != null && endTime != null
                && RoomAvailability.hasOverlappingMeeting(dynamoDbClient, meetingsTableName, roomId, startTime, endTime)) {
            errors.add(MeetingError.TimeRangeUnavailable.name());
        }

        final Map<String, Object> result = new HashMap<>();
        if (!errors.isEmpty()) {
            result.put("meeting", null);
            result.put("errors", errors);
            return result;
        }

        final String id = UUID.randomUUID().toString();
        final String canonicalStartTime = startTime.format(MeetingRecord.DATE_TIME_FORMAT);
        final String canonicalEndTime = endTime.format(MeetingRecord.DATE_TIME_FORMAT);
        final MeetingRecord record =
                new MeetingRecord(id, roomId, organiserId, attendeeIds, subject, canonicalStartTime, canonicalEndTime);
        writeMeetingAndParticipants(record);

        final Meeting meeting = new Meeting(id, room, organiser, attendees, subject, canonicalStartTime, canonicalEndTime);
        result.put("meeting", meeting.toResponseMap());
        result.put("errors", errors);
        return result;
    }

    /**
     * Writes the meeting and one meeting-participants row per organiser/attendee (see
     * MeetingParticipant) in a single transaction, so the participants table - a derived index,
     * not a source of truth - can never end up missing rows for a meeting that was actually
     * created.
     */
    private void writeMeetingAndParticipants(final MeetingRecord record) {
        final List<TransactWriteItem> transactItems = new ArrayList<>();
        transactItems.add(TransactWriteItem.builder()
                .put(Put.builder().tableName(meetingsTableName).item(record.toItem()).build())
                .build());
        for (final MeetingParticipant participant : MeetingParticipant.allFor(record)) {
            transactItems.add(TransactWriteItem.builder()
                    .put(Put.builder().tableName(meetingParticipantsTableName).item(participant.toItem()).build())
                    .build());
        }
        dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(transactItems).build());
    }

    private static LocalDateTime parseOnFiveMinuteBoundary(final String text, final MeetingError error, final List<String> errors) {
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
        if (dateTime.getSecond() != 0 || dateTime.getNano() != 0 || dateTime.getMinute() % 5 != 0) {
            errors.add(error.name());
            return null;
        }
        return dateTime;
    }

    private Room getRoom(final String roomId) {
        if (isBlank(roomId)) {
            return null;
        }
        final GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(roomsTableName)
                .key(Map.of("id", AttributeValue.builder().s(roomId).build()))
                .build());
        return response.hasItem() ? Room.fromItem(response.item()) : null;
    }

    private Person getPerson(final String personId) {
        if (isBlank(personId)) {
            return null;
        }
        final GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(peopleTableName)
                .key(Map.of("id", AttributeValue.builder().s(personId).build()))
                .build());
        return response.hasItem() ? Person.fromItem(response.item()) : null;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
