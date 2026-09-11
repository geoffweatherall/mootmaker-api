package com.mootmaker.handler;

import module java.base;

import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.model.Room;

/**
 * Builds the GraphQL response shape for a meeting, resolving room, organiser and attendees only
 * when the query actually asked for something beyond their ids.
 *
 * <p>Shared rather than duplicated because three handlers return meetings - workspace, meeting(id:)
 * and both creates - and the id-only case is the subtle one. Emitting an object with only an {@code
 * id} is a complete answer, not a stub: GraphQL serialises only what was selected, and {@link
 * SelectionSet} reports "no lookup needed" only when everything selected can be answered from the
 * id. If those two ever disagree, a null reaches a non-null field and GraphQL nulls the person,
 * then the list, then the meeting - silent data loss rather than a degraded response. One
 * implementation means they cannot drift apart per call site.
 */
final class MeetingResponse {

  private MeetingResponse() {}

  static Map<String, Object> of(
      final MeetingRecord record,
      final Map<String, Room> roomsById,
      final Map<String, Person> peopleById,
      final boolean roomsResolved,
      final boolean peopleResolved) {
    final Map<String, Object> map = new HashMap<>();
    map.put("id", record.id());
    map.put("subject", record.subject());
    map.put("startTime", record.startTime());
    map.put("endTime", record.endTime());
    map.put(
        "room", roomsResolved ? resolveRoom(record.roomId(), roomsById) : idOnly(record.roomId()));
    map.put(
        "organiser",
        peopleResolved
            ? resolvePerson(record.organiserId(), peopleById)
            : idOnly(record.organiserId()));
    map.put(
        "attendees",
        record.attendeeIds().stream()
            .map(id -> peopleResolved ? resolvePerson(id, peopleById) : idOnly(id))
            .toList());
    return map;
  }

  private static Map<String, Object> idOnly(final String id) {
    final Map<String, Object> map = new HashMap<>();
    map.put("id", id);
    return map;
  }

  /**
   * A meeting can outlive one of its participants: account deletion deliberately leaves past
   * meetings alone, so a Person row can be gone while a historical meeting still references its id.
   * A placeholder keeps the non-null contract rather than letting a null reach the response.
   */
  private static Map<String, Object> resolvePerson(
      final String personId, final Map<String, Person> peopleById) {
    return peopleById.getOrDefault(personId, new Person(personId, "Deleted user")).toResponseMap();
  }

  /** Rooms cannot be deleted today, but the same reasoning applies if that ever changes. */
  private static Map<String, Object> resolveRoom(
      final String roomId, final Map<String, Room> roomsById) {
    final Room room = roomsById.get(roomId);
    return room == null
        ? new Room(roomId, "Deleted room", 0).toResponseMap()
        : room.toResponseMap();
  }
}
