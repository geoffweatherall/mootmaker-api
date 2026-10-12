package com.mootmaker.model;

import module java.base;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The shape a meeting is persisted in: room, organiser and attendees referenced by id only. See
 * {@link Meeting} for the fully-resolved shape used to build a GraphQL response.
 *
 * <p>A meeting is not an item of its own: it is one element of its day's {@code meetings} list, so
 * this converts to and from an {@code AttributeValue} rather than a whole item.
 */
public record MeetingRecord(
    String id,
    String roomId,
    String organiserId,
    List<String> attendeeIds,
    List<AttendeeStatus> attendeeStatuses,
    String subject,
    String startTime,
    String endTime) {

  /**
   * Enforces the invariant every write in this class relies on: {@code attendeeStatuses} is a
   * sibling list to {@code attendeeIds}, same length and order, index {@code i} of one corresponds
   * to index {@code i} of the other - never a separate per-index update anywhere in this model, so
   * the two cannot drift apart between writes (see designs/archive/attendee-response-status.md's
   * "Trade-offs and decisions"). Failing loudly here, at construction, is what stops a mismatched
   * pair being written at all rather than being discovered on read.
   */
  public MeetingRecord {
    if (attendeeStatuses.size() != attendeeIds.size()) {
      throw new IllegalArgumentException(
          "attendeeStatuses must be the same length as attendeeIds: "
              + attendeeIds.size()
              + " attendee(s), "
              + attendeeStatuses.size()
              + " status(es)");
    }
  }

  /**
   * The canonical, always-19-character form of startTime/endTime, e.g. "2026-07-01T09:00:00". Every
   * time is formatted with this rather than kept as the client sent it, so times are fixed-width
   * and compare correctly as plain strings, which the overlap check and "upcoming" rely on. {@code
   * LocalDateTime.toString()} is not fixed-width: it omits ":ss" when the seconds are zero.
   */
  public static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  /**
   * The form stored as one element of a day item's {@code meetings} list.
   *
   * <p>{@code startTime}/{@code endTime} are stored as {@code N} (epoch-minutes-since-UTC, see
   * {@link #toEpochMinutes}), not the 19-byte fixed-width ISO string they are everywhere else in
   * this class - see designs/archive/dynamodb-storage-compaction.md. That is a pure
   * storage-encoding choice confined to this method and {@link #fromAttributeValue}: every other
   * field and method on this record, and everything outside this file, still sees the canonical ISO
   * string.
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
    // A parallel List<S> of single-character codes, exactly like attendeeIds above, matching the
    // "1 byte code (+1 overhead = 2)" per-attendee budget designs/archive/dynamodb-storage-
    // compaction.md reserved for this field. Same length and order as attendeeIds by construction
    // (the compact constructor enforces it), so index i of one always corresponds to index i of
    // the other.
    fields.put(
        "attendeeStatuses",
        AttributeValue.builder()
            .l(
                attendeeStatuses.stream()
                    .map(status -> AttributeValue.builder().s(status.code()).build())
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
        // Required, not defaulted: every stored meeting has statuses, so a missing value means
        // corrupt data, and failing loudly beats silently inventing statuses nobody set.
        fields.get("attendeeStatuses").l().stream()
            .map(AttributeValue::s)
            .map(AttendeeStatus::fromCode)
            .toList(),
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

  /** This meeting's version - see {@link MeetingVersion}. */
  public String version() {
    return MeetingVersion.of(roomId, organiserId, attendeeIds, subject, startTime, endTime);
  }
}
