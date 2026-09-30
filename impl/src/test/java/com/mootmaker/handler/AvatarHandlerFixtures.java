package com.mootmaker.handler;

import module java.base;

import com.mootmaker.avatar.AvatarStore;
import com.mootmaker.avatar.AvatarUrls;
import com.mootmaker.model.DateFormat;
import com.mootmaker.model.Person;
import com.mootmaker.model.TimeFormat;
import com.mootmaker.model.WeekStart;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.FakeS3Client;
import com.mootmaker.testsupport.TestPresigner;

/** Shared scaffolding for the three avatar handlers' tests. */
final class AvatarHandlerFixtures {

  static final String TABLE = "People";
  static final String BASE_URL = "https://avatars.example.test";
  static final AvatarUrls AVATAR_URLS = new AvatarUrls(BASE_URL);

  private AvatarHandlerFixtures() {}

  static AvatarStore store(final FakeS3Client s3) {
    return new AvatarStore(s3, TestPresigner.create(), "test-avatars");
  }

  /** A person with every field populated, so a write that clobbers anything is visible. */
  static Person fullyPopulated(final String id, final String avatarUrl) {
    return new Person(
        id,
        "Ada Lovelace",
        List.of("sub-1"),
        List.of("ada@example.com"),
        true,
        DateFormat.British,
        TimeFormat.AmPm,
        WeekStart.Sunday,
        avatarUrl);
  }

  static void seed(final FakeDynamoDbClient dynamo, final Person... people) {
    final List<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> items =
        dynamo.tables.computeIfAbsent(TABLE, _ -> new ArrayList<>());
    for (final Person person : people) {
      items.add(person.toItem());
    }
  }

  static Person stored(final FakeDynamoDbClient dynamo, final String id) {
    return dynamo.tables.get(TABLE).stream()
        .map(Person::fromItem)
        .filter(person -> person.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  static Map<String, Object> asAdmin(final Map<String, Object> arguments) {
    return event(arguments, Map.of("custom:class", "admin"));
  }

  /** A signed-in, non-admin user whose own Person is {@code personId}. */
  static Map<String, Object> asPerson(final String personId, final Map<String, Object> arguments) {
    return event(arguments, Map.of("custom:class", "standard", "custom:personId", personId));
  }

  private static Map<String, Object> event(
      final Map<String, Object> arguments, final Map<String, Object> claims) {
    final Map<String, Object> event = new HashMap<>();
    event.put("arguments", new HashMap<>(arguments));
    event.put("identity", Map.of("sub", "test-user", "claims", claims));
    return event;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(final Object value) {
    return (Map<String, Object>) value;
  }
}
