package com.mootmaker.verify;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import module java.base;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Acceptance coverage for the weekly history cleanup, against a real deployed environment.
 *
 * <p><b>History is seeded through the API</b>, not written into DynamoDB directly. That means the
 * seeded days go through the same write path as any other booking - the version-conditional write,
 * the byte measurement, the pointer transaction - so there is exactly one implementation of the
 * day-item format and this test proves it rather than duplicating it. It also keeps {@code verify/}
 * a black-box suite with no DynamoDB dependency at all.
 *
 * <p>The cost of that choice, recorded so it is not rediscovered as a surprise: past bookings must
 * stay permanently legal. Adding a rule rejecting them would break this test three files from the
 * symptom.
 */
class HistoryRetentionAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryRetentionAcceptanceIT.class);

    /**
     * The server's retention window, in days. Duplicated rather than imported: {@code verify/} has no
     * dependency on the implementation on purpose, so this is a black-box restatement of the promise
     * (Limits.RETENTION_DAYS_MINIMUM) rather than a shared constant that could drift with it silently.
     * If the two disagree, the assertions below fail - which is the point.
     */
    private static final int RETENTION_DAYS = 30;

    /**
     * Two weeks - the unit every offset below is built from, because a whole number of weeks added to
     * the Monday boundary is itself a Monday. That makes the job's own rounding back to a Monday a
     * no-op, so this test's arithmetic is exact rather than approximate, and no assertion depends on
     * which weekday it happens to run.
     */
    private static final int TWO_WEEKS = 14;

    private static final String CREATE_ROOM_MUTATION =
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id } errors } }";
    private static final String CREATE_PERSON_MUTATION =
            "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { person { id } errors } }";
    private static final String CREATE_MEETING_MUTATION =
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) { meeting { id } errors } }";
    private static final String WORKSPACE_QUERY =
            "query Workspace($dates: [String!]) { workspace(dates: $dates) {"
                    + " boundaries { earliestRetainedDate latestBookableDate }"
                    + " days { date meetings { id } } } }";

    private static GraphQlClient client;
    private static String roomId;
    private static String organiserId;

    @BeforeAll
    static void setUp() {
        client = GraphQlClient.fromEnvironment();
        final Faker faker = new Faker();
        LOG.info("Resetting the database before the test");
        DatabaseReset.reset();
        roomId = client.execute(CREATE_ROOM_MUTATION,
                Map.of("room", Map.of("name", faker.address().city() + " Room", "capacity", 8)))
                .get("createRoom").get("room").get("id").asText();
        organiserId = client.execute(CREATE_PERSON_MUTATION,
                Map.of("person", Map.of("name", faker.name().fullName())))
                .get("createPerson").get("person").get("id").asText();
    }

    private static String boundary() {
        return client.execute(WORKSPACE_QUERY, Map.of("dates", List.of()))
                .get("workspace").get("boundaries").get("earliestRetainedDate").asText();
    }

    /** Books a meeting on a given date, which the API allows because past bookings stay legal. */
    private static void seedMeetingOn(final LocalDate date) {
        final JsonNode result = client.execute(CREATE_MEETING_MUTATION, Map.of("meeting", Map.of(
                "roomId", roomId,
                "organiserId", organiserId,
                "attendeeIds", List.of(),
                "subject", "Seeded history " + date,
                "startTime", date + "T09:00:00",
                "endTime", date + "T09:30:00")));
        assertThat("seeding " + date + " must be accepted - past bookings stay legal for this reason",
                result.get("createMeeting").get("errors").size(), equalTo(0));
    }

    private static List<String> datesHoldingMeetings(final List<LocalDate> candidates) {
        final JsonNode days = client.execute(WORKSPACE_QUERY,
                Map.of("dates", candidates.stream().map(LocalDate::toString).toList()))
                .get("workspace").get("days");
        final List<String> withMeetings = new ArrayList<>();
        days.forEach(day -> {
            if (day.get("meetings").size() > 0) {
                withMeetings.add(day.get("date").asText());
            }
        });
        return withMeetings;
    }

    @Test
    @DisplayName("deletes history before the boundary, keeps the day ON it, and leaves later days untouched")
    void deletesOnlyWhatIsPastTheBoundary() {
        final LocalDate currentBoundary = LocalDate.parse(boundary());
        // Seeded AT and AFTER the current boundary, because writes behind it are rejected - that bound
        // is itself part of the design. Time is then moved forward instead, until the boundary the job
        // computes for itself has advanced past the earliest of the three.
        final LocalDate before = currentBoundary;
        final LocalDate onBoundary = currentBoundary.plusDays(TWO_WEEKS);
        final LocalDate after = onBoundary.plusDays(TWO_WEEKS);
        LOG.info("Seeding {} (before), {} (on the boundary) and {} (after)", before, onBoundary, after);
        seedMeetingOn(before);
        seedMeetingOn(onBoundary);
        seedMeetingOn(after);

        final List<LocalDate> seeded = List.of(before, onBoundary, after);
        assertThat("precondition: all three days hold a meeting",
                datesHoldingMeetings(seeded).size(), equalTo(3));

        // As if it were a retention window past the second seeded day: the job's own
        // Monday(asOf - RETENTION_DAYS) then lands on that day, so the first falls behind the new
        // boundary, the second sits exactly ON it, and the third is well clear.
        final LocalDate asOf = onBoundary.plusDays(RETENTION_DAYS);
        LOG.info("Running the cleanup job as if it were {}", asOf);
        final JsonNode summary = HistoryCleanup.runAsOf(asOf);
        final String newBoundary = summary.get("boundary").asText();

        // The boundary only ever moves forward, and lands on a Monday.
        assertThat(LocalDate.parse(newBoundary).getDayOfWeek(), equalTo(DayOfWeek.MONDAY));
        assertThat(newBoundary, greaterThanOrEqualTo(currentBoundary.toString()));
        assertThat("the API must now report what the job advanced to", boundary(), equalTo(newBoundary));

        final List<String> surviving = datesHoldingMeetings(seeded);
        // Asserting what SURVIVES matters as much as what goes: a job that deleted everything would
        // pass a test that only checked the old day was gone.
        assertThat(surviving, hasItem(after.toString()));
        assertThat(surviving, not(hasItem(before.toString())));
        assertThat("every surviving day must be on or after the boundary",
                surviving, everyItem(greaterThanOrEqualTo(newBoundary)));
    }

    @Test
    @DisplayName("a second run immediately after the first changes nothing")
    void isIdempotent() {
        final LocalDate asOf = LocalDate.parse(boundary()).plusDays(RETENTION_DAYS + TWO_WEEKS);
        HistoryCleanup.runAsOf(asOf);
        final String settled = boundary();

        final JsonNode second = HistoryCleanup.runAsOf(asOf);

        assertThat(second.get("datesDeleted").size(), equalTo(0));
        assertThat(boundary(), equalTo(settled));
    }

    @Test
    @DisplayName("a dry run reports what would go without moving the boundary or deleting anything")
    void dryRunChangesNothing() {
        final String settled = boundary();
        // A day that is legal to book now, but will be behind the boundary once the job runs later.
        final LocalDate willExpire = LocalDate.parse(settled).plusDays(1);
        seedMeetingOn(willExpire);

        final JsonNode summary = HistoryCleanup.dryRun(willExpire.plusDays(RETENTION_DAYS + TWO_WEEKS));

        final List<String> would = new ArrayList<>();
        summary.get("datesDeleted").forEach(date -> would.add(date.asText()));
        assertThat(would, hasItem(willExpire.toString()));
        assertThat("the boundary must not move on a dry run", boundary(), equalTo(settled));
        assertThat("the day must still be there", datesHoldingMeetings(List.of(willExpire)), hasItem(willExpire.toString()));
    }
}
