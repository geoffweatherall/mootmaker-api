package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code AttendeeStatus} enum. Constant names must match the schema's enum
 * value names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>Stored as a single-character code ({@link #code()}), not the enum name, per
 * designs/attendee-response-status.md's byte budget - the same field
 * designs/archive/dynamodb-storage-compaction.md already reserved "1 byte code (+1 overhead = 2)"
 * per attendee for.
 */
public enum AttendeeStatus {
  Going("G"),
  NotGoing("N"),
  Maybe("M"),
  NoResponse("U");

  private final String code;

  AttendeeStatus(final String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  /**
   * Inverse of {@link #code()}. Throws on an unrecognised code rather than substituting a default -
   * unlike a Person's absent display preference, a corrupt/unknown status code on a real attendee
   * signals a genuine data problem worth failing loudly on, not a "never chose yet" case.
   */
  public static AttendeeStatus fromCode(final String code) {
    for (final AttendeeStatus status : values()) {
      if (status.code.equals(code)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unrecognised AttendeeStatus code: " + code);
  }
}
