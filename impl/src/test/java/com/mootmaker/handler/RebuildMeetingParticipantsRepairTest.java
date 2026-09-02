package com.mootmaker.handler;

import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RebuildMeetingParticipantsRepairTest {

    private static final String MEETINGS_TABLE = "Meetings";
    private static final String PARTICIPANTS_TABLE = "MeetingParticipants";

    private static MeetingRecord meeting(final String id, final String organiserId, final List<String> attendeeIds) {
        return new MeetingRecord(id, "room-1", organiserId, attendeeIds, "Subject", "2026-07-01T09:00:00", "2026-07-01T10:00:00");
    }

    @Test
    void createsParticipantRowsMissingForAnExistingMeeting() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(MEETINGS_TABLE, new ArrayList<>(List.of(
                meeting("b1", "organiser-1", List.of("attendee-1")).toItem())));

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(2, result.created());
        assertEquals(0, result.removed());
        assertEquals(0, result.alreadyCorrect());
        final Set<String> personIds = dynamoDbClient.tables.get(PARTICIPANTS_TABLE).stream()
                .map(item -> item.get("personId").s())
                .collect(Collectors.toSet());
        assertEquals(Set.of("organiser-1", "attendee-1"), personIds);
    }

    @Test
    void skipsParticipantRowsThatAlreadyExist() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final MeetingRecord existingMeeting = meeting("b1", "organiser-1", List.of());
        dynamoDbClient.tables.put(MEETINGS_TABLE, new ArrayList<>(List.of(existingMeeting.toItem())));
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(
                MeetingParticipant.allFor(existingMeeting).stream().map(MeetingParticipant::toItem).toList()));

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        assertEquals(1, result.alreadyCorrect());
        assertEquals(1, dynamoDbClient.tables.get(PARTICIPANTS_TABLE).size());
    }

    @Test
    void removesAParticipantRowWhoseMeetingNoLongerExists() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        final MeetingParticipant orphan = new MeetingParticipant("person-1", "deleted-meeting", "2026-07-01T09:00:00", "2026-07-01T10:00:00");
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(List.of(orphan.toItem())));

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(0, result.created());
        assertEquals(1, result.removed());
        assertTrue(dynamoDbClient.tables.get(PARTICIPANTS_TABLE).isEmpty());
    }

    @Test
    void dryRunReportsWithoutWritingOrDeletingAnything() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(MEETINGS_TABLE, new ArrayList<>(List.of(meeting("b1", "organiser-1", List.of()).toItem())));
        final MeetingParticipant orphan = new MeetingParticipant("person-1", "deleted-meeting", "2026-07-01T09:00:00", "2026-07-01T10:00:00");
        dynamoDbClient.tables.put(PARTICIPANTS_TABLE, new ArrayList<>(List.of(orphan.toItem())));

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE, true);

        assertEquals(1, result.created());
        assertEquals(1, result.removed());
        assertEquals(1, dynamoDbClient.tables.get(PARTICIPANTS_TABLE).size(), "dry run must not change the table");
        assertEquals("deleted-meeting", dynamoDbClient.tables.get(PARTICIPANTS_TABLE).getFirst().get("meetingId").s());
    }

    @Test
    void succeedsWhenBothTablesAreEmpty() {
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, MEETINGS_TABLE, PARTICIPANTS_TABLE, false);

        assertEquals(0, result.created());
        assertEquals(0, result.removed());
        assertEquals(0, result.alreadyCorrect());
    }
}
