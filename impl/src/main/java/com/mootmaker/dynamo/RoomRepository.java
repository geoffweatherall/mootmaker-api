package com.mootmaker.dynamo;

import module java.base;

import com.mootmaker.model.Room;
import com.mootmaker.model.RoomColor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Rooms, by id. An instance rather than a static utility so it owns its client and table name,
 * matching {@link DayRepository}; constructed once at initialisation and held as a handler field,
 * so it lands in the SnapStart snapshot.
 */
public final class RoomRepository {

  /** Enough to clear the negligible chance of a real collision; see {@link IdAllocator}. */
  private static final int MAX_CREATE_ATTEMPTS = 5;

  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public RoomRepository(final DynamoDbClient dynamoDbClient, final String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  /**
   * Deduplicates ids and batches over {@code BatchGetItem}, so no room is fetched twice however
   * many meetings reference it. This absorbed the old generic {@code BatchLoader}: only rooms and
   * people are ever batch-loaded, so a helper parameterised by table name added a argument at
   * exactly the call sites where the selection logic is hardest to read.
   */
  public Map<String, Room> loadByIds(final Set<String> ids) {
    return BatchGet.byId(dynamoDbClient, tableName, ids).entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> Room.fromItem(entry.getValue())));
  }

  public Optional<Room> findById(final String id) {
    return Optional.ofNullable(loadByIds(Set.of(id)).get(id));
  }

  public List<Room> listAll() {
    return BatchGet.scan(dynamoDbClient, tableName).stream().map(Room::fromItem).toList();
  }

  public void put(final Room room) {
    BatchGet.put(dynamoDbClient, tableName, room.toItem());
  }

  /**
   * Allocates a fresh id and creates a room with it, unlike {@link #put}, which writes whatever
   * {@code Room} it is given (an update overwriting an existing item, for {@code
   * UpdateRoomHandler}). Retries with a newly drawn id on the negligible chance of a collision -
   * see {@link IdAllocator}.
   */
  public Room create(final String name, final int capacity, final RoomColor color) {
    for (int attempt = 1; attempt <= MAX_CREATE_ATTEMPTS; attempt++) {
      final Room room = new Room(IdAllocator.newId(), name, capacity, color);
      try {
        dynamoDbClient.putItem(
            PutItemRequest.builder()
                .tableName(tableName)
                .item(room.toItem())
                .conditionExpression("attribute_not_exists(id)")
                .build());
        return room;
      } catch (final ConditionalCheckFailedException e) {
        if (attempt == MAX_CREATE_ATTEMPTS) {
          throw new IllegalStateException(
              "Could not allocate a room id after " + MAX_CREATE_ATTEMPTS + " attempts", e);
        }
      }
    }
    throw new IllegalStateException("unreachable");
  }

  public void deleteById(final String id) {
    dynamoDbClient.deleteItem(
        DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of("id", AttributeValue.builder().s(id).build()))
            .build());
  }

  /** Exposed for the resolver's response bound, which must know the collection is within limits. */
  public int count() {
    return BatchGet.scan(dynamoDbClient, tableName).size();
  }

  Map<String, AttributeValue> rawItem(final String id) {
    return BatchGet.byId(dynamoDbClient, tableName, Set.of(id)).get(id);
  }
}
