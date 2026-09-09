package com.mootmaker.dynamo;

import com.mootmaker.limits.Limits;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayRepositoryTest {

    private static final String TABLE = "meetings";
    private static final String DATE = "2026-09-14";

    private FakeDynamoDbClient table;
    private DayRepository repository;

    @BeforeEach
    void setUp() {
        table = new FakeDynamoDbClient();
        repository = new DayRepository(table, TABLE);
    }

    private static MeetingRecord meeting(final String id, final String subject) {
        return new MeetingRecord(id, "room-1", "person-1", List.of("person-2"), subject,
                DATE + "T09:00:00", DATE + "T09:30:00");
    }

    /**
     * A 36-character id, the shape a UUID actually is. The byte model in {@code Limits} budgets for
     * UUIDs, so a test using longer ids measures a system nobody is running - and it will fail against
     * limits that are, in fact, correct.
     */
    private static String uuidShapedId(final int seed) {
        final String digits = "%032d".formatted(seed);
        return digits.substring(0, 8) + "-" + digits.substring(8, 12) + "-" + digits.substring(12, 16)
                + "-" + digits.substring(16, 20) + "-" + digits.substring(20);
    }

    /** Runs the interfering write with the hook disabled, so it cannot trigger itself. */
    private void withoutInterference(final Runnable write) {
        final Runnable hook = table.beforeWrite;
        table.beforeWrite = () -> { };
        try {
            write.run();
        } finally {
            table.beforeWrite = hook;
        }
    }

    private Day add(final MeetingRecord record) {
        return repository.mutate(DATE, day -> day.withMeetings(
                Stream.concat(day.meetings().stream(), Stream.of(record)).toList()));
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("an unwritten day is an empty day at version 0, not null")
        void returnsAnEmptyDayWhenNothingIsStored() {
            final Day day = repository.read(DATE);
            assertEquals(DATE, day.date());
            assertEquals(0, day.version());
            assertTrue(day.meetings().isEmpty());
        }

        @Test
        void roundTripsMeetingsThroughTheItem() {
            add(meeting("m-1", "Standup"));
            final Day day = repository.read(DATE);
            assertEquals(1, day.meetings().size());
            assertEquals("Standup", day.meetings().getFirst().subject());
            assertEquals(List.of("person-2"), day.meetings().getFirst().attendeeIds());
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        @DisplayName("the first write is conditional on the day not existing, and lands at version 1")
        void writesTheFirstVersionOfADay() {
            assertEquals(1, add(meeting("m-1", "Standup")).version());
            assertEquals(1, repository.read(DATE).version());
        }

        @Test
        void incrementsTheVersionOnEveryWrite() {
            add(meeting("m-1", "One"));
            add(meeting("m-2", "Two"));
            assertEquals(2, repository.read(DATE).version());
            assertEquals(2, repository.read(DATE).meetings().size());
        }

        @Test
        @DisplayName("a pointer is written with the day, in the same transaction")
        void writesAPointerForEachNewMeeting() {
            add(meeting("m-1", "Standup"));
            assertEquals(Optional.of(DATE), repository.findDateOfMeeting("m-1"));
            assertEquals(Optional.empty(), repository.findDateOfMeeting("never-existed"));
        }

        @Test
        @DisplayName("removing a meeting removes its pointer, so a pointer cannot outlive its day")
        void deletesThePointerWhenAMeetingGoes() {
            add(meeting("m-1", "Standup"));
            add(meeting("m-2", "Retro"));
            repository.mutate(DATE, day -> day.withMeetings(
                    day.meetings().stream().filter(m -> !m.id().equals("m-1")).toList()));

            assertEquals(Optional.empty(), repository.findDateOfMeeting("m-1"));
            assertEquals(Optional.of(DATE), repository.findDateOfMeeting("m-2"));
        }
    }

    @Nested
    @DisplayName("under contention")
    class Contention {

        @Test
        @DisplayName("re-applies the change against fresh state rather than overwriting the other writer")
        void retriesAndPreservesTheConcurrentWrite() {
            add(meeting("m-1", "First"));

            // Another writer lands between this call's read and its write, exactly once.
            final AtomicBoolean interfered = new AtomicBoolean(false);
            table.beforeWrite = () -> {
                if (interfered.compareAndSet(false, true)) {
                    withoutInterference(() -> new DayRepository(table, TABLE).mutate(DATE, day -> day.withMeetings(
                            Stream.concat(day.meetings().stream(), Stream.of(meeting("m-2", "Sneaked in"))).toList())));
                }
            };

            add(meeting("m-3", "Mine"));

            final Day day = repository.read(DATE);
            final List<String> ids = day.meetings().stream().map(MeetingRecord::id).sorted().toList();
            assertEquals(List.of("m-1", "m-2", "m-3"), ids,
                    "the concurrent write must survive - a last-write-wins overwrite would lose m-2");
            assertTrue(table.transactionAttempts >= 3, "expected a retry, saw " + table.transactionAttempts);
        }

        @Test
        @DisplayName("gives up rather than retrying forever when a date stays hot")
        void failsAfterTheRetryBudget() {
            add(meeting("m-1", "First"));
            // Every attempt is beaten to the write, so no attempt can ever succeed.
            table.beforeWrite = () -> withoutInterference(() ->
                    new DayRepository(table, TABLE).mutate(DATE, day -> day.withMeetings(
                            Stream.concat(day.meetings().stream(),
                                    Stream.of(meeting(UUID.randomUUID().toString(), "Filler"))).toList())));

            final IllegalStateException thrown =
                    assertThrows(IllegalStateException.class, () -> add(meeting("m-mine", "Mine")));
            assertTrue(thrown.getMessage().contains("attempts"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("layer 3")
    class SizeGuarantee {

        @Test
        @DisplayName("refuses an item over the cap even though validation let it through")
        void rejectsAnOversizedDayItem() {
            final String maxSubject = "x".repeat(Limits.MAX_SUBJECT_BYTES);
            final List<String> attendees = IntStream.range(0, Limits.MAX_ATTENDEES_PER_MEETING)
                    .mapToObj(DayRepositoryTest::uuidShapedId).toList();
            // Past the day limit on purpose: layer 2 would have stopped this, so reaching layer 3
            // means the model drifted - which is the case this exists to survive.
            final List<MeetingRecord> tooMany = IntStream.range(0, 400)
                    .mapToObj(i -> new MeetingRecord(uuidShapedId(i), uuidShapedId(i + 1_000_000), uuidShapedId(i + 2_000_000),
                            attendees, maxSubject, DATE + "T09:00:00", DATE + "T09:30:00"))
                    .toList();

            final DayItemTooLargeException thrown = assertThrows(DayItemTooLargeException.class,
                    () -> repository.mutate(DATE, day -> day.withMeetings(tooMany)));
            assertTrue(thrown.actualBytes() > Limits.DYNAMODB_MAX_ITEM_BYTES);
            assertTrue(table.tables.getOrDefault(TABLE, List.of()).isEmpty(), "nothing may be written when the item is refused");
        }

        @Test
        @DisplayName("a day at every configured limit is accepted - the model and the measurement agree")
        void acceptsADayAtEveryConfiguredLimit() {
            final String maxSubject = "x".repeat(Limits.MAX_SUBJECT_BYTES);
            final List<String> attendees = IntStream.range(0, Limits.MAX_ATTENDEES_PER_MEETING)
                    .mapToObj(DayRepositoryTest::uuidShapedId).toList();
            final List<MeetingRecord> full = IntStream.range(0, Limits.MAX_MEETINGS_PER_DAY)
                    .mapToObj(i -> new MeetingRecord(uuidShapedId(i), uuidShapedId(i + 1_000_000),
                            uuidShapedId(i + 2_000_000), attendees, maxSubject,
                            DATE + "T09:00:00", DATE + "T09:30:00"))
                    .toList();

            repository.mutate(DATE, day -> day.withMeetings(full));
            assertEquals(Limits.MAX_MEETINGS_PER_DAY, repository.read(DATE).meetings().size());
        }
    }

    @Nested
    @DisplayName("scanning")
    class Scanning {

        @Test
        @DisplayName("returns day items only - never pointers, and never the config item")
        void ignoresEverythingThatIsNotADay() {
            add(meeting("m-1", "Standup"));
            repository.mutate("2026-09-15", day -> day.withMeetings(List.of(
                    new MeetingRecord("m-2", "room-1", "person-1", List.of(), "Other day",
                            "2026-09-15T09:00:00", "2026-09-15T09:30:00"))));

            assertTrue(table.tables.get(TABLE).stream().anyMatch(item -> item.get("pk").s().startsWith(DayRepository.POINTER_PK_PREFIX)),
                    "precondition: pointers exist in the table");
            final List<Day> days = repository.scanDays();
            assertEquals(2, days.size());
            assertFalse(days.stream().anyMatch(day -> day.date() == null));
        }
    }
}
