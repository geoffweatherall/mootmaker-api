package com.mootmaker.limits;

import module java.base;

/**
 * Every size limit in the system, and the arithmetic proving they are consistent with the platform
 * caps they exist to respect.
 *
 * <p><b>This is layer 1 of the item-size guarantee.</b> The requirement is absolute: there must be no
 * input a user can construct that produces a DynamoDB item over 400 KB. Meeting that needs the limits
 * to be provably consistent with each other, not merely individually sensible - so
 * {@link #assertConsistent()} runs during Lambda initialisation and refuses to start if they are not.
 *
 * <p>SnapStart lands this in exactly the right place: publishing a Lambda version <i>executes init</i>,
 * so an inconsistent set of limits fails the <b>deploy</b> rather than a user's booking. That is what
 * stops the guarantee quietly decaying when someone later raises a limit, or adds a field to the
 * persisted meeting shape without revisiting the arithmetic.
 *
 * <p>Layer 2 is per-request validation. Layer 3 measures the actual serialised item immediately before
 * the write, and is the backstop that holds even if the byte model below is wrong - and it will drift,
 * because it is an estimate of DynamoDB's own accounting rather than a reading of it.
 */
public final class Limits {

    // ── Platform caps. None of these is adjustable; they are the reason every other number exists. ──

    /** DynamoDB's hard maximum item size. */
    public static final int DYNAMODB_MAX_ITEM_BYTES = 400 * 1024;

    /** AppSync's resolver response cap. */
    public static final int APPSYNC_MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    /** AppSync's subscription payload cap - the tightest limit anywhere in this design. */
    public static final int APPSYNC_MAX_SUBSCRIPTION_BYTES = 240 * 1024;

    /**
     * How much of the RESPONSE cap the modelled worst case may occupy.
     *
     * <p>The item bound deliberately has no equivalent, and the asymmetry is the point. A day item is
     * measured for real immediately before it is written (layer 3), so if this byte model is optimistic
     * the consequence is a premature "day is full" rejection - never an oversized item. The guarantee
     * holds on measurement, not on the model, so budgeting the model below 100% would buy nothing.
     *
     * <p>Nothing measures the real response at run time. The dynamic meetings-per-response cap counts
     * meetings and multiplies by the modelled size, so there the model IS the guarantee - and a margin
     * is worth having.
     */
    public static final double RESPONSE_SAFETY_FRACTION = 0.98;

    // ── Business limits. ──

    /**
     * Physical capacity is 360 meetings a day (10 rooms x 36 fifteen-minute slots), so this can
     * refuse a booking with a room standing free - but only at 89% of the building. The error must
     * therefore say the DAY is full, never the room, or the rejection is unexplainable.
     */
    public static final int MAX_MEETINGS_PER_DAY = 320;

    public static final int MAX_ATTENDEES_PER_MEETING = 20;

    /**
     * Measured in UTF-8 bytes of NFC-normalised text, because that is the unit DynamoDB itself sizes
     * string attributes in - so the limit and the item cost are the same quantity rather than two
     * numbers that can drift apart. Plain text gets 280 characters; simple emoji get 70.
     */
    public static final int MAX_SUBJECT_BYTES = 280;

    public static final int MAX_ROOM_NAME_BYTES = 100;
    public static final int MAX_PERSON_NAME_BYTES = 100;

    /** Bounds {@code rooms}, which is an unfiltered scan. */
    public static final int MAX_ROOMS = 200;

    /** Bounds {@code people}, same reasoning. */
    public static final int MAX_PEOPLE = 1_000;

    /**
     * The person calendar shows 6 weeks x 5 weekdays = 30 dates spanning 39 calendar days, so this
     * covers the whole span with a week to spare - and keeps working if the UI ever shows weekends,
     * which a tighter bound would not.
     */
    public static final int MAX_DATES_PER_REQUEST = 42;

    /**
     * The dynamic bound, and the one that actually makes the response guarantee hold. The static
     * limits alone permit a request that cannot be answered - see {@link #assertConsistent()}.
     */
    public static final int MAX_MEETINGS_PER_RESPONSE = 2_000;

    /**
     * How many meetings one {@code createMeetings} call may carry.
     *
     * <p>Not in the original design, and it exists for a specific reason: the day item and the
     * {@code PTR#} pointer for every meeting it gains are written in ONE {@code TransactWriteItems},
     * and DynamoDB caps that at 100 items. One day item plus 99 pointers is exactly 100. Without this
     * limit a bulk create of 320 - which the day cap permits - would fail at the transaction rather
     * than at validation, and the alternatives (chunking, or writing pointers outside the
     * transaction) both give up the atomicity that makes a pointer unable to outlive its day.
     *
     * <p>Well clear of real use: mootmaker-demo-data creates roughly 20 meetings a day. A client
     * wanting more makes more calls.
     */
    public static final int MAX_MEETINGS_PER_BULK_CREATE = 99;

    /** DynamoDB's cap on items in a single transaction. */
    public static final int DYNAMODB_MAX_TRANSACT_ITEMS = 100;

    /** How far ahead a meeting may be booked. With retention, bounds the table at 217 day items. */
    public static final int BOOKING_HORIZON_DAYS = 180;

    /**
     * Monday alignment makes retention a range rather than a fixed number - between 30 and 37 days
     * depending where in the week the cleanup job falls - so the storage bound uses the worst case.
     */
    public static final int RETENTION_DAYS_WORST_CASE = 37;

    // ── The byte model. Estimates of DynamoDB's and AppSync's accounting, held honest by layer 3. ──

    /** Attribute names and fixed-width values of one stored meeting, excluding attendees and subject. */
    static final int PER_MEETING_BASE_BYTES = 212;

    /** One attendee id: a 36-character UUID plus list-element overhead. */
    static final int BYTES_PER_ATTENDEE = 37;

    /** pk, date and version attributes wrapping the meetings list. */
    static final int DAY_ITEM_OVERHEAD_BYTES = 128;

    /**
     * One meeting as JSON, which is much larger than its stored form: attribute names repeat per
     * object, and Apollo adds {@code __typename} to every selection set. At 20 attendees that is 23
     * objects and roughly 480 bytes of {@code __typename} alone - a quarter of the payload.
     */
    static final int MEETING_JSON_BYTES = 1_931;

    static final int ROOM_JSON_BYTES = 189;
    static final int PERSON_JSON_BYTES = 178;

    /** One ISO-8601 date in a JSON array, e.g. {@code "2026-09-14",}. */
    static final int DATE_JSON_BYTES = 13;

    private Limits() {
    }

    /**
     * The configurable limits, bundled so the invariant can be checked against a hypothetical set as
     * well as the real one. Following {@code Identity.requireAdmin}'s precedent: a package-private
     * overload taking the values directly is what lets a test prove the assertion actually fires,
     * rather than only that it passes for the values that happen to be compiled in.
     */
    record Budget(int maxMeetingsPerDay, int maxAttendees, int maxSubjectBytes,
            int maxRooms, int maxPeople, int maxDates, int maxMeetingsPerResponse) {

        int worstCaseMeetingBytes() {
            return PER_MEETING_BASE_BYTES + BYTES_PER_ATTENDEE * maxAttendees + maxSubjectBytes;
        }

        int worstCaseDayItemBytes() {
            return maxMeetingsPerDay * worstCaseMeetingBytes() + DAY_ITEM_OVERHEAD_BYTES;
        }

        int worstCaseResponseBytes() {
            return maxRooms * ROOM_JSON_BYTES + maxPeople * PERSON_JSON_BYTES
                    + maxMeetingsPerResponse * MEETING_JSON_BYTES;
        }
    }

    /** The limits this build actually enforces. */
    static final Budget CONFIGURED = new Budget(MAX_MEETINGS_PER_DAY, MAX_ATTENDEES_PER_MEETING, MAX_SUBJECT_BYTES,
            MAX_ROOMS, MAX_PEOPLE, MAX_DATES_PER_REQUEST, MAX_MEETINGS_PER_RESPONSE);

    /** The modelled worst-case size of one stored meeting. */
    public static int worstCaseMeetingBytes() {
        return CONFIGURED.worstCaseMeetingBytes();
    }

    /** The modelled worst-case size of one stored day item. */
    public static int worstCaseDayItemBytes() {
        return CONFIGURED.worstCaseDayItemBytes();
    }

    /** The modelled worst-case size of a workspace response at every limit simultaneously. */
    public static int worstCaseResponseBytes() {
        return CONFIGURED.worstCaseResponseBytes();
    }

    /**
     * Refuses to return if the limits this build was compiled with cannot hold their guarantees.
     * Called from resolver initialisation so it runs when a Lambda version is published, not when a
     * user tries to book.
     *
     * @throws IllegalStateException naming the specific invariant that fails
     */
    public static void assertConsistent() {
        assertConsistent(CONFIGURED);
    }

    static void assertConsistent(final Budget budget) {
        require(budget.maxMeetingsPerDay() > 0 && budget.maxAttendees() > 0 && budget.maxSubjectBytes() > 0
                        && budget.maxRooms() > 0 && budget.maxPeople() > 0 && budget.maxDates() > 0
                        && budget.maxMeetingsPerResponse() > 0,
                "every limit must be positive");

        require(budget.worstCaseDayItemBytes() <= DYNAMODB_MAX_ITEM_BYTES,
                "a day at every limit is " + budget.worstCaseDayItemBytes() + " bytes, over the "
                        + DYNAMODB_MAX_ITEM_BYTES + "-byte item cap. "
                        + "Lower the meetings-per-day, attendee or subject limit, or revisit the byte model "
                        + "if the persisted meeting shape gained a field.");

        final long responseBudget = (long) (APPSYNC_MAX_RESPONSE_BYTES * RESPONSE_SAFETY_FRACTION);
        require(budget.worstCaseResponseBytes() <= responseBudget,
                "a response at every limit is " + budget.worstCaseResponseBytes() + " bytes, over the "
                        + responseBudget + "-byte budget. Lower the meetings-per-response, rooms or people limit.");

        // Not a redundant check: it asserts the DYNAMIC cap is doing real work. If the static limits
        // alone ever became sufficient, the fail-fast accumulation in the resolver would be dead code -
        // so this documents why it exists, and fails loudly if that ever stops being true.
        require((long) budget.maxDates() * budget.maxMeetingsPerDay() > budget.maxMeetingsPerResponse(),
                "maxDates x maxMeetingsPerDay no longer exceeds maxMeetingsPerResponse, so the dynamic "
                        + "response cap is unreachable and should be reconsidered rather than quietly kept.");

        require(1 + MAX_MEETINGS_PER_BULK_CREATE <= DYNAMODB_MAX_TRANSACT_ITEMS,
                "one day item plus MAX_MEETINGS_PER_BULK_CREATE pointers exceeds DynamoDB's "
                        + DYNAMODB_MAX_TRANSACT_ITEMS + "-item transaction cap, so a full bulk create would "
                        + "fail at the write rather than at validation.");

        final long subscriptionBudget = (long) (APPSYNC_MAX_SUBSCRIPTION_BYTES * RESPONSE_SAFETY_FRACTION);
        require((long) budget.maxDates() * DATE_JSON_BYTES <= subscriptionBudget,
                "a full invalidation payload would exceed the subscription cap - which should be "
                        + "unreachable by construction, so this failing means the payload carries data again.");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException("Size limits are inconsistent: " + message);
        }
    }
}
