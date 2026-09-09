package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.BatchLoader;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
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
    private final String meetingParticipantsTableName;
    // Constructed once here rather than per request, so they are swept into the SnapStart snapshot
    // along with the DynamoDB client they share.
    private final RoomRepository rooms;
    private final PersonRepository people;

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
        this.meetingParticipantsTableName = meetingParticipantsTableName;
        this.rooms = new RoomRepository(dynamoDbClient, roomsTableName);
        this.people = new PersonRepository(dynamoDbClient, peopleTableName);
    }

    /** null fromStartTime/toEndTime means no range filter; same for personId. Never partially null. */
    private record Filter(String fromStartTime, String toEndTime, String personId) {
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        // Asked before anything is fetched: a query selecting only ids does no lookup at all, and
        // one selecting organiser and attendee names does a single batched lookup for both, since
        // they share a table. See SelectionSet for why the test is "is everything selected free?"
        // rather than "did they ask for a field we know needs a fetch?".
        final SelectionSet selection = SelectionSet.from(event);
        final boolean resolveRooms = selection.needsLookup("room");
        final boolean resolvePeople = selection.needsLookup("organiser") || selection.needsLookup("attendees");

        final List<MeetingRecord> records = fetchMeetingRecords(parseFilter(event));

        // Rooms and people live in separate tables, so the two lookups run concurrently; within each,
        // the repository deduplicates ids and fans out over BatchGetItem so nothing is fetched twice.
        final CompletableFuture<Map<String, Room>> roomsById = resolveRooms
                ? CompletableFuture.supplyAsync(() -> rooms.loadByIds(roomIds(records)))
                : CompletableFuture.completedFuture(Map.of());
        final CompletableFuture<Map<String, Person>> peopleById = resolvePeople
                ? CompletableFuture.supplyAsync(() -> people.loadByIds(personIds(records)))
                : CompletableFuture.completedFuture(Map.of());

        final Map<String, Room> rooms = roomsById.join();
        final Map<String, Person> people = peopleById.join();

        return records.stream()
                .map(record -> toResponseMap(record, rooms, people, resolveRooms, resolvePeople))
                .toList();
    }

    private static Set<String> roomIds(final List<MeetingRecord> records) {
        return records.stream().map(MeetingRecord::roomId).collect(Collectors.toSet());
    }

    private static Set<String> personIds(final List<MeetingRecord> records) {
        return records.stream()
                .flatMap(record -> Stream.concat(Stream.of(record.organiserId()), record.attendeeIds().stream()))
                .collect(Collectors.toSet());
    }

    /**
     * Where a lookup was skipped the nested object is emitted as its id alone. That is a complete
     * answer rather than a stub: GraphQL only serialises fields the query selected, and
     * {@link SelectionSet} only reports "no lookup needed" when everything selected under that
     * path can be answered from the id. Emitting a null name here for a name the client did ask
     * for would null the Person, then the list, then the meeting - so the two must agree, which is
     * why the selection test errs toward fetching.
     */
    private static Map<String, Object> toResponseMap(final MeetingRecord record, final Map<String, Room> roomsById,
            final Map<String, Person> peopleById, final boolean roomsResolved, final boolean peopleResolved) {
        final Map<String, Object> map = new HashMap<>();
        map.put("id", record.id());
        map.put("subject", record.subject());
        map.put("startTime", record.startTime());
        map.put("endTime", record.endTime());
        map.put("room", roomsResolved ? roomsById.get(record.roomId()).toResponseMap() : idOnly(record.roomId()));
        map.put("organiser", peopleResolved
                ? resolvePerson(record.organiserId(), peopleById).toResponseMap()
                : idOnly(record.organiserId()));
        map.put("attendees", record.attendeeIds().stream()
                .map(id -> peopleResolved ? resolvePerson(id, peopleById).toResponseMap() : idOnly(id))
                .toList());
        return map;
    }

    private static Map<String, Object> idOnly(final String id) {
        final Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        return map;
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
        final ScanResponse response = dynamoDbClient.scan(ScanRequest.builder().tableName(meetingsTableName).consistentRead(true).build());
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

}
