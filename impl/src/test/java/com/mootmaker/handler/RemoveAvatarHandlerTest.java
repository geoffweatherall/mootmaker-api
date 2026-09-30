package com.mootmaker.handler;

import static com.mootmaker.handler.AvatarHandlerFixtures.AVATAR_URLS;
import static com.mootmaker.handler.AvatarHandlerFixtures.TABLE;
import static com.mootmaker.handler.AvatarHandlerFixtures.asAdmin;
import static com.mootmaker.handler.AvatarHandlerFixtures.asPerson;
import static com.mootmaker.handler.AvatarHandlerFixtures.fullyPopulated;
import static com.mootmaker.handler.AvatarHandlerFixtures.map;
import static com.mootmaker.handler.AvatarHandlerFixtures.seed;
import static com.mootmaker.handler.AvatarHandlerFixtures.store;
import static com.mootmaker.handler.AvatarHandlerFixtures.stored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.FakeS3Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RemoveAvatarHandlerTest {

  private final FakeDynamoDbClient dynamo = new FakeDynamoDbClient();
  private final FakeS3Client s3 = new FakeS3Client();
  private final RemoveAvatarHandler handler =
      new RemoveAvatarHandler(dynamo, TABLE, store(s3), AVATAR_URLS);

  private static Map<String, Object> arguments(final String personId) {
    return Map.of("personId", personId);
  }

  private Map<String, Object> invoke(final Map<String, Object> event) {
    return map(handler.handleRequest(event, null));
  }

  private void serve(final String personId, final String hash) {
    s3.stage("avatars/v1/" + personId + "/" + hash + ".jpg", new byte[] {1}, "image/jpeg");
  }

  @Test
  void clearsTheAvatarAndDeletesTheImage() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/abc"));
    serve("person-1", "abc");

    final Map<String, Object> result = invoke(asAdmin(arguments("person-1")));

    assertEquals(List.of(), result.get("errors"));
    assertNull(map(result.get("person")).get("avatarUrl"));
    assertNull(stored(dynamo, "person-1").avatarUrl());
    assertTrue(s3.objects.isEmpty());
  }

  @Test
  @DisplayName("the attribute is removed outright, not stored as an empty value")
  void removesTheAttributeRatherThanBlankingIt() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/abc"));

    invoke(asAdmin(arguments("person-1")));

    assertFalse(dynamo.tables.get(TABLE).getFirst().containsKey("avatarUrl"));
  }

  @Test
  void leavesEveryOtherFieldOfThePersonUntouched() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/abc"));

    invoke(asAdmin(arguments("person-1")));

    assertEquals(fullyPopulated("person-1", null), stored(dynamo, "person-1"));
  }

  @Test
  @DisplayName("removing an avatar from someone who has none succeeds")
  void succeedsForAPersonWithNoAvatar() {
    seed(dynamo, fullyPopulated("person-1", null));

    final Map<String, Object> result = invoke(asAdmin(arguments("person-1")));

    assertEquals(List.of(), result.get("errors"));
    assertEquals("person-1", map(result.get("person")).get("id"));
  }

  @Test
  @DisplayName("also sweeps objects the record did not name")
  void sweepsOrphansToo() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/abc"));
    serve("person-1", "abc");
    serve("person-1", "orphan");

    invoke(asAdmin(arguments("person-1")));

    assertTrue(s3.objects.isEmpty());
  }

  @Test
  @DisplayName("another person's identical image is untouched")
  void leavesOtherPeoplesAvatarsAlone() {
    seed(
        dynamo,
        fullyPopulated("person-1", "v1/person-1/samehash"),
        fullyPopulated("person-2", "v1/person-2/samehash"));
    serve("person-1", "samehash");
    serve("person-2", "samehash");

    invoke(asAdmin(arguments("person-1")));

    assertEquals(Set.of("avatars/v1/person-2/samehash.jpg"), s3.objects.keySet());
    assertEquals("v1/person-2/samehash", stored(dynamo, "person-2").avatarUrl());
  }

  @Test
  @DisplayName("an unknown person is a typed error, and no partial record is created")
  void rejectsAnUnknownPerson() {
    final Map<String, Object> result = invoke(asAdmin(arguments("ghost")));

    assertNull(result.get("person"));
    assertEquals(List.of("PersonNotFound"), result.get("errors"));
    assertTrue(dynamo.tables.getOrDefault(TABLE, List.of()).isEmpty());
  }

  @Test
  void aPersonMayRemoveTheirOwnAvatar() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/abc"));

    final Map<String, Object> result = invoke(asPerson("person-1", arguments("person-1")));

    assertEquals(List.of(), result.get("errors"));
  }

  @Test
  void aNonAdminMayNotRemoveSomeoneElsesAvatar() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/abc"));
    serve("person-1", "abc");

    assertThrows(
        IllegalStateException.class, () -> invoke(asPerson("person-2", arguments("person-1"))));
    assertEquals("v1/person-1/abc", stored(dynamo, "person-1").avatarUrl());
    assertEquals(1, s3.objects.size());
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    final Map<String, Object> event = asAdmin(arguments("person-1"));
    event.remove("identity");

    assertThrows(IllegalStateException.class, () -> invoke(event));
  }
}
