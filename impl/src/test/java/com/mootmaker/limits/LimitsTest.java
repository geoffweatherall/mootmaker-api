package com.mootmaker.limits;

import com.mootmaker.limits.Limits.Budget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of these is not that the numbers are right - it is that the <i>assertion</i> is real.
 * A check that only ever passes for the values compiled in cannot tell you it would catch a bad
 * change, which is the entire job of layer 1.
 */
class LimitsTest {

    @Nested
    @DisplayName("the shipped limits")
    class Configured {

        @Test
        void areConsistent() {
            assertDoesNotThrow(() -> Limits.assertConsistent());
        }

        @Test
        @DisplayName("a full day fits inside the DynamoDB item cap, with the modelled figure the design states")
        void produceADayItemInsideTheItemCap() {
            assertTrue(Limits.worstCaseDayItemBytes() <= Limits.DYNAMODB_MAX_ITEM_BYTES,
                    "worst-case day item was " + Limits.worstCaseDayItemBytes() + " bytes");
            // 212 + 37x20 + 280 = 1,232 bytes a meeting; x320 plus overhead is ~394 KB, ~96% of the cap.
            assertTrue(Limits.worstCaseMeetingBytes() == 1_232,
                    "worst-case meeting was " + Limits.worstCaseMeetingBytes() + " bytes, expected 1232 - "
                            + "if the persisted shape changed, the design's arithmetic needs revisiting too");
        }

        @Test
        void produceAResponseInsideTheAppSyncCap() {
            assertTrue(Limits.worstCaseResponseBytes() <= Limits.APPSYNC_MAX_RESPONSE_BYTES * Limits.RESPONSE_SAFETY_FRACTION,
                    "worst-case response was " + Limits.worstCaseResponseBytes() + " bytes");
        }

        @Test
        @DisplayName("the static limits alone are NOT sufficient - which is why the dynamic cap exists")
        void leaveTheStaticLimitsInsufficientOnTheirOwn() {
            final long staticWorstCase = (long) Limits.MAX_DATES_PER_REQUEST * Limits.MAX_MEETINGS_PER_DAY
                    * Limits.MEETING_JSON_BYTES;
            assertTrue(staticWorstCase > Limits.APPSYNC_MAX_RESPONSE_BYTES,
                    "the static limits now fit unaided (" + staticWorstCase + " bytes), so the fail-fast "
                            + "accumulation in the resolver is dead code and should be removed deliberately");
        }
    }

    @Nested
    @DisplayName("refuses to start")
    class Fires {

        @Test
        @DisplayName("when someone raises the day limit past what the item cap allows")
        void whenTheDayLimitIsRaisedTooFar() {
            final Budget tooManyMeetings = new Budget(400, 20, 280, 200, 1_000, 42, 2_000);
            final IllegalStateException thrown =
                    assertThrows(IllegalStateException.class, () -> Limits.assertConsistent(tooManyMeetings));
            assertTrue(thrown.getMessage().contains("a day at every limit is"), thrown.getMessage());
        }

        @Test
        @DisplayName("when someone raises the attendee limit without revisiting the day limit")
        void whenTheAttendeeLimitIsRaisedTooFar() {
            assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(320, 60, 280, 200, 1_000, 42, 2_000)));
        }

        @Test
        @DisplayName("when someone lengthens subjects without revisiting the day limit")
        void whenTheSubjectLimitIsRaisedTooFar() {
            assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(320, 20, 2_000, 200, 1_000, 42, 2_000)));
        }

        @Test
        @DisplayName("when the response cap is raised past what AppSync will carry")
        void whenTheResponseCapIsRaisedTooFar() {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(320, 20, 280, 200, 1_000, 42, 10_000)));
            assertTrue(thrown.getMessage().contains("a response at every limit is"), thrown.getMessage());
        }

        @Test
        @DisplayName("when the dynamic cap becomes unreachable, so it would silently stop doing anything")
        void whenTheDynamicCapCouldNeverFire() {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(320, 20, 280, 200, 1_000, 2, 2_000)));
            assertTrue(thrown.getMessage().contains("dynamic"), thrown.getMessage());
        }

        @Test
        void whenALimitIsZeroOrNegative() {
            assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(0, 20, 280, 200, 1_000, 42, 2_000)));
            assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(320, 20, -1, 200, 1_000, 42, 2_000)));
        }
    }

    @Nested
    @DisplayName("accepts")
    class Accepts {

        @Test
        @DisplayName("330 a day, which the design names as defensible - and 332, which it names as the ceiling")
        void theDayLimitsTheDesignSaysAreStillSafe() {
            assertDoesNotThrow(() -> Limits.assertConsistent(new Budget(330, 20, 280, 200, 1_000, 42, 2_000)));
            assertDoesNotThrow(() -> Limits.assertConsistent(new Budget(332, 20, 280, 200, 1_000, 42, 2_000)));
        }

        @Test
        @DisplayName("but not 333 - the design's stated ceiling is exactly where the arithmetic puts it")
        void butNotOneAboveThatCeiling() {
            assertThrows(IllegalStateException.class,
                    () -> Limits.assertConsistent(new Budget(333, 20, 280, 200, 1_000, 42, 2_000)));
        }

        @Test
        @DisplayName("16 attendees, the trade the design offers if the day cap ever binds")
        void theLowerAttendeeLimitThatMakesAPhysicallyFullDayFit() {
            assertDoesNotThrow(() -> Limits.assertConsistent(new Budget(360, 16, 280, 200, 1_000, 42, 2_000)));
        }
    }
}
