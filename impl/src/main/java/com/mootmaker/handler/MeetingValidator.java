package com.mootmaker.handler;

import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.dynamo.RoomAvailability;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.limits.Limits;
import com.mootmaker.limits.Subjects;
import com.mootmaker.model.Boundaries;
import com.mootmaker.model.MeetingError;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;

import module java.base;

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
 *   <li>{@link #dayStateErrors} covers what depends on the state of the day - is the room still free,
 *       is the day now full. These must be re-checked <i>inside</i> the read-modify-write, so a
 *       booking that loses a version conflict re-validates against the day as it now is. Two
 *       concurrent bookings reading a day at 319 meetings would otherwise both decide there is room.
 * </ul>
 *
 * <p>The day-state rules are also evaluated once before the write, so a caller learns everything
 * wrong with their request at once rather than a round trip at a time. Reporting and correctness are
 * different jobs: the reporting pass has to run even when other rules have already failed, and the
 * correctness pass has to run on every retry.
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
    record Validated(List<String> errors, String roomId, String organiserId, List<String> attendeeIds,
            String subject, LocalDateTime startTime, LocalDateTime endTime, Room room, Person organiser,
            List<Person> attendees) {

        /** Enough of the request survived to ask the day a question. */
        boolean canInspectDay() {
            return startTime != null && endTime != null && startTime.toLocalDate().equals(endTime.toLocalDate());
        }

        String date() {
            return startTime.toLocalDate().toString();
        }
    }

    /** The rules that depend on what the day already holds. */
    static List<String> dayStateErrors(final List<MeetingRecord> meetingsThatDay, final Validated validated) {
        final List<String> errors = new ArrayList<>();
        if (meetingsThatDay.size() >= Limits.MAX_MEETINGS_PER_DAY) {
            errors.add(MeetingError.DayIsFull.name());
        }
        if (validated.roomId() != null && !validated.roomId().isBlank()
                && !RoomAvailability.isFree(meetingsThatDay, validated.roomId(), validated.startTime(), validated.endTime())) {
            errors.add(MeetingError.TimeRangeUnavailable.name());
        }
        return errors;
    }

    /** Everything checkable without reading the day. Collects every broken rule rather than stopping at the first. */
    Validated validateRequest(final Map<String, Object> meetingInput, final Boundaries boundaries) {
        final String roomId = (String) meetingInput.get("roomId");
        final String organiserId = (String) meetingInput.get("organiserId");
        @SuppressWarnings("unchecked")
        final List<String> attendeeIds = (List<String>) meetingInput.get("attendeeIds");
        final String subject = Subjects.normalise((String) meetingInput.get("subject"));

        final List<String> errors = new ArrayList<>();

        if (isBlank(subject)) {
            errors.add(MeetingError.SubjectRequired.name());
        } else if (!Subjects.isWithinLimit(subject)) {
            errors.add(MeetingError.SubjectTooLong.name());
        }

        final LocalDateTime startTime =
                parseOnFifteenMinuteBoundary((String) meetingInput.get("startTime"), MeetingError.StartMisaligned, errors);
        final LocalDateTime endTime =
                parseOnFifteenMinuteBoundary((String) meetingInput.get("endTime"), MeetingError.EndMisaligned, errors);

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
            // A Set, not "1 + attendeeIds.size()", so a duplicated organiserId (already separately rejected
            // above via OrganiserIsAttendee) can't also inflate this count and raise a spurious, misleading
            // InsufficientCapacity alongside it.
            final Set<String> distinctParticipantIds = new HashSet<>(safeAttendeeIds);
            if (!isBlank(organiserId)) {
                distinctParticipantIds.add(organiserId);
            }
            if (room.capacity() < distinctParticipantIds.size()) {
                errors.add(MeetingError.InsufficientCapacity.name());
            }
        }

        return new Validated(errors, roomId, organiserId, safeAttendeeIds, subject, startTime, endTime,
                room, organiser, attendees);
    }

    private static LocalDateTime parseOnFifteenMinuteBoundary(final String text, final MeetingError error, final List<String> errors) {
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
