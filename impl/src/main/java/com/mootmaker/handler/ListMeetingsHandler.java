package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Query.meetings}.
 *
 * <p>Reads day items. A date range maps straight onto day keys, so neither meetings GSI has anything
 * left to answer, and a {@code personId} is applied in memory rather than through the participants
 * join table. Combined with the selection-aware fetching below, a query for a week of meeting ids
 * costs seven point reads and nothing else.
 */
public class ListMeetingsHandler implements RequestHandler<Map<String, Object>, Object> {


    private final DayRepository days;
    // Constructed once here rather than per request, so they are swept into the SnapStart snapshot
    // along with the DynamoDB client they share.
    private final RoomRepository rooms;
    private final PersonRepository people;

    public ListMeetingsHandler() {
        this(DynamoDbClientProvider.client(),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
                System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
                System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    ListMeetingsHandler(final DynamoDbClient dynamoDbClient, final String meetingsTableName, final String roomsTableName,
            final String peopleTableName) {
        this.days = new DayRepository(dynamoDbClient, meetingsTableName);
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

    /**
     * Every meeting matching the filter, read from day items.
     *
     * <p>A date range maps straight onto day keys, so the old {@code bucket-startTime-index} GSI has
     * nothing left to answer - which is why it could be deleted rather than ported. A {@code personId}
     * is applied in memory: the participants join table existed only to answer "this person's meetings"
     * without a range, and the one caller that needed that (account deletion) now scans instead.
     */
    private List<MeetingRecord> fetchMeetingRecords(final Filter filter) {
        final List<MeetingRecord> candidates = filter.fromStartTime() == null
                ? days.scanDays().stream().flatMap(day -> day.meetings().stream()).toList()
                : days.read(datesCovering(filter.fromStartTime(), filter.toEndTime())).stream()
                        .flatMap(day -> day.meetings().stream())
                        .filter(record -> overlapsRange(record, filter.fromStartTime(), filter.toEndTime()))
                        .toList();

        return filter.personId() == null
                ? candidates
                : candidates.stream().filter(record -> involves(record, filter.personId())).toList();
    }

    /**
     * Every date the range can touch, inclusive of both ends. A meeting cannot span midnight, so a
     * meeting overlapping the range must start on one of these dates - which makes the day keys an
     * exact cover rather than a heuristic.
     */
    private static List<String> datesCovering(final String fromStartTime, final String toEndTime) {
        final LocalDate from = LocalDateTime.parse(fromStartTime).toLocalDate();
        final LocalDate to = LocalDateTime.parse(toEndTime).toLocalDate();
        return from.datesUntil(to.plusDays(1)).map(LocalDate::toString).toList();
    }

    /** Half-open, matching the overlap rule createMeeting enforces: startTime < toEndTime and endTime > fromStartTime. */
    private static boolean overlapsRange(final MeetingRecord record, final String fromStartTime, final String toEndTime) {
        return record.startTime().compareTo(toEndTime) < 0 && record.endTime().compareTo(fromStartTime) > 0;
    }

    private static boolean involves(final MeetingRecord record, final String personId) {
        return record.organiserId().equals(personId) || record.attendeeIds().contains(personId);
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
