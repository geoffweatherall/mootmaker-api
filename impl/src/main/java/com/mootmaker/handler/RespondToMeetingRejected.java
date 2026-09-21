package com.mootmaker.handler;

import com.mootmaker.model.RespondToMeetingError;

/**
 * Carries a {@code respondToMeeting} rejection out of a read-modify-write, exactly mirroring {@link
 * MeetingRejected}'s role for {@code createMeeting} - see that class's javadoc for why this is an
 * exception rather than a return value. Single-error rather than a list: unlike a meeting's several
 * independent validation rules, every case here (no linked Person, meeting not found, caller not an
 * attendee) is mutually exclusive with the others, so there is never more than one to report.
 */
final class RespondToMeetingRejected extends RuntimeException {

  private final transient RespondToMeetingError error;

  RespondToMeetingRejected(final RespondToMeetingError error) {
    super(error.name(), null, false, false);
    this.error = error;
  }

  RespondToMeetingError error() {
    return error;
  }
}
