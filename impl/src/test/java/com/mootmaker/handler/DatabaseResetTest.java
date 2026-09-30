package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.AttendeeStatus;
import com.mootmaker.model.MeetingRecord;
import com.mootmaker.model.Person;
import com.mootmaker.testsupport.DayFixtures;
import com.mootmaker.testsupport.FakeCognitoIdentityProviderClient;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.FakeS3Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class DatabaseResetTest {

  private static final String ROOMS_TABLE = "Rooms";
  private static final String PEOPLE_TABLE = "People";
  private static final String MEETINGS_TABLE = "Meetings";
  private static final String USER_POOL_ID = "pool-1";

  private static Map<String, AttributeValue> room(final String id) {
    return Map.of("id", AttributeValue.builder().s(id).build());
  }

  private static Map<String, AttributeValue> person(final String id, final String cognitoSub) {
    return new Person(id, "Someone", cognitoSub).toItem();
  }

  private static MeetingRecord meeting(
      final String id, final String organiserId, final List<String> attendeeIds) {
    return new MeetingRecord(
        id,
        "room-1",
        organiserId,
        attendeeIds,
        Collections.nCopies(attendeeIds.size(), AttendeeStatus.NoResponse),
        "Subject",
        "2026-07-01T09:00:00",
        "2026-07-01T10:00:00");
  }

  private static UserType user(final String username, final String sub, final String email) {
    return UserType.builder()
        .username(username)
        .attributes(
            AttributeType.builder().name("sub").value(sub).build(),
            AttributeType.builder().name("email").value(email).build())
        .build();
  }

  @Test
  void deleteAllItemsEmptiesTheTable() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    dynamoDbClient.tables.put(
        ROOMS_TABLE, new ArrayList<>(List.of(room("room-1"), room("room-2"))));

    final int deleted = DatabaseReset.deleteAllItems(dynamoDbClient, ROOMS_TABLE);

    assertEquals(2, deleted);
    assertTrue(dynamoDbClient.tables.get(ROOMS_TABLE).isEmpty());
  }

  @Test
  void deleteAllItemsSucceedsWhenTableIsAlreadyEmpty() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

    final int deleted = DatabaseReset.deleteAllItems(dynamoDbClient, ROOMS_TABLE);

    assertEquals(0, deleted);
  }

  @Test
  void deleteUnlinkedPeopleRemovesOnlyPeopleWithNoCognitoSub() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    dynamoDbClient.tables.put(
        PEOPLE_TABLE,
        new ArrayList<>(List.of(person("guest-1", null), person("linked-1", "cognito-sub-123"))));

    final int deleted = DatabaseReset.deleteUnlinkedPeople(dynamoDbClient, PEOPLE_TABLE);

    assertEquals(1, deleted);
    final List<Map<String, AttributeValue>> remaining = dynamoDbClient.tables.get(PEOPLE_TABLE);
    assertEquals(1, remaining.size());
    assertEquals("linked-1", remaining.getFirst().get("id").s());
  }

  @Test
  void deleteAllMeetingsRemovesEveryDayAndPointerButKeepsTheRetentionBoundary() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    final MeetingRecord meeting = meeting("meeting-1", "organiser-1", List.of("attendee-1"));
    dynamoDbClient.tables.put(MEETINGS_TABLE, new ArrayList<>(DayFixtures.dayItems(meeting)));

    final int deleted = DatabaseReset.deleteAllMeetings(dynamoDbClient, MEETINGS_TABLE);

    assertEquals(1, deleted, "the count is meetings removed, not items");
    assertTrue(DayFixtures.meetingsIn(dynamoDbClient, MEETINGS_TABLE).isEmpty());
    assertTrue(
        dynamoDbClient.tables.get(MEETINGS_TABLE).stream()
            .noneMatch(item -> item.get("pk").s().startsWith("PTR#")),
        "pointers must go with their days");

    // The one thing a reset must NOT remove. It is configuration seeded by Terraform, and without
    // it every subsequent booking fails because the bookable window cannot be computed - leaving an
    // environment that looks reset and refuses every write.
    assertEquals(
        1,
        dynamoDbClient.tables.get(MEETINGS_TABLE).size(),
        "only the retention config should remain");
    assertEquals(
        "CONFIG#retention", dynamoDbClient.tables.get(MEETINGS_TABLE).getFirst().get("pk").s());
  }

  @Test
  void deleteAllMeetingsPutsTheRetentionBoundaryBackToTodaysNaturalValue() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    // A boundary far in the FUTURE, which is the state the retention acceptance test leaves
    // behind: it must advance the boundary to observe a deletion at all, and the boundary is
    // monotonic, so nothing could put it back. Left alone, an environment's bookable window
    // crept further forward with every run until nothing near-term could be booked.
    dynamoDbClient.tables.put(
        MEETINGS_TABLE, new ArrayList<>(List.of(DayFixtures.retentionConfig("2026-11-02"))));

    DatabaseReset.deleteAllMeetings(dynamoDbClient, MEETINGS_TABLE);

    final Map<String, AttributeValue> config =
        dynamoDbClient.tables.get(MEETINGS_TABLE).stream()
            .filter(item -> "CONFIG#retention".equals(item.get("pk").s()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the config item must still exist"));
    assertEquals(
        DatabaseReset.naturalBoundary(LocalDate.now()),
        config.get("earliestRetainedDate").s(),
        "a reset must leave the boundary where the weekly job would put it today");
  }

  @Test
  void naturalBoundaryIsTheMondayOnOrBeforeThirtyDaysAgo() {
    // Pinned against a known Wednesday: 2026-09-09 minus 30 days is 2026-08-10, itself a Monday.
    assertEquals("2026-08-10", DatabaseReset.naturalBoundary(LocalDate.parse("2026-09-09")));
    // And a date whose minus-30 lands mid-week rounds BACK, never forward.
    assertEquals("2026-08-10", DatabaseReset.naturalBoundary(LocalDate.parse("2026-09-14")));
  }

  @Test
  void deleteAllMeetingsSucceedsWhenTheTableIsAlreadyEmpty() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

    final int deleted = DatabaseReset.deleteAllMeetings(dynamoDbClient, MEETINGS_TABLE);

    assertEquals(0, deleted);
  }

  @Test
  void wipeCognitoPoolDeletesEveryUserExceptReservedEmails() {
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    cognitoClient.users.addAll(
        List.of(
            user("demo@mootmaker.com", "demo-sub", "demo@mootmaker.com"),
            user("e2e-tests@example.com", "e2e-sub", "e2e-tests@example.com"),
            user("stray-signup@example.com", "stray-sub", "stray-signup@example.com")));
    final Set<String> reservedEmails = Set.of("demo@mootmaker.com", "e2e-tests@example.com");

    final DatabaseReset.CognitoWipeResult result =
        DatabaseReset.wipeCognitoPool(cognitoClient, USER_POOL_ID, reservedEmails);

    assertEquals(1, result.usersDeleted());
    assertEquals(Set.of("demo-sub", "e2e-sub"), result.survivingSubs());
    assertEquals(1, cognitoClient.deleteRequests.size());
    assertEquals("stray-signup@example.com", cognitoClient.deleteRequests.getFirst().username());
  }

  @Test
  void wipeCognitoPoolMatchesReservedEmailsCaseInsensitively() {
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    cognitoClient.users.add(user("demo@mootmaker.com", "demo-sub", "Demo@Mootmaker.com"));
    final Set<String> reservedEmails = Set.of("demo@mootmaker.com");

    final DatabaseReset.CognitoWipeResult result =
        DatabaseReset.wipeCognitoPool(cognitoClient, USER_POOL_ID, reservedEmails);

    assertEquals(0, result.usersDeleted());
    assertEquals(Set.of("demo-sub"), result.survivingSubs());
  }

  @Test
  void wipeCognitoPoolFollowsPaginationAcrossMultiplePages() {
    final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
    cognitoClient.users.addAll(
        List.of(
            user("one@example.com", "sub-1", "one@example.com"),
            user("two@example.com", "sub-2", "two@example.com"),
            user("three@example.com", "sub-3", "three@example.com")));
    cognitoClient.listUsersPageSize = 1;

    final DatabaseReset.CognitoWipeResult result =
        DatabaseReset.wipeCognitoPool(cognitoClient, USER_POOL_ID, Set.of());

    assertEquals(3, result.usersDeleted());
    assertEquals(3, cognitoClient.deleteRequests.size());
  }

  @Test
  void deletePeopleNotLinkedToRemovesPeopleWithNoCognitoSubAndPeopleWithAStaleOne() {
    final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
    dynamoDbClient.tables.put(
        PEOPLE_TABLE,
        new ArrayList<>(
            List.of(
                person("guest-1", null),
                person("stray-1", "deleted-sub"),
                person("demo-person", "demo-sub"))));

    final int deleted =
        DatabaseReset.deletePeopleNotLinkedTo(dynamoDbClient, PEOPLE_TABLE, Set.of("demo-sub"));

    assertEquals(2, deleted);
    final List<Map<String, AttributeValue>> remaining = dynamoDbClient.tables.get(PEOPLE_TABLE);
    assertEquals(1, remaining.size());
    assertEquals("demo-person", remaining.getFirst().get("id").s());
  }

  // --- Avatars --------------------------------------------------------------------------

  private static FakeS3Client bucketWith(final String... keys) {
    final FakeS3Client s3 = new FakeS3Client();
    for (final String key : keys) {
      s3.stage(key, new byte[] {1}, "image/jpeg");
    }
    return s3;
  }

  @Test
  void avatarResetEmptiesTheBucketWhenNobodySurvives() {
    final FakeS3Client s3 =
        bucketWith(
            "avatars/v1/person-1/aaa.jpg", "avatars/v1/person-2/bbb.jpg", "uploads/person-1/UP01");

    final int deleted = DatabaseReset.deleteAvatarsExceptThoseOf(s3, "bucket", Set.of());

    assertEquals(3, deleted);
    assertTrue(s3.objects.isEmpty());
  }

  /**
   * A reset keeps some people - the reserved accounts everywhere, every linked person in
   * production. Emptying the bucket outright would leave each of them with an avatarUrl pointing at
   * a deleted object: a broken image, where a person with no avatar at least gets initials.
   */
  @Test
  @DisplayName("a person who survives the reset keeps their avatar")
  void avatarResetKeepsTheAvatarsOfSurvivingPeople() {
    final FakeS3Client s3 =
        bucketWith("avatars/v1/demo/aaa.jpg", "avatars/v1/guest/bbb.jpg", "uploads/demo/UP01");

    final int deleted = DatabaseReset.deleteAvatarsExceptThoseOf(s3, "bucket", Set.of("demo"));

    assertEquals(2, deleted);
    assertEquals(Set.of("avatars/v1/demo/aaa.jpg"), s3.objects.keySet());
  }

  @Test
  @DisplayName("staged uploads go even for a survivor - they are nobody's avatar yet")
  void avatarResetAlwaysClearsStagedUploads() {
    final FakeS3Client s3 = bucketWith("uploads/demo/UP01", "uploads/demo/UP02");

    DatabaseReset.deleteAvatarsExceptThoseOf(s3, "bucket", Set.of("demo"));

    assertTrue(s3.objects.isEmpty());
  }

  @Test
  @DisplayName("an object under a prefix that names nobody is swept up")
  void avatarResetSweepsOrphans() {
    final FakeS3Client s3 =
        bucketWith("avatars/v1/long-gone/aaa.jpg", "avatars/stray.jpg", "stray-at-root");

    DatabaseReset.deleteAvatarsExceptThoseOf(s3, "bucket", Set.of("demo"));

    assertTrue(s3.objects.isEmpty());
  }

  @Test
  @DisplayName("a survivor's id being a prefix of a deleted person's id protects only the survivor")
  void avatarResetDoesNotConfuseIdsThatShareAPrefix() {
    final FakeS3Client s3 = bucketWith("avatars/v1/abc/aaa.jpg", "avatars/v1/abcd/bbb.jpg");

    DatabaseReset.deleteAvatarsExceptThoseOf(s3, "bucket", Set.of("abc"));

    assertEquals(Set.of("avatars/v1/abc/aaa.jpg"), s3.objects.keySet());
  }

  @Test
  void personIdsAreWhoeverIsLeftInTheTable() {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(
        PEOPLE_TABLE, new ArrayList<>(List.of(person("p1", "sub-1"), person("p2", null))));

    assertEquals(Set.of("p1", "p2"), DatabaseReset.personIds(fakeClient, PEOPLE_TABLE));
    assertEquals(Set.of(), DatabaseReset.personIds(new FakeDynamoDbClient(), PEOPLE_TABLE));
  }
}
