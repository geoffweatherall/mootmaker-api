package com.mootmaker.dynamo;

import com.mootmaker.limits.Limits;
import com.mootmaker.model.Boundaries;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingRecord;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import module java.base;

/**
 * The only code that writes a day item.
 *
 * <p>That exclusivity is the point of the class, not a side effect of tidiness. Five callers change
 * day items - createMeeting, createMeetings, deleteMyAccount, the history cleanup job, and the
 * retention test's seeding - and every one of them must do the same four things: read consistently,
 * write conditionally on the version, keep the {@code PTR#} pointers in the same transaction, and
 * measure the serialised item before sending it. Written inline five times those drift, and layer 3
 * of the size guarantee quietly becomes a convention rather than a guarantee.
 *
 * <p>Deliberately <b>not</b> a portability abstraction. There is no interface, no in-memory
 * implementation, and no expectation of ever swapping DynamoDB out. It is an instance rather than the
 * static utilities elsewhere in this package so that it owns its client, table name and retry budget -
 * an invariant cannot be bypassed by calling a static method with different arguments.
 *
 * <p>Constructed once at initialisation and held as a handler field, so it lands in the SnapStart
 * snapshot. Nothing here may hold state that must differ per restore.
 */
public final class DayRepository {

    /** Pointer items: meeting id to the date whose item holds it. The only secondary lookup kept. */
    public static final String POINTER_PK_PREFIX = "PTR#";

    /** The single config item, holding the retention boundary. Seeded by Terraform when the table is created. */
    public static final String RETENTION_CONFIG_PK = "CONFIG#retention";

    /**
     * Enough to clear realistic contention, few enough that a genuinely hot day fails rather than
     * retrying forever. Concurrent bookings on one date are single-figure at this scale.
     */
    private static final int MAX_WRITE_ATTEMPTS = 5;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final Clock clock;

    public DayRepository(final DynamoDbClient dynamoDbClient, final String tableName) {
        this(dynamoDbClient, tableName, Clock.systemUTC());
    }

    /** The clock is injectable so boundary tests do not depend on the day they are run. */
    public DayRepository(final DynamoDbClient dynamoDbClient, final String tableName, final Clock clock) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.clock = clock;
    }

    /**
     * The retention boundary is READ; the booking horizon is COMPUTED.
     *
     * <p>Storing the horizon as a date would need its own daily job to advance it. Storing it as a
     * length and adding it to the server's today keeps one authority - the server - with no second
     * schedule. The retention boundary genuinely is stored, because the cleanup job advances it
     * before deleting anything, and reading it consistently is what stops a stale, more permissive
     * value being handed to a client.
     */
    public Boundaries boundaries() {
        final Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", AttributeValue.builder().s(RETENTION_CONFIG_PK).build()))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            throw new IllegalStateException("No " + RETENTION_CONFIG_PK + " item in " + tableName
                    + ". Terraform seeds it when the table is created, so an environment without one "
                    + "was not built by the current configuration.");
        }
        final LocalDate today = LocalDate.now(clock);
        return new Boundaries(
                item.get("earliestRetainedDate").s(),
                today.plusDays(Limits.BOOKING_HORIZON_DAYS).toString());
    }

    /**
     * Reads one day with {@code ConsistentRead}. An absent item is returned as an empty day rather
     * than null: "no meetings that date" and "never written" are the same answer to every caller
     * here, and the version of 0 is what the conditional write turns into "must not exist".
     */
    public Day read(final String date) {
        final Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", AttributeValue.builder().s(Day.partitionKey(date)).build()))
                        .consistentRead(true)
                        .build())
                .item();
        return item == null || item.isEmpty() ? Day.empty(date) : Day.fromItem(item);
    }

    /** Reads several days, preserving the requested order and including days that hold nothing. */
    public List<Day> read(final List<String> dates) {
        return dates.stream().map(this::read).toList();
    }

    /**
     * Read, modify, write - the whole invariant in one place.
     *
     * <p>The change is a function rather than a value because it must be re-applied against fresh
     * state on a conflict. Re-running it is what makes "the day is full" a decision about the day as
     * it now is, rather than as it was when this call started reading.
     *
     * @return the day as written
     * @throws DayItemTooLargeException if the serialised item exceeds the cap (layer 3)
     * @throws IllegalStateException if the write still conflicts after {@link #MAX_WRITE_ATTEMPTS}
     */
    public Day mutate(final String date, final UnaryOperator<Day> change) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            final Day current = read(date);
            final Day changed = change.apply(current);
            final Day next = new Day(date, current.version() + 1, changed.meetings());

            final Map<String, AttributeValue> item = next.toItem();
            final int bytes = ItemSizer.sizeOf(item);
            if (bytes > Limits.DYNAMODB_MAX_ITEM_BYTES) {
                throw new DayItemTooLargeException(date, bytes, Limits.DYNAMODB_MAX_ITEM_BYTES);
            }

            try {
                dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(writeItems(current, next, item))
                        .build());
                return next;
            } catch (final ConditionalCheckFailedException | TransactionCanceledException e) {
                if (attempt == MAX_WRITE_ATTEMPTS) {
                    throw new IllegalStateException("Could not write day " + date + " after "
                            + MAX_WRITE_ATTEMPTS + " attempts - sustained write contention on one date.", e);
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * The day item plus one pointer write per meeting gained and one delete per meeting lost, in a
     * single transaction - so a pointer can never outlive the day that explains it, nor arrive before
     * it. That atomicity is why {@code MAX_MEETINGS_PER_BULK_CREATE} exists: DynamoDB caps a
     * transaction at 100 items, and one day plus 99 pointers is exactly that.
     */
    private List<TransactWriteItem> writeItems(final Day current, final Day next, final Map<String, AttributeValue> item) {
        final List<TransactWriteItem> items = new ArrayList<>();
        items.add(TransactWriteItem.builder().put(dayPut(current, item)).build());

        final Set<String> before = current.meetings().stream().map(MeetingRecord::id).collect(Collectors.toSet());
        final Set<String> after = next.meetings().stream().map(MeetingRecord::id).collect(Collectors.toSet());

        after.stream().filter(id -> !before.contains(id))
                .forEach(id -> items.add(TransactWriteItem.builder().put(pointerPut(id, next.date())).build()));
        before.stream().filter(id -> !after.contains(id))
                .forEach(id -> items.add(TransactWriteItem.builder().delete(pointerDelete(id)).build()));
        return items;
    }

    /**
     * Version 0 means the day has never been written, so the condition is "must not exist" rather
     * than "version must match" - otherwise two racing first-writes would both pass a comparison
     * against an attribute neither of them can see.
     */
    private Put dayPut(final Day current, final Map<String, AttributeValue> item) {
        final Put.Builder put = Put.builder().tableName(tableName).item(item);
        return current.version() == 0
                ? put.conditionExpression("attribute_not_exists(pk)").build()
                : put.conditionExpression("version = :expected")
                        .expressionAttributeValues(Map.of(":expected",
                                AttributeValue.builder().n(String.valueOf(current.version())).build()))
                        .build();
    }

    private Put pointerPut(final String meetingId, final String date) {
        return Put.builder().tableName(tableName).item(Map.of(
                "pk", AttributeValue.builder().s(POINTER_PK_PREFIX + meetingId).build(),
                "date", AttributeValue.builder().s(date).build())).build();
    }

    private Delete pointerDelete(final String meetingId) {
        return Delete.builder().tableName(tableName)
                .key(Map.of("pk", AttributeValue.builder().s(POINTER_PK_PREFIX + meetingId).build())).build();
    }

    /** Resolves a meeting id to its date, or empty if no pointer exists. Backs {@code meeting(id:)}. */
    public Optional<String> findDateOfMeeting(final String meetingId) {
        final Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("pk", AttributeValue.builder().s(POINTER_PK_PREFIX + meetingId).build()))
                        .consistentRead(true)
                        .build())
                .item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item.get("date").s());
    }

    /**
     * Moves the retention boundary forward, and refuses to move it back.
     *
     * <p>Monotonic on purpose. The boundary is what clients are told about how far back history goes,
     * so moving it backwards would advertise data that has already been deleted - the exact failure
     * the advance-then-delete ordering exists to prevent. A caller passing an earlier date is a bug,
     * and one that would be very hard to see from the outside, so it fails loudly here.
     */
    public void advanceBoundaryTo(final String monday) {
        final Boundaries current = boundaries();
        if (monday.compareTo(current.earliestRetainedDate()) < 0) {
            throw new IllegalArgumentException("Refusing to move the retention boundary backwards, from "
                    + current.earliestRetainedDate() + " to " + monday
                    + " - it would advertise history that may already be deleted.");
        }
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "pk", AttributeValue.builder().s(RETENTION_CONFIG_PK).build(),
                        "earliestRetainedDate", AttributeValue.builder().s(monday).build()))
                .build());
    }

    /**
     * Deletes every day strictly before {@code boundary}, each with its pointers, transactionally.
     *
     * <p>Strictly before: a day falling exactly ON the boundary is retained, because the boundary is
     * the earliest date still kept rather than the first one removed. That off-by-one is the whole
     * difference between honouring the retention promise and breaking it by a day.
     *
     * @return the dates deleted, so a caller can report or broadcast them
     */
    public List<String> deleteDaysBefore(final String boundary) {
        final List<Day> expired = scanDays().stream()
                .filter(day -> day.date().compareTo(boundary) < 0)
                .toList();

        for (final Day day : expired) {
            final List<TransactWriteItem> items = new ArrayList<>();
            items.add(TransactWriteItem.builder().delete(Delete.builder()
                    .tableName(tableName)
                    .key(Map.of("pk", AttributeValue.builder().s(Day.partitionKey(day.date())).build()))
                    .build()).build());
            day.meetings().forEach(meeting -> items.add(
                    TransactWriteItem.builder().delete(pointerDelete(meeting.id())).build()));
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(items).build());
        }
        return expired.stream().map(Day::date).sorted().toList();
    }

    /**
     * Every day item in the table, for the rare operations that genuinely need all of them -
     * account deletion, and the cleanup job. Cheap by construction: the horizon and retention bound
     * the table at 217 day items, which is why this replaced the meeting-participants join table
     * rather than an index replacing it.
     */
    public List<Day> scanDays() {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).consistentRead(true).build())
                .items().stream()
                .filter(item -> item.get("pk").s().startsWith(Day.PK_PREFIX))
                .map(Day::fromItem)
                .toList();
    }
}
