package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.RoomRepository;
import com.mootmaker.model.Room;
import com.mootmaker.model.RoomError;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/** AppSync direct-Lambda resolver for {@code Mutation.updateRoom}. Admin only - see {@link Identity#requireAdmin}. */
public class UpdateRoomHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public UpdateRoomHandler() {
        this(DynamoDbClientProvider.client(), System.getenv().getOrDefault("ROOMS_TABLE_NAME", "Rooms"));
    }

    UpdateRoomHandler(final DynamoDbClient dynamoDbClient, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAdmin(event);

        final Map<String, Object> arguments = castToMap(event.get("arguments"));
        final String id = (String) arguments.get("id");
        final Map<String, Object> roomInput = castToMap(arguments.get("room"));

        final String name = (String) roomInput.get("name");
        final int capacity = ((Number) roomInput.get("capacity")).intValue();

        // Collects every broken rule rather than stopping at the first, same as CreateRoomHandler -
        // deliberately checks existence even when name/capacity are already invalid, so a caller
        // fixing one problem doesn't get surprised by a second one on the next attempt.
        final List<String> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(RoomError.NameRequired.name());
        }
        if (capacity < 2) {
            errors.add(RoomError.CapacityTooLow.name());
        }
        if (!roomExists(id)) {
            errors.add(RoomError.RoomNotFound.name());
        }

        final Map<String, Object> result = new HashMap<>();
        if (!errors.isEmpty()) {
            result.put("room", null);
            // The whole collection, on success and on failure alike. A normalising client cache updates
        // an entity by id everywhere it is referenced, but returning one room does NOT add it to a
        // cached list - that is a separate cache field - so the list is what makes this mutation
        // self-sufficient. Both collections are small enough that sending them costs nothing.
        result.put("rooms", allRooms());
            result.put("errors", errors);
            return result;
        }

        final Room room = new Room(id, name, capacity);
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(room.toItem())
                .build());

        result.put("room", room.toResponseMap());
        result.put("rooms", allRooms());
        result.put("errors", errors);
        return result;
    }

    private boolean roomExists(final String id) {
        final GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("id", AttributeValue.builder().s(id).build()))
                .build());
        return response.hasItem();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }

    /** Every room after the change - see the schema's note on Room mutation results. */
    private List<Map<String, Object>> allRooms() {
        return new RoomRepository(dynamoDbClient, tableName).listAll().stream().map(Room::toResponseMap).toList();
    }
}
