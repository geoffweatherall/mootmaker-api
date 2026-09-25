package com.mootmaker.handler;

import module java.base;

import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingRecord;

/**
 * Shared by every handler that needs to know about a person's or a room's upcoming meetings: {@code
 * DeleteMyAccountHandler} (cascading the caller's own account), {@code DeletePersonHandler} (the
 * same cascade, admin-invoked against someone else - see its own doc comment for why the cascade
 * itself is identical), and {@code DeleteRoomHandler} (which blocks rather than cascades - see its
 * own doc comment for why room deletion's blast radius is treated differently from a person's).
 *
 * <p>Found by <b>scanning day items</b>, which is what replaced the old {@code
 * meeting-participants} join table. That table existed to answer one question - "every meeting this
 * person/room is in, with no date range" - for exactly these rare operations, at the cost of a row
 * per participant per meeting kept consistent on every write. The booking horizon and retention
 * bound the table at 217 day items, so a scan is both trivial and, unlike a computed range of
 * horizon dates, exact: it cannot miss a day the arithmetic got wrong.
 */
final class UpcomingMeetings {

  private UpcomingMeetings() {}

  static boolean isUpcoming(final MeetingRecord meeting, final String now) {
    return meeting.startTime().compareTo(now) >= 0;
  }

  /** {@code now} in the same naive-local ISO-8601 shape every meeting's startTime is stored in. */
  static String now() {
    return LocalDateTime.now().format(MeetingRecord.DATE_TIME_FORMAT);
  }

  /** True if any meeting from {@code now} onward is booked in this room. See DeleteRoomHandler. */
  static boolean hasUpcomingMeetingInRoom(
      final DayRepository days, final String roomId, final String now) {
    return days.scanDays().stream()
        .flatMap(day -> day.meetings().stream())
        .anyMatch(meeting -> isUpcoming(meeting, now) && meeting.roomId().equals(roomId));
  }

  /**
   * Cancels every upcoming meeting this person organises (deleted, along with its
   * meeting-participants), and removes them as an attendee from every other upcoming one, leaving
   * that meeting intact for its organiser and remaining attendees. Past meetings are left untouched
   * entirely; {@code MeetingResponse} already resolves a since-deleted participant or room to a
   * placeholder rather than breaking, so leaving a dangling id in historical data is safe.
   *
   * <p>Each affected day goes through {@link DayRepository#mutate}, so the filtering re-runs
   * against freshly-read state and the {@code PTR#} pointers of cancelled meetings are removed in
   * the same transaction as the day - the repository diffs the meeting ids, so no caller has to
   * remember to. The meetings are not all one single transaction with each other, since a prolific
   * organiser's meeting count has no fixed upper bound and DynamoDB transactions cap at 100 items -
   * a failure partway through leaves some meetings cleaned up and others not, reconcilable by
   * {@code database-repair}.
   */
  static void cancelUpcomingMeetingsFor(
      final DayRepository days, final String personId, final String now) {
    final List<String> affectedDates =
        days.scanDays().stream()
            .filter(
                day ->
                    day.meetings().stream()
                        .anyMatch(meeting -> isUpcomingAndInvolves(meeting, personId, now)))
            .map(Day::date)
            .toList();

    for (final String date : affectedDates) {
      days.mutate(
          date,
          day ->
              day.withMeetings(
                  day.meetings().stream()
                      .filter(meeting -> !isUpcomingOrganisedBy(meeting, personId, now))
                      .map(
                          meeting ->
                              isUpcoming(meeting, now)
                                  ? withoutAttendee(meeting, personId)
                                  : meeting)
                      .toList()));
    }
  }

  private static boolean isUpcomingOrganisedBy(
      final MeetingRecord meeting, final String personId, final String now) {
    return isUpcoming(meeting, now) && meeting.organiserId().equals(personId);
  }

  private static boolean isUpcomingAndInvolves(
      final MeetingRecord meeting, final String personId, final String now) {
    return isUpcoming(meeting, now)
        && (meeting.organiserId().equals(personId) || meeting.attendeeIds().contains(personId));
  }

  /**
   * Removes {@code personId} from {@code attendeeIds}, and the same index from the parallel {@code
   * attendeeStatuses} - the two lists must stay in step (see {@code MeetingRecord}'s compact
   * constructor), so this is the one place besides {@code RespondToMeetingHandler} that has to
   * think about both together rather than just the ids.
   */
  private static MeetingRecord withoutAttendee(final MeetingRecord meeting, final String personId) {
    final int index = meeting.attendeeIds().indexOf(personId);
    if (index < 0) {
      return meeting;
    }
    final List<String> ids = new ArrayList<>(meeting.attendeeIds());
    final List<AttendeeStatus> statuses = new ArrayList<>(meeting.attendeeStatuses());
    ids.remove(index);
    statuses.remove(index);
    return new MeetingRecord(
        meeting.id(),
        meeting.roomId(),
        meeting.organiserId(),
        ids,
        statuses,
        meeting.subject(),
        meeting.startTime(),
        meeting.endTime());
  }
}
