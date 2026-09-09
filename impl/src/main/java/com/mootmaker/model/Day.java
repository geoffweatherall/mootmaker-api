package com.mootmaker.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * Every meeting on one calendar date, as a single DynamoDB item.
 *
 * <p>The day is the unit the whole design turns on: one key identifies a DynamoDB item, an AppSync
 * fetch, an Apollo cache entity, and a subscription filter value. Reading a day by primary key is
 * what buys {@code ConsistentRead}, which removes the read-after-write class of bug outright - the
 * old per-meeting items were queried through a GSI, and GSIs reject consistent reads.
 *
 * <p>{@code version} exists for optimistic locking. Adding one meeting rewrites the whole item, so
 * two concurrent bookings on the same date will collide; the conditional write on this attribute is
 * what makes the loser retry against fresh state rather than silently overwrite. A day that has
 * never been written has version 0 and no item.
 */
public record Day(String date, long version, List<MeetingRecord> meetings) {

    /** Partition-key prefix. The table holds day items, id-to-date pointers and one config item. */
    public static final String PK_PREFIX = "DAY#";

    public Day {
        meetings = List.copyOf(meetings);
    }

    /** An unwritten day: present as a concept, absent from the table. Version 0 means "must not exist". */
    public static Day empty(final String date) {
        return new Day(date, 0, List.of());
    }

    public static String partitionKey(final String date) {
        return PK_PREFIX + date;
    }

    public Day withMeetings(final List<MeetingRecord> updated) {
        return new Day(date, version, updated);
    }

    public Map<String, AttributeValue> toItem() {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.builder().s(partitionKey(date)).build());
        item.put("date", AttributeValue.builder().s(date).build());
        item.put("version", AttributeValue.builder().n(String.valueOf(version)).build());
        item.put("meetings", AttributeValue.builder()
                .l(meetings.stream().map(MeetingRecord::toAttributeValue).toList())
                .build());
        return item;
    }

    public static Day fromItem(final Map<String, AttributeValue> item) {
        return new Day(
                item.get("date").s(),
                Long.parseLong(item.get("version").n()),
                item.get("meetings").l().stream().map(MeetingRecord::fromAttributeValue).toList());
    }
}
