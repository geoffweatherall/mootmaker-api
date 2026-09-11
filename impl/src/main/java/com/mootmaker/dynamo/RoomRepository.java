package com.mootmaker.dynamo;

import module java.base;

import com.mootmaker.model.Room;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Rooms, by id. An instance rather than a static utility so it owns its client and table name,
 * matching {@link DayRepository}; constructed once at initialisation and held as a handler field,
 * so it lands in the SnapStart snapshot.
 */
public final class RoomRepository {

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

  /** Exposed for the resolver's response bound, which must know the collection is within limits. */
  public int count() {
    return BatchGet.scan(dynamoDbClient, tableName).size();
  }

  Map<String, AttributeValue> rawItem(final String id) {
    return BatchGet.byId(dynamoDbClient, tableName, Set.of(id)).get(id);
  }
}
