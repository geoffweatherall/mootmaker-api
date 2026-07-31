package com.mootmaker.dynamo;

import com.mootmaker.model.MeetingRecord;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import module java.base;

/**
 * Checks whether a room has a meeting overlapping a given time range, via the meetings table's
 * roomId-startTime-index GSI. Shared by CreateMeetingHandler (does this room, at this time, work?)
 * and SuggestRoomHandler (probing candidate rooms one at a time for the same question).
 */
public final class RoomAvailability {

    private static final String ROOM_START_TIME_INDEX = "roomId-startTime-index";

    private RoomAvailability() {
    }

    /**
     * begins_with is exact rather than approximate because every meeting is confined to a single
     * calendar day (a meeting cannot span midnight), so two meetings for the same room can only
     * possibly overlap if they share a date - then the (small) result set is checked for an actual
     * time overlap. Replaces a full table scan.
     */
    public static boolean hasOverlappingMeeting(final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String roomId, final LocalDateTime startTime, final LocalDateTime endTime) {
        final String datePrefix = startTime.toLocalDate().toString();
        final QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(meetingsTableName)
                .indexName(ROOM_START_TIME_INDEX)
                .keyConditionExpression("roomId = :roomId AND begins_with(startTime, :datePrefix)")
                .expressionAttributeValues(Map.of(
                        ":roomId", AttributeValue.builder().s(roomId).build(),
                        ":datePrefix", AttributeValue.builder().s(datePrefix).build()))
                .build());
        return response.items().stream()
                .map(MeetingRecord::fromItem)
                .anyMatch(existing -> {
                    final LocalDateTime existingStart = LocalDateTime.parse(existing.startTime());
                    final LocalDateTime existingEnd = LocalDateTime.parse(existing.endTime());
                    return startTime.isBefore(existingEnd) && endTime.isAfter(existingStart);
                });
    }
}
