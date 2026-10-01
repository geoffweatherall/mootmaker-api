package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optimistic concurrency on updateMeeting against the real API (mootmaker-api#96): an edit made
 * from a stale copy is rejected with MeetingChanged rather than silently undoing the change made
 * since, and nothing is written.
 */
class UpdateMeetingVersionAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(UpdateMeetingVersionAcceptanceIT.class);

  private static final String UPDATE =
      "mutation($id: ID!, $meeting: MeetingInput!) { updateMeeting(id: $id, meeting: $meeting) {"
          + " meeting { version subject startTime } errors } }";

  private static GraphQlClient client;

  @BeforeAll
  static void setUp() {
    client = GraphQlClient.fromEnvironment();
  }

  @Test
  void anEditFromAStaleCopyIsRejectedAndChangesNothing() {
    DatabaseReset.reset();
    final String roomId =
        client
            .execute(
                "mutation($room: RoomInput!) { createRoom(room: $room) { room { id } errors } }",
                Map.of("room", Map.of("name", "Version Room", "capacity", 4)))
            .get("createRoom")
            .get("room")
            .get("id")
            .asText();
    final String organiserId =
        client
            .execute(
                "mutation($name: String!) { createPerson(name: $name) { person { id } errors } }",
                Map.of("name", "Version Organiser"))
            .get("createPerson")
            .get("person")
            .get("id")
            .asText();
    final Map<String, Object> original =
        meeting(roomId, organiserId, "Original subject", "10:00:00", "10:30:00");
    final JsonNode created =
        client
            .execute(
                "mutation($meeting: MeetingInput!) { createMeeting(meeting: $meeting) {"
                    + " meeting { id version } errors } }",
                Map.of("meeting", original))
            .get("createMeeting")
            .get("meeting");
    final String meetingId = created.get("id").asText();
    final String versionAsRead = created.get("version").asText();

    LOG.info("Someone else moves the meeting half an hour later");
    final Map<String, Object> moved =
        meeting(roomId, organiserId, "Original subject", "10:30:00", "11:00:00");
    moved.put("expectedVersion", versionAsRead);
    final JsonNode firstEdit = update(meetingId, moved);
    assertThat(firstEdit.get("errors").size(), equalTo(0));
    assertThat(firstEdit.get("meeting").get("version").asText(), not(equalTo(versionAsRead)));

    LOG.info("A second editor saves a subject change from the copy they read before the move");
    final Map<String, Object> stale =
        meeting(roomId, organiserId, "Renamed from a stale copy", "10:00:00", "10:30:00");
    stale.put("expectedVersion", versionAsRead);
    final JsonNode secondEdit = update(meetingId, stale);
    assertThat(secondEdit.get("errors").get(0).asText(), equalTo("MeetingChanged"));
    assertThat(secondEdit.get("meeting").isNull(), equalTo(true));

    LOG.info("The move is still there; the stale edit wrote nothing");
    final JsonNode now =
        client
            .execute(
                "query($id: ID!) { meeting(id: $id) { subject startTime } }",
                Map.of("id", meetingId))
            .get("meeting");
    assertThat(now.get("subject").asText(), equalTo("Original subject"));
    assertThat(now.get("startTime").asText(), equalTo(BookableDates.at("10:30:00")));
  }

  private static JsonNode update(final String id, final Map<String, Object> input) {
    return client.execute(UPDATE, Map.of("id", id, "meeting", input)).get("updateMeeting");
  }

  private static Map<String, Object> meeting(
      final String roomId,
      final String organiserId,
      final String subject,
      final String start,
      final String end) {
    final Map<String, Object> input = new HashMap<>();
    input.put("roomId", roomId);
    input.put("organiserId", organiserId);
    input.put("attendeeIds", List.of());
    input.put("subject", subject);
    input.put("startTime", BookableDates.at(start));
    input.put("endTime", BookableDates.at(end));
    return input;
  }
}
