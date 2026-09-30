package com.mootmaker.dynamo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.DateFormat;
import com.mootmaker.model.Person;
import com.mootmaker.model.TimeFormat;
import com.mootmaker.model.WeekStart;
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

  /**
   * Creation never sets an avatar. It used to, via a second argument that mootmaker-demo-data
   * passed a bundled filename to - which meant an unvalidated, caller-supplied path reached
   * storage. Avatars now arrive only through the upload mutations, so every one has been decoded
   * and re-encoded by this API before any client sees it.
   */
  @Test
  void createWithNewIdNeverSetsAnAvatar() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);

    final Person person = repository.createWithNewId("Ada Lovelace");

    assertNull(person.avatarUrl());
    assertNull(Person.fromItem(fakeClient.tables.get(TABLE).getFirst()).avatarUrl());
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

  // --- Attribute-level updates ---------------------------------------------------------
  //
  // These replaced read-modify-PutItem, where each caller had to rebuild the whole record and
  // carry every field it did not own forward by hand. The handler-level tests still assert that
  // nothing is lost; what follows asserts it at the level where it is now structurally true, so
  // the guarantee is pinned to the repository rather than to four handlers all remembering.

  /** A person with every field populated, so an update that clobbers anything is visible. */
  private static Person fullyPopulated() {
    return new Person(
        "person-1",
        "Ada Lovelace",
        List.of("sub-1", "sub-2"),
        List.of("ada@example.com"),
        true,
        DateFormat.British,
        TimeFormat.AmPm,
        WeekStart.Sunday,
        "v1/person-1/abc123");
  }

  @Test
  void updateNameChangesOnlyTheName() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);
    repository.create(fullyPopulated());

    final Person updated = repository.updateName("person-1", "Ada King");

    final Person expected =
        new Person(
            "person-1",
            "Ada King",
            List.of("sub-1", "sub-2"),
            List.of("ada@example.com"),
            true,
            DateFormat.British,
            TimeFormat.AmPm,
            WeekStart.Sunday,
            "v1/person-1/abc123");
    assertEquals(expected, updated);
    assertEquals(updated, Person.fromItem(fakeClient.tables.get(TABLE).getFirst()));
  }

  @Test
  void updateIsAdminChangesOnlyTheAdminFlag() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);
    repository.create(fullyPopulated());

    final Person updated = repository.updateIsAdmin("person-1", false);

    assertFalse(updated.isAdmin());
    assertEquals("Ada Lovelace", updated.name());
    assertEquals(List.of("sub-1", "sub-2"), updated.cognitoSubs());
    assertEquals(List.of("ada@example.com"), updated.cognitoEmails());
    assertEquals("v1/person-1/abc123", updated.avatarUrl());
    assertEquals(DateFormat.British, updated.dateFormat());
  }

  @Test
  void updatePreferencesChangesOnlyTheThreePreferences() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);
    repository.create(fullyPopulated());

    final Person updated =
        repository.updatePreferences(
            "person-1", DateFormat.Iso, TimeFormat.TwentyFourHour, WeekStart.Monday);

    assertEquals(DateFormat.Iso, updated.dateFormat());
    assertEquals(TimeFormat.TwentyFourHour, updated.timeFormat());
    assertEquals(WeekStart.Monday, updated.weekStart());
    assertEquals("Ada Lovelace", updated.name());
    assertTrue(updated.isAdmin());
    assertEquals("v1/person-1/abc123", updated.avatarUrl());
    assertEquals(List.of("sub-1", "sub-2"), updated.cognitoSubs());
  }

  @Test
  @DisplayName("an update returns what was actually stored, not what the caller assembled")
  void updateReturnsTheStoredItem() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);
    repository.create(fullyPopulated());

    final Person returned = repository.updateName("person-1", "Ada King");

    assertEquals(Person.fromItem(fakeClient.tables.get(TABLE).getFirst()), returned);
  }

  /**
   * The trap PutItem never had. A real {@code UpdateItem} on a key that does not exist CREATES the
   * item from whatever the expression sets - here, a Person carrying a name and nothing else, no id
   * having been written by any caller. {@code attribute_exists(id)} is what turns that into a
   * failure, and this asserts both halves: it throws, and the table is still empty afterwards.
   */
  @Test
  @DisplayName("updating a person who does not exist fails, and creates no partial record")
  void updateOnAMissingPersonFailsAndWritesNothing() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    final PersonRepository repository = new PersonRepository(fakeClient, TABLE);

    assertThrows(
        ConditionalCheckFailedException.class, () -> repository.updateName("ghost", "Nobody"));
    assertTrue(fakeClient.tables.getOrDefault(TABLE, List.of()).isEmpty());
  }
}
