package com.mootmaker.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * The shape a meeting is persisted in DynamoDB: room, organiser, and attendees are referenced by
 * id only. See {@link Meeting} for the fully-resolved shape (room/organiser/attendees looked up)
 * used to build a GraphQL response.
 */
public record MeetingRecord(String id, String roomId, String organiserId, List<String> attendeeIds, String subject, String startTime,
        String endTime) {

    /**
     * Constant hash key for the bucket-startTime-index GSI (see dynamodb.tf) - there's no other
     * partitioning dimension for "every meeting's startTime", so every item shares this value.
     */
    private static final String BUCKET = "ALL";

    /**
     * Canonical, always-19-character format startTime/endTime are stored in, e.g.
     * "2026-07-01T09:00:00". CreateMeetingHandler formats every stored value with this rather
     * than trusting the client's raw input text, so the result is guaranteed fixed-width and
     * therefore lexicographically sortable as a plain string - required for the range queries
     * ListMeetingsHandler and the overlap check run against startTime/endTime and the
     * meeting-participants table's sortKey. Plain LocalDateTime.toString() isn't fixed-width: it
     * omits ":ss" when seconds are zero (e.g. midnight), which would break those comparisons.
     */
    public static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * The nested form: a meeting is no longer an item of its own, it is one element of its day's
     * {@code meetings} list. {@code bucket} is gone with the GSI it existed to give a partition key.
     */
    public AttributeValue toAttributeValue() {
        final Map<String, AttributeValue> fields = new HashMap<>();
        fields.put("id", AttributeValue.builder().s(id).build());
        fields.put("roomId", AttributeValue.builder().s(roomId).build());
        fields.put("organiserId", AttributeValue.builder().s(organiserId).build());
        fields.put("attendeeIds", AttributeValue.builder()
                .l(attendeeIds.stream().map(attendeeId -> AttributeValue.builder().s(attendeeId).build()).toList())
                .build());
        fields.put("subject", AttributeValue.builder().s(subject).build());
        fields.put("startTime", AttributeValue.builder().s(startTime).build());
        fields.put("endTime", AttributeValue.builder().s(endTime).build());
        return AttributeValue.builder().m(fields).build();
    }

    public static MeetingRecord fromAttributeValue(final AttributeValue value) {
        final Map<String, AttributeValue> fields = value.m();
        return new MeetingRecord(
                fields.get("id").s(),
                fields.get("roomId").s(),
                fields.get("organiserId").s(),
                fields.get("attendeeIds").l().stream().map(AttributeValue::s).toList(),
                fields.get("subject").s(),
                fields.get("startTime").s(),
                fields.get("endTime").s());
    }

    /** The calendar date this meeting belongs to - its day item's key. Meetings cannot span midnight. */
    public String date() {
        return startTime.substring(0, 10);
    }

    public Map<String, AttributeValue> toItem() {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().s(id).build());
        item.put("bucket", AttributeValue.builder().s(BUCKET).build());
        item.put("roomId", AttributeValue.builder().s(roomId).build());
        item.put("organiserId", AttributeValue.builder().s(organiserId).build());
        item.put("attendeeIds", AttributeValue.builder()
                .l(attendeeIds.stream().map(attendeeId -> AttributeValue.builder().s(attendeeId).build()).toList())
                .build());
        item.put("subject", AttributeValue.builder().s(subject).build());
        item.put("startTime", AttributeValue.builder().s(startTime).build());
        item.put("endTime", AttributeValue.builder().s(endTime).build());
        return item;
    }

    public static MeetingRecord fromItem(final Map<String, AttributeValue> item) {
        final List<String> attendeeIds = item.get("attendeeIds").l().stream().map(AttributeValue::s).toList();
        return new MeetingRecord(
                item.get("id").s(),
                item.get("roomId").s(),
                item.get("organiserId").s(),
                attendeeIds,
                item.get("subject").s(),
                item.get("startTime").s(),
                item.get("endTime").s());
    }
}
