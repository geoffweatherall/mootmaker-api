package com.mootmaker.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import module java.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingVersionTest {

  private static final MeetingRecord BASE =
      new MeetingRecord(
          "m-1",
          "room-a",
          "org-1",
          List.of("p-1", "p-2"),
          List.of(AttendeeStatus.NoResponse, AttendeeStatus.NoResponse),
          "Standup",
          "2026-09-24T10:00:00",
          "2026-09-24T11:00:00");

  @Test
  @DisplayName("every field an edit can change changes the version")
  void everyEditableFieldChangesIt() {
    final String base = BASE.version();
    assertNotEquals(
        base,
        with(
            "room-b",
            "org-1",
            List.of("p-1", "p-2"),
            "Standup",
            "2026-09-24T10:00:00",
            "2026-09-24T11:00:00"));
    assertNotEquals(
        base,
        with(
            "room-a",
            "org-2",
            List.of("p-1", "p-2"),
            "Standup",
            "2026-09-24T10:00:00",
            "2026-09-24T11:00:00"));
    assertNotEquals(
        base,
        with(
            "room-a",
            "org-1",
            List.of("p-1"),
            "Standup",
            "2026-09-24T10:00:00",
            "2026-09-24T11:00:00"));
    assertNotEquals(
        base,
        with(
            "room-a",
            "org-1",
            List.of("p-1", "p-2"),
            "Retro",
            "2026-09-24T10:00:00",
            "2026-09-24T11:00:00"));
    assertNotEquals(
        base,
        with(
            "room-a",
            "org-1",
            List.of("p-1", "p-2"),
            "Standup",
            "2026-09-24T10:30:00",
            "2026-09-24T11:00:00"));
    assertNotEquals(
        base,
        with(
            "room-a",
            "org-1",
            List.of("p-1", "p-2"),
            "Standup",
            "2026-09-24T10:00:00",
            "2026-09-24T11:30:00"));
  }

  @Test
  @DisplayName("attendee responses and attendee order do not change it")
  void responsesAndOrderDoNot() {
    final MeetingRecord responded =
        new MeetingRecord(
            "m-1",
            "room-a",
            "org-1",
            List.of("p-2", "p-1"),
            List.of(AttendeeStatus.Going, AttendeeStatus.NotGoing),
            "Standup",
            "2026-09-24T10:00:00",
            "2026-09-24T11:00:00");
    assertEquals(BASE.version(), responded.version());
  }

  private static String with(
      final String room,
      final String organiser,
      final List<String> attendees,
      final String subject,
      final String start,
      final String end) {
    return MeetingVersion.of(room, organiser, attendees, subject, start, end);
  }
}
