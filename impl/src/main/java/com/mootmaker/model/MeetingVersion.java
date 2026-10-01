package com.mootmaker.model;

import module java.base;

/**
 * A meeting's version: a fingerprint of everything {@code updateMeeting} can change - room,
 * organiser, attendees, subject, start and end (mootmaker-api#96).
 *
 * <p>{@code updateMeeting} replaces every field, so an edit form saved from a stale copy used to
 * silently undo whatever changed since the form was opened. A client now sends the version it
 * edited ({@code MeetingInput.expectedVersion}); if the meeting has changed since, the update is
 * rejected with {@code MeetingChanged} instead of overwriting.
 *
 * <p><b>Derived, not stored.</b> Meetings live inside each day's item; a stored counter would mean
 * a storage-format change and a backfill of every existing meeting. A fingerprint of the editable
 * fields detects every change that matters, and needs neither. Attendee responses are deliberately
 * not part of it: they are not something an edit form sets (an edit preserves them, see
 * UpdateMeetingHandler), so an attendee responding must not block the organiser's save. The one
 * thing a fingerprint cannot see - a change and its exact reversal between two reads - leaves the
 * meeting just as the editor saw it, so overwriting it loses nothing.
 */
public final class MeetingVersion {

  private MeetingVersion() {}

  /** Attendee order is not meaningful, so it does not change the version. */
  public static String of(
      final String roomId,
      final String organiserId,
      final List<String> attendeeIds,
      final String subject,
      final String startTime,
      final String endTime) {
    final List<String> attendees = attendeeIds.stream().sorted().toList();
    final String canonical =
        String.join(
            "\u0000",
            roomId,
            organiserId,
            String.join(",", attendees),
            subject,
            startTime,
            endTime);
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
