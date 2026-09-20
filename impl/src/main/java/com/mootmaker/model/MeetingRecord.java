package com.mootmaker.model;

import module java.base;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The shape a meeting is persisted in: room, organiser and attendees referenced by id only. See
 * {@link Meeting} for the fully-resolved shape used to build a GraphQL response.
 *
 * <p>A meeting is no longer an item of its own - it is one element of its day's {@code meetings}
 * list, so this converts to and from an {@code AttributeValue} rather than a whole item. The {@code
 * bucket = "ALL"} attribute went with it: that existed solely to give the deleted {@code
 * bucket-startTime-index} GSI a partition key, and every item in the table carried it.
 */
public record MeetingRecord(
    String id,
    String roomId,
    String organiserId,
    List<String> attendeeIds,
    String subject,
    String startTime,
    String endTime) {

  /**
   * Canonical, always-19-character format startTime/endTime are stored in, e.g.
   * "2026-07-01T09:00:00". CreateMeetingHandler formats every stored value with this rather than
   * trusting the client's raw input text, so the result is guaranteed fixed-width and therefore
   * lexicographically sortable as a plain string - required for the range queries
   * ListMeetingsHandler and the overlap check run against startTime/endTime and the
   * meeting-participants table's sortKey. Plain LocalDateTime.toString() isn't fixed-width: it
   * omits ":ss" when seconds are zero (e.g. midnight), which would break those comparisons.
   */
  public static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /**
   * The nested form: a meeting is no longer an item of its own, it is one element of its day's
   * {@code meetings} list. {@code bucket} is gone with the GSI it existed to give a partition key.
   *
   * <p>{@code startTime}/{@code endTime} are stored as {@code N} (epoch-minutes-since-UTC, see
   * {@link #toEpochMinutes}), not the 19-byte fixed-width ISO string they are everywhere else in
   * this class - see designs/dynamodb-storage-compaction.md. That is a pure storage-encoding choice
   * confined to this method and {@link #fromAttributeValue}: every other field and method on this
   * record, and everything outside this file, still sees the canonical ISO string.
   */
  public AttributeValue toAttributeValue() {
    final Map<String, AttributeValue> fields = new HashMap<>();
    fields.put("id", AttributeValue.builder().s(id).build());
    fields.put("roomId", AttributeValue.builder().s(roomId).build());
    fields.put("organiserId", AttributeValue.builder().s(organiserId).build());
    fields.put(
        "attendeeIds",
        AttributeValue.builder()
            .l(
                attendeeIds.stream()
                    .map(attendeeId -> AttributeValue.builder().s(attendeeId).build())
                    .toList())
            .build());
    fields.put("subject", AttributeValue.builder().s(subject).build());
    fields.put(
        "startTime", AttributeValue.builder().n(String.valueOf(toEpochMinutes(startTime))).build());
    fields.put(
        "endTime", AttributeValue.builder().n(String.valueOf(toEpochMinutes(endTime))).build());
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
        fromEpochMinutes(Long.parseLong(fields.get("startTime").n())),
        fromEpochMinutes(Long.parseLong(fields.get("endTime").n())));
  }

  /**
   * Minutes since the Unix epoch, treating {@code dateTime} as if it were UTC - an internal storage
   * convention, not a real timezone claim; these values have no real zone attached even in their
   * canonical string form (see the class javadoc's "LocalDateTime semantics"). Minutes rather than
   * milliseconds or seconds because every stored value already has {@code :00} seconds and falls on
   * a 15-minute boundary ({@code MeetingInput} validation enforces this), so finer precision would
   * only ever store information that never varies. Costs ~5 bytes as a DynamoDB {@code N} today (an
   * 8-digit number); good for roughly another 130 years before a 9th digit is needed.
   */
  private static long toEpochMinutes(final String canonicalDateTime) {
    return LocalDateTime.parse(canonicalDateTime, DATE_TIME_FORMAT).toEpochSecond(ZoneOffset.UTC)
        / 60;
  }

  private static String fromEpochMinutes(final long epochMinutes) {
    return LocalDateTime.ofEpochSecond(epochMinutes * 60, 0, ZoneOffset.UTC)
        .format(DATE_TIME_FORMAT);
  }

  /**
   * The calendar date this meeting belongs to - its day item's key. Meetings cannot span midnight.
   */
  public String date() {
    return startTime.substring(0, 10);
  }
}
