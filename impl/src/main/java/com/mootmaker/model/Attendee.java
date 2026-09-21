package com.mootmaker.model;

import module java.base;

/**
 * An attendee with their {@link Person} fully resolved, paired with their response status, for
 * building a GraphQL response. See {@link MeetingRecord}'s {@code attendeeIds}/{@code
 * attendeeStatuses} for the persisted, parallel-list shape this is built from.
 *
 * <p>The organiser never appears as an {@code Attendee} - {@code Meeting.organiser} stays a plain
 * {@link Person}, unchanged. Per designs/attendee-response-status.md, the organiser's status is
 * implicitly {@link AttendeeStatus#Going} and is never stored or exposed over GraphQL; there is no
 * status control for it either. Only non-organiser attendees carry a real, storable status.
 */
public record Attendee(Person person, AttendeeStatus status) {

  public Map<String, Object> toResponseMap() {
    final Map<String, Object> map = new HashMap<>();
    map.put("person", person.toResponseMap());
    map.put("status", status.name());
    return map;
  }
}
