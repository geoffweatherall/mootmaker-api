package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.BatchLoader;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.model.Meeting;
import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import module java.base;

/** AppSync direct-Lambda resolver for {@code Query.meetings}. */
public class ListMeetingsHandler implements RequestHandler<Map<String, Object>, Object> {

    private static final String BUCKET_START_TIME_INDEX = "bucket-startTime-index";
    private static final String BUCKET = "ALL";

    private final DynamoDbClient dynamoDbClient;
    private final String meetingsTableName;
    private final String roomsTableName;
    private final String peopleTableName;
    private final String meetingParticipantsTableName;

    public ListMeetingsHandler() {
        this(DynamoDbClientProvider.client(),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
                System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
                System.getenv().getOrDefault("MEETING_PARTICIPANTS_TABLE_NAME", "MeetingParticipants"));
    }

    ListMeetingsHandler(final DynamoDbClient dynamoDbClient, final String meetingsTableName, final String roomsTableName,
            final String peopleTableName, final String meetingParticipantsTableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.meetingsTableName = meetingsTableName;
        this.roomsTableName = roomsTableName;
        this.peopleTableName = peopleTableName;
        this.meetingParticipantsTableName = meetingParticipantsTableName;
    }

    /** null fromStartTime/toEndTime means no range filter; same for personId. Never partially null. */
    private record Filter(String fromStartTime, String toEndTime, String personId) {
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final List<MeetingRecord> records = fetchMeetingRecords(parseFilter(event));

        final Set<String> roomIds = records.stream().map(MeetingRecord::roomId).collect(Collectors.toSet());
        final Set<String> personIds = records.stream()
                .flatMap(record -> Stream.concat(Stream.of(record.organiserId()), record.attendeeIds().stream()))
                .collect(Collectors.toSet());

        // Rooms and people live in separate tables, so the two lookups run concurrently; within each,
        // BatchLoader deduplicates ids and fans out over BatchGetItem so no room or person is fetched twice.
        final CompletableFuture<Map<String, Room>> roomsById = CompletableFuture
                .supplyAsync(() -> BatchLoader.loadById(dynamoDbClient, roomsTableName, roomIds))
                .thenApply(ListMeetingsHandler::toRoomsById);
        final CompletableFuture<Map<String, Person>> peopleById = CompletableFuture
                .supplyAsync(() -> BatchLoader.loadById(dynamoDbClient, peopleTableName, personIds))
                .thenApply(ListMeetingsHandler::toPeopleById);

        final Map<String, Room> rooms = roomsById.join();
        final Map<String, Person> people = peopleById.join();

        return records.stream()
                .map(record -> resolve(record, rooms, people))
                .map(Meeting::toResponseMap)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Filter parseFilter(final Map<String, Object> event) {
        final Object argumentsObj = event.get("arguments");
        if (!(argumentsObj instanceof Map)) {
            return new Filter(null, null, null);
        }
        final Object filterObj = ((Map<String, Object>) argumentsObj).get("filter");
        if (!(filterObj instanceof Map)) {
            return new Filter(null, null, null);
        }
        final Map<String, Object> filter = (Map<String, Object>) filterObj;
        final String fromStartTime = (String) filter.get("fromStartTime");
        final String toEndTime = (String) filter.get("toEndTime");
        final String personId = (String) filter.get("personId");
        if ((fromStartTime == null) != (toEndTime == null)) {
            throw new IllegalArgumentException("fromStartTime and toEndTime must be supplied together.");
        }
        return new Filter(fromStartTime, toEndTime, personId);
    }

    private List<MeetingRecord> fetchMeetingRecords(final Filter filter) {
        final boolean hasRange = filter.fromStartTime() != null;
        final boolean hasPerson = filter.personId() != null;

        if (!hasRange && !hasPerson) {
            return scanAllMeetings();
        }
        if (hasRange && !hasPerson) {
            return queryByDateRange(filter.fromStartTime(), filter.toEndTime());
        }
        final List<String> meetingIds = hasRange
                ? queryParticipantMeetingIds(filter.personId(), filter.fromStartTime(), filter.toEndTime())
                : queryParticipantMeetingIds(filter.personId());
        return hydrateMeetings(meetingIds);
    }

    /** No filter at all genuinely means "every meeting", so a scan is the right tool, not a workaround. */
    private List<MeetingRecord> scanAllMeetings() {
        final ScanResponse response = dynamoDbClient.scan(ScanRequest.builder().tableName(meetingsTableName).build());
        return response.items().stream().map(MeetingRecord::fromItem).toList();
    }

    /**
     * Queries the bucket-startTime-index GSI for every meeting starting in [fromStartTime,
     * toEndTime). The low end is bounded at the start of fromStartTime's calendar day rather than
     * fromStartTime itself - a safe bound (not just a heuristic buffer) because every meeting is
     * confined to a single calendar day (see MeetingError.SpansMultipleDays), so nothing starting
     * before that day could still be running when it begins. DynamoDB only allows a single
     * condition on a sort key, so both ends of that bound go through one BETWEEN in the key
     * condition; BETWEEN is inclusive on both ends, so an item starting exactly at toEndTime is
     * excluded afterwards in Java rather than via a second key-attribute condition. endTime isn't
     * part of this index's key schema, so the precise low-end overlap check (an item that started
     * before fromStartTime but is still running) can go in a FilterExpression - unlike startTime,
     * which DynamoDB rejects in a FilterExpression here since it's this index's sort key.
     */
    private List<MeetingRecord> queryByDateRange(final String fromStartTime, final String toEndTime) {
        final String dayStart = startOfDay(fromStartTime);
        final QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(meetingsTableName)
                .indexName(BUCKET_START_TIME_INDEX)
                // "bucket" is a DynamoDB reserved word, so it can't appear literally in an
                // expression - it must go through an ExpressionAttributeNames alias.
                .keyConditionExpression("#bucket = :bucket AND startTime BETWEEN :dayStart AND :toEndTime")
                .filterExpression("endTime > :fromStartTime")
                .expressionAttributeNames(Map.of("#bucket", "bucket"))
                .expressionAttributeValues(Map.of(
                        ":bucket", AttributeValue.builder().s(BUCKET).build(),
                        ":dayStart", AttributeValue.builder().s(dayStart).build(),
                        ":toEndTime", AttributeValue.builder().s(toEndTime).build(),
                        ":fromStartTime", AttributeValue.builder().s(fromStartTime).build()))
                .build());
        return response.items().stream()
                .map(MeetingRecord::fromItem)
                .filter(record -> record.startTime().compareTo(toEndTime) < 0)
                .toList();
    }

    /** No date range: every meeting this person organises or attends, in chronological (sortKey) order. */
    private List<String> queryParticipantMeetingIds(final String personId) {
        final QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(meetingParticipantsTableName)
                .keyConditionExpression("personId = :personId")
                .expressionAttributeValues(Map.of(":personId", AttributeValue.builder().s(personId).build()))
                .build());
        return distinctMeetingIds(response);
    }

    /**
     * sortKey is "startTime#meetingId"; a bare startTime string (no "#" suffix) sorts immediately
     * before any real entry starting at that instant, so BETWEEN a bare dayStart and a bare
     * toEndTime gives an inclusive-low/exclusive-high range on the startTime component with no
     * FilterExpression needed for the upper bound. endTime > fromStartTime is still applied as a
     * FilterExpression for exact overlap precision at the low end, for the same reason as
     * queryByDateRange above.
     */
    private List<String> queryParticipantMeetingIds(final String personId, final String fromStartTime, final String toEndTime) {
        final String dayStart = startOfDay(fromStartTime);
        final QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(meetingParticipantsTableName)
                .keyConditionExpression("personId = :personId AND sortKey BETWEEN :dayStart AND :toEndTime")
                .filterExpression("endTime > :fromStartTime")
                .expressionAttributeValues(Map.of(
                        ":personId", AttributeValue.builder().s(personId).build(),
                        ":dayStart", AttributeValue.builder().s(dayStart).build(),
                        ":toEndTime", AttributeValue.builder().s(toEndTime).build(),
                        ":fromStartTime", AttributeValue.builder().s(fromStartTime).build()))
                .build());
        return distinctMeetingIds(response);
    }

    private static String startOfDay(final String dateTime) {
        return LocalDateTime.parse(dateTime).toLocalDate().atStartOfDay().format(MeetingRecord.DATE_TIME_FORMAT);
    }

    private static List<String> distinctMeetingIds(final QueryResponse response) {
        return response.items().stream()
                .map(MeetingParticipant::fromItem)
                .map(MeetingParticipant::meetingId)
                .distinct()
                .toList();
    }

    private List<MeetingRecord> hydrateMeetings(final List<String> meetingIds) {
        final Map<String, Map<String, AttributeValue>> itemsById =
                BatchLoader.loadById(dynamoDbClient, meetingsTableName, Set.copyOf(meetingIds));
        return itemsById.values().stream().map(MeetingRecord::fromItem).toList();
    }

    private static Meeting resolve(final MeetingRecord record, final Map<String, Room> roomsById, final Map<String, Person> peopleById) {
        final Room room = roomsById.get(record.roomId());
        final Person organiser = resolvePerson(record.organiserId(), peopleById);
        final List<Person> attendees = record.attendeeIds().stream().map(id -> resolvePerson(id, peopleById)).toList();
        return new Meeting(record.id(), room, organiser, attendees, record.subject(), record.startTime(), record.endTime());
    }

    /**
     * A meeting can outlive one of its participants: DeleteMyAccountHandler deliberately leaves
     * past meetings untouched when an organiser/attendee deletes their account, rather than
     * deleting meetings that already happened - so their Person row can be gone while a historical
     * meeting still references its id. Substituting a placeholder here avoids the null that would
     * otherwise reach Meeting.toResponseMap() and NullPointerException.
     */
    private static Person resolvePerson(final String personId, final Map<String, Person> peopleById) {
        return peopleById.getOrDefault(personId, new Person(personId, "Deleted user"));
    }

    private static Map<String, Room> toRoomsById(final Map<String, Map<String, AttributeValue>> itemsById) {
        return itemsById.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Room.fromItem(entry.getValue())));
    }

    private static Map<String, Person> toPeopleById(final Map<String, Map<String, AttributeValue>> itemsById) {
        return itemsById.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Person.fromItem(entry.getValue())));
    }
}
