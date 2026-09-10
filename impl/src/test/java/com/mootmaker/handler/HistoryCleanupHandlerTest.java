package com.mootmaker.handler;

import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCleanupHandlerTest {

    private static final String TABLE = "Meetings";

    /** A Wednesday, so "the Monday on or before today minus 30" is not trivially today. */
    private static final Clock WEDNESDAY = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);

    /** 2026-09-09 minus 30 days is 2026-08-10, which is itself a Monday. */
    private static final String EXPECTED_BOUNDARY = "2026-08-10";

    private FakeDynamoDbClient fakeClient;
    private DayRepository days;
    private HistoryCleanupHandler handler;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put(TABLE, new ArrayList<>(List.of(DayFixtures.retentionConfig("2026-07-01"))));
        days = new DayRepository(fakeClient, TABLE, WEDNESDAY);
        handler = new HistoryCleanupHandler(days, WEDNESDAY);
    }

    private void seed(final String... dates) {
        for (final String date : dates) {
            DayFixtures.addMeeting(fakeClient, TABLE, new MeetingRecord(
                    "meeting-" + date, "room-1", "person-1", List.of("person-2"), "Seeded",
                    date + "T09:00:00", date + "T09:30:00"));
        }
    }

    private List<String> remainingDates() {
        return days.scanDays().stream().map(day -> day.date()).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> run() {
        return (List<String>) handler.handleRequest(Map.of(), null).get("datesDeleted");
    }

    @Test
    @DisplayName("advances to the Monday on or before today minus the retention minimum")
    void advancesTheBoundaryToTheCorrectMonday() {
        handler.handleRequest(Map.of(), null);

        assertEquals(EXPECTED_BOUNDARY, days.boundaries().earliestRetainedDate());
        assertEquals(DayOfWeek.MONDAY, LocalDate.parse(EXPECTED_BOUNDARY).getDayOfWeek());
        // The promise is "at least 30 days"; Monday alignment can only ever keep more, never less.
        assertTrue(LocalDate.parse(EXPECTED_BOUNDARY)
                .isBefore(LocalDate.now(WEDNESDAY).minusDays(Limits.RETENTION_DAYS_MINIMUM).plusDays(1)));
    }

    @Test
    @DisplayName("keeps the day ON the boundary and removes the one before it")
    void treatsTheBoundaryAsTheEarliestDateStillKept() {
        // The off-by-one that separates honouring the retention promise from breaking it by a day.
        seed("2026-08-09", EXPECTED_BOUNDARY, "2026-08-11");

        assertEquals(List.of("2026-08-09"), run());
        assertEquals(List.of(EXPECTED_BOUNDARY, "2026-08-11"), remainingDates());
    }

    @Test
    @DisplayName("asserts what SURVIVES, not just what goes - a job that deletes too much passes the other test")
    void leavesEverythingOnOrAfterTheBoundaryUntouched() {
        seed("2026-06-01", "2026-07-15", "2026-08-09", EXPECTED_BOUNDARY, "2026-09-01", "2026-12-25");

        run();

        assertEquals(List.of(EXPECTED_BOUNDARY, "2026-09-01", "2026-12-25"), remainingDates());
    }

    @Test
    @DisplayName("pointers go with their days, so meeting(id:) cannot resolve one whose day is gone")
    void removesThePointersOfDeletedDays() {
        seed("2026-06-01", "2026-09-01");

        run();

        assertEquals(Optional.empty(), days.findDateOfMeeting("meeting-2026-06-01"));
        assertEquals(Optional.of("2026-09-01"), days.findDateOfMeeting("meeting-2026-09-01"));
    }

    @Test
    @DisplayName("a job that has not run for weeks catches up in one pass, rather than falling permanently behind")
    void catchesUpAfterMissedRuns() {
        // The boundary is computed from today, not from the stored value - which is what makes this
        // work without any special case for "how long were we down".
        seed("2026-05-01", "2026-06-01", "2026-07-01", "2026-08-01", "2026-09-01");

        final List<String> deleted = run();

        assertEquals(List.of("2026-05-01", "2026-06-01", "2026-07-01", "2026-08-01"), deleted);
        assertEquals(EXPECTED_BOUNDARY, days.boundaries().earliestRetainedDate());
    }

    @Test
    @DisplayName("a second run immediately after the first changes nothing")
    void isIdempotent() {
        seed("2026-06-01", "2026-09-01");
        run();

        final List<String> second = run();

        assertTrue(second.isEmpty(), "nothing left to delete, so nothing should be reported");
        assertEquals(List.of("2026-09-01"), remainingDates());
        assertEquals(EXPECTED_BOUNDARY, days.boundaries().earliestRetainedDate());
    }

    @Test
    @DisplayName("the invariant itself: after any run, no day exists earlier than the stored boundary")
    void leavesNoDayEarlierThanTheBoundary() {
        seed("2026-05-01", "2026-08-09", EXPECTED_BOUNDARY, "2026-10-01");

        run();

        final String boundary = days.boundaries().earliestRetainedDate();
        assertTrue(remainingDates().stream().allMatch(date -> date.compareTo(boundary) >= 0),
                "found a day before " + boundary + " in " + remainingDates());
    }

    @Test
    @DisplayName("a dry run reports what would go without touching the boundary or the data")
    void dryRunChangesNothing() {
        seed("2026-06-01", "2026-09-01");

        @SuppressWarnings("unchecked")
        final List<String> would = (List<String>) handler.handleRequest(Map.of("dryRun", true), null).get("datesDeleted");

        assertEquals(List.of("2026-06-01"), would);
        assertEquals("2026-07-01", days.boundaries().earliestRetainedDate(), "the boundary must not move");
        assertEquals(List.of("2026-06-01", "2026-09-01"), remainingDates(), "nothing may be deleted");
    }

    @Test
    @DisplayName("refuses to move the boundary backwards, which would advertise deleted history")
    void refusesToRewindTheBoundary() {
        fakeClient.tables.put(TABLE, new ArrayList<>(List.of(DayFixtures.retentionConfig("2027-01-04"))));

        assertThrows(IllegalArgumentException.class, () -> days.advanceBoundaryTo(EXPECTED_BOUNDARY));
    }
}
