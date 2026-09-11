package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Query.workspace} - the single entry point every screen
 * loads through.
 *
 * <p>One invocation instead of the three or four this replaced, which matters most on the request
 * that decides how fast the app feels. The four sub-fields are independent, so they are fetched
 * concurrently and the caller waits for the slowest rather than the sum.
 *
 * <p><b>Nothing not selected is fetched.</b> A calendar paging to a new week selects only {@code
 * days}, so this does no people, rooms or boundaries work at all; a reference-data refresh omits
 * {@code dates} entirely and reads no day items. That is the whole reason a composite field costs
 * no more than the narrow ones it replaced - see {@link SelectionSet} for why the test errs toward
 * fetching rather than enumerating what is expensive.
 */
public class WorkspaceHandler implements RequestHandler<Map<String, Object>, Object> {

  private final DayRepository days;
  private final RoomRepository rooms;
  private final PersonRepository people;

  public WorkspaceHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"),
        System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  WorkspaceHandler(
      final DynamoDbClient dynamoDbClient,
      final String meetingsTableName,
      final String roomsTableName,
      final String peopleTableName) {
    this(
        new DayRepository(dynamoDbClient, meetingsTableName),
        new RoomRepository(dynamoDbClient, roomsTableName),
        new PersonRepository(dynamoDbClient, peopleTableName));
  }

  WorkspaceHandler(
      final DayRepository days, final RoomRepository rooms, final PersonRepository people) {
    this.days = days;
    this.rooms = rooms;
    this.people = people;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);

    final SelectionSet selection = SelectionSet.from(event);
    final List<String> dates = requestedDates(event);

    final CompletableFuture<List<Map<String, Object>>> peopleFuture =
        selection.selects("people") ? async(this::peopleResponse) : completed(null);
    final CompletableFuture<List<Map<String, Object>>> roomsFuture =
        selection.selects("rooms") ? async(this::roomsResponse) : completed(null);
    final CompletableFuture<Map<String, Object>> meFuture =
        selection.selects("me") ? async(() -> meResponse(event)) : completed(null);
    final CompletableFuture<Map<String, Object>> boundariesFuture =
        selection.selects("boundaries")
            ? async(() -> days.boundaries().toResponseMap())
            : completed(null);
    final CompletableFuture<List<Map<String, Object>>> daysFuture =
        selection.selects("days") ? async(() -> daysResponse(dates, selection)) : completed(null);

    final Map<String, Object> workspace = new HashMap<>();
    workspace.put("me", join(meFuture));
    workspace.put("people", join(peopleFuture));
    workspace.put("rooms", join(roomsFuture));
    workspace.put("days", join(daysFuture));
    workspace.put("boundaries", join(boundariesFuture));
    return workspace;
  }

  /**
   * Absent means no days are wanted, which is how a reference-data-only refresh asks for just rooms
   * and people. An oversized list is refused rather than truncated - a client silently receiving
   * fewer days than it asked for would render gaps as empty days, which is worse than an error.
   */
  @SuppressWarnings("unchecked")
  private static List<String> requestedDates(final Map<String, Object> event) {
    final Object arguments = event.get("arguments");
    if (!(arguments instanceof Map<?, ?> args)) {
      return List.of();
    }
    final Object dates = ((Map<String, Object>) args).get("dates");
    if (!(dates instanceof List<?> list)) {
      return List.of();
    }
    if (list.size() > Limits.MAX_DATES_PER_REQUEST) {
      throw new IllegalArgumentException(
          "Too many dates requested: "
              + list.size()
              + " exceeds the limit of "
              + Limits.MAX_DATES_PER_REQUEST
              + ".");
    }
    return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
  }

  /**
   * Days in the order asked for, with the response bound enforced <b>as they accumulate</b>.
   *
   * <p>The static limits alone permit a request that cannot be answered - 42 dates times 320
   * meetings is far past what AppSync will carry - so the count is checked while building rather
   * than after. Failing before the response exists is the difference between a typed error a client
   * can render and a truncated payload it cannot detect.
   */
  private List<Map<String, Object>> daysResponse(
      final List<String> dates, final SelectionSet selection) {
    final boolean resolveRooms = selection.needsLookup("days/meetings/room");
    final boolean resolvePeople =
        selection.needsLookup("days/meetings/organiser")
            || selection.needsLookup("days/meetings/attendees");

    final List<Day> fetched = new ArrayList<>();
    int meetingCount = 0;
    for (final Day day : days.read(dates)) {
      meetingCount += day.meetings().size();
      if (meetingCount > Limits.MAX_MEETINGS_PER_RESPONSE) {
        throw new IllegalArgumentException(
            "The response would be too large: more than "
                + Limits.MAX_MEETINGS_PER_RESPONSE
                + " meetings across the requested dates. "
                + "Ask for fewer dates.");
      }
      fetched.add(day);
    }

    final List<MeetingRecord> all =
        fetched.stream().flatMap(day -> day.meetings().stream()).toList();
    final Map<String, Room> roomsById =
        resolveRooms
            ? rooms.loadByIds(all.stream().map(MeetingRecord::roomId).collect(Collectors.toSet()))
            : Map.of();
    final Map<String, Person> peopleById =
        resolvePeople
            ? people.loadByIds(
                all.stream()
                    .flatMap(
                        m -> Stream.concat(Stream.of(m.organiserId()), m.attendeeIds().stream()))
                    .collect(Collectors.toSet()))
            : Map.of();

    return fetched.stream()
        .map(
            day -> {
              final Map<String, Object> response = new HashMap<>();
              response.put("date", day.date());
              response.put(
                  "meetings",
                  day.meetings().stream()
                      .map(
                          record ->
                              MeetingResponse.of(
                                  record, roomsById, peopleById, resolveRooms, resolvePeople))
                      .toList());
              return response;
            })
        .toList();
  }

  private Map<String, Object> meResponse(final Map<String, Object> event) {
    return Identity.personId(event)
        .flatMap(people::findById)
        .map(Person::toResponseMap)
        .orElse(null);
  }

  private List<Map<String, Object>> peopleResponse() {
    return people.listAll().stream().map(Person::toResponseMap).toList();
  }

  private List<Map<String, Object>> roomsResponse() {
    return rooms.listAll().stream().map(Room::toResponseMap).toList();
  }

  /**
   * Unwraps {@code CompletionException} so a validation failure raised inside one of these branches
   * still reaches AppSync as itself.
   *
   * <p>Not cosmetic: the response bound is enforced while days accumulate, which happens on a
   * worker thread, and a plain {@code join()} would wrap it. The client would then get a generic
   * failure instead of the message telling them to ask for fewer dates - turning a rule they can
   * act on into an apparent server error.
   */
  private static <T> T join(final CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (final CompletionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw e;
    }
  }

  private static <T> CompletableFuture<T> async(final Supplier<T> work) {
    return CompletableFuture.supplyAsync(work);
  }

  private static <T> CompletableFuture<T> completed(final T value) {
    return CompletableFuture.completedFuture(value);
  }
}
