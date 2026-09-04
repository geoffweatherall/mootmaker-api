package com.mootmaker.handler;

import com.mootmaker.concurrent.ConcurrencyUtils;
import com.mootmaker.model.MeetingParticipant;
import com.mootmaker.model.MeetingRecord;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import module java.base;

/**
 * Repair #2: meeting-participants is a derived index of the meetings table (see
 * {@link MeetingParticipant}) - one row per (meeting, organiser-or-attendee) pair, letting
 * {@code Query.meetings}' {@code personId} filter find a person's meetings without scanning
 * {@code attendeeIds} (a list, so it can't be a GSI key). {@link com.mootmaker.handler.CreateMeetingHandler}
 * writes a meeting and its participant rows atomically, so under normal operation the two never
 * drift - but this table has no rows at all for any meeting created before it existed, and a manual
 * data fix or a restored table could leave it out of sync. This repair recomputes the exact expected
 * participant rows from the meetings table (the source of truth) and reconciles: creates rows that
 * are missing, and removes rows that don't belong (either their meeting no longer exists, or the
 * (person, meeting) pair isn't actually one of that meeting's participants). Only ever touches
 * meeting-participants, never the meetings table itself, so - like {@link CreateMissingPersonsRepair}
 * - this is safe to run against a real/production environment.
 */
final class RebuildMeetingParticipantsRepair {

    private RebuildMeetingParticipantsRepair() {
    }

    record Result(int created, int removed, int alreadyCorrect) {
    }

    static Result run(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String meetingParticipantsTableName, final boolean dryRun) {
        final List<MeetingRecord> meetings = scan(dynamoDbClient, meetingsTableName, MeetingRecord::fromItem);
        System.out.println("Found " + meetings.size() + " meeting(s).");

        final Map<String, MeetingParticipant> expected = new LinkedHashMap<>();
        for (final MeetingRecord meeting : meetings) {
            for (final MeetingParticipant participant : MeetingParticipant.allFor(meeting)) {
                expected.put(key(participant), participant);
            }
        }

        final Map<String, MeetingParticipant> existing = new LinkedHashMap<>();
        for (final MeetingParticipant participant : scan(dynamoDbClient, meetingParticipantsTableName, MeetingParticipant::fromItem)) {
            existing.put(key(participant), participant);
        }

        final List<MeetingParticipant> toCreate = new ArrayList<>();
        int alreadyCorrect = 0;
        for (final Map.Entry<String, MeetingParticipant> entry : expected.entrySet()) {
            if (existing.containsKey(entry.getKey())) {
                alreadyCorrect++;
            } else {
                toCreate.add(entry.getValue());
            }
        }

        final List<MeetingParticipant> toRemove = existing.entrySet().stream()
                .filter(entry -> !expected.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();

        // Every row created or removed here is independent of every other one, so both passes run
        // on the shared bounded thread pool rather than one row at a time - the DynamoDB SDK client
        // is safe to share across threads.
        ConcurrencyUtils.runInParallel(toCreate, participant -> {
            System.out.println("  creating participant row: person " + participant.personId() + ", meeting "
                    + participant.meetingId() + (dryRun ? " (dry run)" : ""));
            if (!dryRun) {
                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(meetingParticipantsTableName)
                        .item(participant.toItem())
                        .build());
            }
        });

        ConcurrencyUtils.runInParallel(toRemove, participant -> {
            System.out.println("  removing stale participant row: person " + participant.personId() + ", meeting "
                    + participant.meetingId() + (dryRun ? " (dry run)" : ""));
            if (!dryRun) {
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(meetingParticipantsTableName)
                        .key(Map.of(
                                "personId", AttributeValue.builder().s(participant.personId()).build(),
                                "sortKey", AttributeValue.builder().s(participant.sortKey()).build()))
                        .build());
            }
        });

        return new Result(toCreate.size(), toRemove.size(), alreadyCorrect);
    }

    private static String key(final MeetingParticipant participant) {
        return participant.personId() + "|" + participant.meetingId();
    }

    private static <T> List<T> scan(final DynamoDbClient dynamoDbClient, final String tableName,
            final Function<Map<String, AttributeValue>, T> fromItem) {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).consistentRead(true).build())
                .items().stream().map(fromItem).toList();
    }
}
