package com.mootmaker.dynamo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.Person;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

class PersonRepositoryTest {

  private static final String TABLE = "People";

  @Test
  void createsExactlyTheGivenPerson() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);
    final Person person = new Person("person-1", "Ada Lovelace");

    repository.create(person);

    assertEquals(1, fakeClient.tables.get(TABLE).size());
    assertEquals(person, Person.fromItem(fakeClient.tables.get(TABLE).getFirst()));
  }

  @Test
  @DisplayName("fails loudly, without retrying, when the given id already exists")
  void createFailsRatherThanSubstitutingADifferentId() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);
    repository.create(new Person("person-1", "Ada Lovelace"));

    assertThrows(
        ConditionalCheckFailedException.class,
        () -> repository.create(new Person("person-1", "A Different Name")),
        "must not silently overwrite, or a caller relying on the id it already claimed "
            + "(e.g. on a Cognito custom:personId) would have no way to know it changed");
    assertEquals(
        "Ada Lovelace",
        Person.fromItem(fakeClient.tables.get(TABLE).getFirst()).name(),
        "the original must be untouched");
  }

  @Test
  void createWithNewIdAllocatesAnEightCharacterId() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);

    final Person person = repository.createWithNewId("Ada Lovelace");

    assertEquals(8, person.id().length(), "IdAllocator produces 8-character ids");
    assertEquals(1, fakeClient.tables.get(TABLE).size());
    assertEquals(person, Person.fromItem(fakeClient.tables.get(TABLE).getFirst()));
  }

  @Test
  @DisplayName("createWithNewId retries with a freshly drawn id when the first one collides")
  void createWithNewIdRetriesOnAnIdCollision() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.forcedPutItemCollisions = 1;
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);

    final Person person = repository.createWithNewId("Ada Lovelace");

    assertEquals(1, fakeClient.tables.get(TABLE).size(), "only the retried write actually lands");
    assertEquals(person, Person.fromItem(fakeClient.tables.get(TABLE).getFirst()));
  }

  @Test
  @DisplayName("createWithNewId gives up rather than retrying forever on sustained collisions")
  void createWithNewIdFailsAfterTheRetryBudget() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.forcedPutItemCollisions = Integer.MAX_VALUE;
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);

    final IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> repository.createWithNewId("Ada Lovelace"));
    assertTrue(thrown.getMessage().contains("attempts"), thrown.getMessage());
    assertTrue(fakeClient.tables.getOrDefault(TABLE, List.of()).isEmpty());
  }
}
