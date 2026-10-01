package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.PersonRepository;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Lambda entry point for database reset - formerly {@code Mutation.reset} in this API, then the
 * standalone {@code mootmaker-admin-tools/database-reset} Lambda, now merged back into this repo
 * (see designs/admin-tools-into-api.md). Invoked directly - {@code aws lambda invoke}, the AWS
 * console, or the AWS SDK - never through a wrapper script; the input payload is unused, there is
 * nothing to configure per invocation. Deletes every stored room and meeting, every avatar that
 * does not belong to a person who survives, and - except in {@code production} - wipes the Cognito
 * user pool down to the reserved accounts (the demo user, and the fixture users in ephemeral
 * environments) and every Person still linked to one of them, then ensures and repairs the fixture
 * users (see {@link FixtureUsers}). See {@link DatabaseReset} for what actually gets deleted and
 * why.
 *
 * <p>{@code ALLOW_COGNITO_WIPE} is computed by Terraform from the target environment ({@code
 * environment != "production"}), not read from the invoke payload - whether wiping Cognito is
 * allowed is a property of which environment this Lambda is deployed to, decided once at deploy
 * time, structurally impossible to override per-invocation.
 *
 * <p>Builds its own plain SDK clients rather than using {@code DynamoDbClientProvider}/ {@code
 * CognitoIdentityProviderClientProvider} - those exist to prime a connection ahead of a SnapStart
 * snapshot for the resolvers/post-confirmation functions, which needs {@code
 * dynamodb:DescribeTable}; this Lambda has no SnapStart config and its own dedicated IAM role
 * deliberately doesn't grant that action, scoped to only what reset actually does.
 */
public final class DatabaseResetHandler
    implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  @Override
  public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
    final String roomsTableName = requireEnv("ROOMS_TABLE_NAME");
    final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
    final String meetingsTableName = requireEnv("MEETINGS_TABLE_NAME");
    final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
    final String avatarsBucketName = requireEnv("AVATARS_BUCKET_NAME");
    final boolean allowCognitoWipe = Boolean.parseBoolean(requireEnv("ALLOW_COGNITO_WIPE"));
    final Set<String> reservedEmails =
        parseReservedEmails(System.getenv("RESERVED_ACCOUNT_EMAILS"));
    final List<FixtureUsers.Fixture> fixtures = FixtureUsers.fromEnvironment(System.getenv());

    try (DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
        CognitoIdentityProviderClient cognitoClient =
            CognitoIdentityProviderClient.builder().build();
        S3Client s3Client = S3Client.builder().build()) {

      // The Cognito wipe (if allowed) runs first, synchronously, because the DynamoDB people
      // deletion below needs its result (which Cognito subs survived) to know which Persons
      // to keep. Rooms, meetings, and people are otherwise independent of each other, so once
      // that's known they run concurrently, same as before this Lambda gained a Cognito step.
      final int cognitoUsersDeleted;
      final boolean cognitoWipeSkipped = !allowCognitoWipe;
      final Set<String> survivingSubs;
      if (allowCognitoWipe) {
        System.out.println("Wiping the Cognito user pool (reserved accounts excepted)...");
        final DatabaseReset.CognitoWipeResult wipeResult =
            DatabaseReset.wipeCognitoPool(cognitoClient, userPoolId, reservedEmails);
        cognitoUsersDeleted = wipeResult.usersDeleted();
        survivingSubs = wipeResult.survivingSubs();
      } else {
        System.out.println("Skipping the Cognito wipe: this environment is production.");
        cognitoUsersDeleted = 0;
        survivingSubs = Set.of();
      }

      final ExecutorService executor = Executors.newFixedThreadPool(3);
      try {
        final Future<Integer> roomsFuture =
            executor.submit(() -> DatabaseReset.deleteAllItems(dynamoDbClient, roomsTableName));
        final Future<Integer> peopleFuture =
            executor.submit(
                () ->
                    allowCognitoWipe
                        ? DatabaseReset.deletePeopleNotLinkedTo(
                            dynamoDbClient, peopleTableName, survivingSubs)
                        : DatabaseReset.deleteUnlinkedPeople(dynamoDbClient, peopleTableName));
        final Future<Integer> meetingsFuture =
            executor.submit(
                () -> DatabaseReset.deleteAllMeetings(dynamoDbClient, meetingsTableName));

        final int roomsDeleted = getResult(roomsFuture);
        final int peopleDeleted = getResult(peopleFuture);
        final int meetingsDeleted = getResult(meetingsFuture);

        // The fixture users (ephemeral environments only) are ensured and repaired after the
        // people pass, so the Persons this writes are not swept up by it. None are configured in
        // production, and none in test, so there this is a no-op. Never when the Cognito wipe is
        // off: repair assumes the pool was just cleared down to the reserved accounts.
        final Set<String> fixturePersonIds;
        if (allowCognitoWipe && !fixtures.isEmpty()) {
          System.out.println("Ensuring and repairing " + fixtures.size() + " fixture user(s)...");
          fixturePersonIds =
              new FixtureUsers(
                      cognitoClient,
                      new PersonRepository(dynamoDbClient, peopleTableName),
                      userPoolId,
                      requireEnv("COGNITO_WEBAPP_CLIENT_ID"))
                  .ensureAndRepair(fixtures);
        } else {
          fixturePersonIds = Set.of();
        }

        // After the people pass, not alongside it: which avatars survive is decided by which
        // people did. See DatabaseReset#deleteAvatarsExceptThoseOf. A repaired fixture user has
        // no avatar, so its objects go too.
        final Set<String> keepAvatarsOf =
            new HashSet<>(DatabaseReset.personIds(dynamoDbClient, peopleTableName));
        keepAvatarsOf.removeAll(fixturePersonIds);
        final int avatarObjectsDeleted =
            DatabaseReset.deleteAvatarsExceptThoseOf(s3Client, avatarsBucketName, keepAvatarsOf);

        System.out.println(
            "Deleted "
                + roomsDeleted
                + " room(s), "
                + peopleDeleted
                + " person(s), "
                + meetingsDeleted
                + " meeting(s) (and their participant rows), "
                + avatarObjectsDeleted
                + " avatar object(s)"
                + (cognitoWipeSkipped ? "." : ", " + cognitoUsersDeleted + " Cognito user(s)."));

        final Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("roomsDeleted", roomsDeleted);
        summary.put("peopleDeleted", peopleDeleted);
        summary.put("meetingsDeleted", meetingsDeleted);
        summary.put("avatarObjectsDeleted", avatarObjectsDeleted);
        summary.put("cognitoWipeSkipped", cognitoWipeSkipped);
        summary.put("cognitoUsersDeleted", cognitoUsersDeleted);
        summary.put("fixtureUsersRepaired", allowCognitoWipe ? fixtures.size() : 0);
        return summary;
      } finally {
        executor.shutdown();
      }
    }
  }

  private static <T> T getResult(final Future<T> future) {
    try {
      return future.get();
    } catch (final ExecutionException e) {
      final Throwable cause = e.getCause();
      throw cause instanceof RuntimeException runtimeException
          ? runtimeException
          : new IllegalStateException(cause);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a deletion pass to finish", e);
    }
  }

  private static String requireEnv(final String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name
              + " environment variable is required. This function must be deployed via ./deploy.sh,"
              + " which sets it.");
    }
    return value;
  }

  /**
   * Same parsing DeleteMyAccountHandler uses for the same env var - case-insensitive,
   * comma-separated.
   */
  private static Set<String> parseReservedEmails(final String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }
}
