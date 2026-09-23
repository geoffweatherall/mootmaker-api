package com.mootmaker.handler;

import module java.base;

import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomAvailability;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.limits.Subjects;
import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.Boundaries;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;

/**
 * Every rule a proposed meeting must satisfy, in one place, because two handlers apply them -
 * {@code createMeeting} and {@code createMeetings} - and a bulk create that validated differently
 * from a single create would be a trap rather than a convenience.
 *
 * <p>The rules split in two, and <b>where each half runs is the design</b>:
 *
 * <ul>
 *   <li>{@link #validateRequest} covers everything answerable from the request alone - times,
 *       existence, capacity, lengths, the bookable window. Checked once, up front.
 *   <li>{@link #dayStateErrors} covers what depends on the state of the day - is the room still
 *       free, is the day now full. These must be re-checked <i>inside</i> the read-modify-write, so
 *       a booking that loses a version conflict re-validates against the day as it now is. Two
 *       concurrent bookings reading a day at 319 meetings would otherwise both decide there is
 *       room.
 * </ul>
 *
 * <p>The day-state rules are also evaluated once before the write, so a caller learns everything
 * wrong with their request at once rather than a round trip at a time. Reporting and correctness
 * are different jobs: the reporting pass has to run even when other rules have already failed, and
 * the correctness pass has to run on every retry.
 */
final class MeetingValidator {

  private final RoomRepository rooms;
  private final PersonRepository people;

  MeetingValidator(final RoomRepository rooms, final PersonRepository people) {
    this.rooms = rooms;
    this.people = people;
  }

  /**
   * The request after checking, valid or not. Carries the errors rather than throwing them so the
   * day-state pass can add its own before anything is reported.
   */
  record Validated(
      List<String> errors,
      String roomId,
      String organiserId,
      List<String> attendeeIds,
      List<AttendeeStatus> attendeeStatuses,
      String subject,
      LocalDateTime startTime,
      LocalDateTime endTime,
      Room room,
      Person organiser,
      List<Person> attendees) {

    /** Enough of the request survived to ask the day a question. */
    boolean canInspectDay() {
      return startTime != null
          && endTime != null
          && startTime.toLocalDate().equals(endTime.toLocalDate());
    }

    String date() {
      return startTime.toLocalDate().toString();
    }
  }

  /**
   * The rules that depend on what the day already holds.
   *
   * <p>{@code excludingMeetingId} is {@code null} for {@code createMeeting}/{@code createMeetings},
   * where there is no self to exclude. {@code updateMeeting} passes the id of the meeting being
   * edited, so neither its own current room-slot nor its own place in the day's count is held
   * against it - editing a meeting's time within its own room (even to a range that overlaps its
   * own prior slot) must not spuriously fail as a self-conflict, and editing on a day already at
   * {@link Limits#MAX_MEETINGS_PER_DAY} must not spuriously fail as full, since an edit never
   * changes how many meetings the day holds.
   */
  static List<String> dayStateErrors(
      final List<MeetingRecord> meetingsThatDay,
      final Validated validated,
      final String excludingMeetingId) {
    final List<String> errors = new ArrayList<>();
    final long countTowardDayCap =
        excludingMeetingId == null
            ? meetingsThatDay.size()
            : meetingsThatDay.stream().filter(m -> !m.id().equals(excludingMeetingId)).count();
    if (countTowardDayCap >= Limits.MAX_MEETINGS_PER_DAY) {
      errors.add(MeetingError.DayIsFull.name());
    }
    // isFreeIgnoring(..., null) behaves exactly like isFree - a null id never matches any
    // meeting's own id, so nothing is excluded on create.
    if (validated.roomId() != null
        && !validated.roomId().isBlank()
        && !RoomAvailability.isFreeIgnoring(
            meetingsThatDay,
            validated.roomId(),
            validated.startTime(),
            validated.endTime(),
            excludingMeetingId)) {
      errors.add(MeetingError.TimeRangeUnavailable.name());
    }
    return errors;
  }

  /**
   * Everything checkable without reading the day. Collects every broken rule rather than stopping
   * at the first.
   */
  Validated validateRequest(final Map<String, Object> meetingInput, final Boundaries boundaries) {
    final String roomId = (String) meetingInput.get("roomId");
    final String organiserId = (String) meetingInput.get("organiserId");
    @SuppressWarnings("unchecked")
    final List<String> attendeeIds = (List<String>) meetingInput.get("attendeeIds");
    @SuppressWarnings("unchecked")
    final List<String> attendeeStatusCodes = (List<String>) meetingInput.get("attendeeStatuses");
    final String subject = Subjects.normalise((String) meetingInput.get("subject"));

    final List<String> errors = new ArrayList<>();

    if (isBlank(subject)) {
      errors.add(MeetingError.SubjectRequired.name());
    } else if (!Subjects.isWithinLimit(subject)) {
      errors.add(MeetingError.SubjectTooLong.name());
    }

    final LocalDateTime startTime =
        parseOnFifteenMinuteBoundary(
            (String) meetingInput.get("startTime"), MeetingError.StartMisaligned, errors);
    final LocalDateTime endTime =
        parseOnFifteenMinuteBoundary(
            (String) meetingInput.get("endTime"), MeetingError.EndMisaligned, errors);

    if (startTime != null && endTime != null) {
      if (!startTime.toLocalDate().equals(endTime.toLocalDate())) {
        errors.add(MeetingError.SpansMultipleDays.name());
      } else if (!endTime.isAfter(startTime)) {
        errors.add(MeetingError.EndBeforeStart.name());
      } else if (!boundaries.contains(startTime.toLocalDate().toString())) {
        errors.add(MeetingError.OutsideBookableRange.name());
      }
    }

    if (attendeeIds != null && attendeeIds.size() > Limits.MAX_ATTENDEES_PER_MEETING) {
      errors.add(MeetingError.TooManyAttendees.name());
    }

    Room room = null;
    if (isBlank(roomId)) {
      errors.add(MeetingError.RoomRequired.name());
    } else {
      room = rooms.findById(roomId).orElse(null);
      if (room == null) {
        errors.add(MeetingError.RoomNotFound.name());
      }
    }

    Person organiser = null;
    if (isBlank(organiserId)) {
      errors.add(MeetingError.OrganiserRequired.name());
    } else {
      organiser = people.findById(organiserId).orElse(null);
      if (organiser == null) {
        errors.add(MeetingError.OrganiserNotFound.name());
      }
    }

    final List<String> safeAttendeeIds = attendeeIds == null ? List.of() : attendeeIds;
    // Every attendee starts NoResponse unless the caller explicitly supplied a same-length
    // attendeeStatuses list - see designs/attendee-response-status.md's "Technical considerations".
    // There is no webapp UI for this (Add Meeting never sends it); it exists so mootmaker-demo-data
    // can seed a realistic status mix through the ordinary GraphQL API rather than needing
    // DynamoDB access or a self-only respondToMeeting call it has no identity to make (most
    // generated attendees are guests with no Cognito account at all to respond as).
    final List<AttendeeStatus> safeAttendeeStatuses =
        attendeeStatusCodes != null && attendeeStatusCodes.size() == safeAttendeeIds.size()
            ? attendeeStatusCodes.stream().map(AttendeeStatus::valueOf).toList()
            : safeAttendeeIds.stream().map(id -> AttendeeStatus.NoResponse).toList();
    final Map<String, Person> attendeesById = people.loadByIds(Set.copyOf(safeAttendeeIds));
    final List<Person> attendees = new ArrayList<>();
    for (final String attendeeId : safeAttendeeIds) {
      final Person attendee = attendeesById.get(attendeeId);
      if (attendee == null) {
        errors.add(MeetingError.AttendeeNotFound.name());
      } else {
        attendees.add(attendee);
      }
    }

    if (!isBlank(organiserId) && safeAttendeeIds.contains(organiserId)) {
      errors.add(MeetingError.OrganiserIsAttendee.name());
    }

    if (room != null) {
      // A Set, not "1 + attendeeIds.size()", so a duplicated organiserId (already separately
      // rejected
      // above via OrganiserIsAttendee) can't also inflate this count and raise a spurious,
      // misleading
      // InsufficientCapacity alongside it.
      final Set<String> distinctParticipantIds = new HashSet<>(safeAttendeeIds);
      if (!isBlank(organiserId)) {
        distinctParticipantIds.add(organiserId);
      }
      if (room.capacity() < distinctParticipantIds.size()) {
        errors.add(MeetingError.InsufficientCapacity.name());
      }
    }

    return new Validated(
        errors,
        roomId,
        organiserId,
        safeAttendeeIds,
        safeAttendeeStatuses,
        subject,
        startTime,
        endTime,
        room,
        organiser,
        attendees);
  }

  private static LocalDateTime parseOnFifteenMinuteBoundary(
      final String text, final MeetingError error, final List<String> errors) {
    if (text == null) {
      errors.add(error.name());
      return null;
    }
    final LocalDateTime dateTime;
    try {
      dateTime = LocalDateTime.parse(text);
    } catch (final DateTimeParseException _) {
      errors.add(error.name());
      return null;
    }
    if (dateTime.getSecond() != 0 || dateTime.getNano() != 0 || dateTime.getMinute() % 15 != 0) {
      errors.add(error.name());
      return null;
    }
    return dateTime;
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }
}
