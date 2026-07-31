package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.RoomAvailability;
import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import module java.base;

/**
 * AppSync direct-Lambda resolver for {@code Query.suggestRoom}. Returns every room with
 * sufficient, free capacity - not just the best one - ranked smallest surplus capacity first (ties
 * broken by name) so the webapp can cache the whole ranked list after one call and step through it
 * on repeat button presses instead of re-querying each time. Best-effort: unlike
 * CreateMeetingHandler, malformed input just yields an empty list rather than a structured error
 * list, since this only feeds a "suggest a room" button, not the authoritative validation that
 * createMeeting still performs when the meeting is actually saved.
 */
public class SuggestRoomHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String roomsTableName;
    private final String meetingsTableName;

    public SuggestRoomHandler() {
        this(DynamoDbClientProvider.client(),
                System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"),
                System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings"));
    }

    SuggestRoomHandler(final DynamoDbClient dynamoDbClient, final String roomsTableName, final String meetingsTableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.roomsTableName = roomsTableName;
        this.meetingsTableName = meetingsTableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final LocalDateTime startTime = parseDateTime((String) arguments.get("startTime"));
        final LocalDateTime endTime = parseDateTime((String) arguments.get("endTime"));
        final Integer requiredCapacity = (Integer) arguments.get("requiredCapacity");

        if (startTime == null || endTime == null || !startTime.isBefore(endTime)
                || requiredCapacity == null || requiredCapacity < 1) {
            return List.of();
        }

        final ScanResponse response = dynamoDbClient.scan(ScanRequest.builder().tableName(roomsTableName).build());
        final List<Room> candidates = response.items().stream()
                .map(Room::fromItem)
                .filter(room -> room.capacity() >= requiredCapacity)
                .sorted(Comparator.comparingInt(Room::capacity).thenComparing(Room::name))
                .toList();

        return candidates.stream()
                .filter(candidate -> !RoomAvailability.hasOverlappingMeeting(dynamoDbClient, meetingsTableName, candidate.id(), startTime, endTime))
                .map(Room::toResponseMap)
                .toList();
    }

    private static LocalDateTime parseDateTime(final String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (final DateTimeParseException _) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
