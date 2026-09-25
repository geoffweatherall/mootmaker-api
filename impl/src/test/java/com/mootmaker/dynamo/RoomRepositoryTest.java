package com.mootmaker.dynamo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.Room;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomRepositoryTest {

  private static final String TABLE = "Rooms";

  @Test
  void createsARoomWithAnEightCharacterId() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final RoomRepository repository = new RoomRepository(fakeClient, TABLE);

    final Room room = repository.create("Conference A", 8, null);

    assertEquals(8, room.id().length(), "IdAllocator produces 8-character ids");
    assertEquals(1, fakeClient.tables.get(TABLE).size());
    assertEquals(room, Room.fromItem(fakeClient.tables.get(TABLE).getFirst()));
  }

  @Test
  @DisplayName("retries with a freshly drawn id when the first one collides")
  void retriesOnAnIdCollision() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.forcedPutItemCollisions = 1;
    final RoomRepository repository = new RoomRepository(fakeClient, TABLE);

    final Room room = repository.create("Conference A", 8, null);

    assertEquals(1, fakeClient.tables.get(TABLE).size(), "only the retried write actually lands");
    assertEquals(room, Room.fromItem(fakeClient.tables.get(TABLE).getFirst()));
  }

  @Test
  @DisplayName("gives up rather than retrying forever on sustained collisions")
  void failsAfterTheRetryBudget() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.forcedPutItemCollisions = Integer.MAX_VALUE;
    final RoomRepository repository = new RoomRepository(fakeClient, TABLE);

    final IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> repository.create("Conference A", 8, null));
    assertTrue(thrown.getMessage().contains("attempts"), thrown.getMessage());
    assertTrue(fakeClient.tables.getOrDefault(TABLE, List.of()).isEmpty());
  }
}
