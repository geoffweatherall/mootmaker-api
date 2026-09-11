package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Lambda entry point for database repair - formerly the standalone {@code
 * mootmaker-admin-tools/database-repair} Lambda, now merged back into this repo (see
 * designs/admin-tools-into-api.md). Runs maintenance repairs directly against this environment's
 * Cognito user pool and DynamoDB tables - unlike a GraphQL resolver, this bypasses the API surface
 * entirely, since what it needs to read and fix (the full Cognito user list, the raw anything not
 * exposed through the API.
 *
 * <p>Invoked directly - {@code aws lambda invoke}, the AWS console, or the AWS SDK - never through
 * a wrapper script. {@code event}'s {@code "dryRun"} boolean field controls whether either repair
 * actually writes anything, e.g. {@code aws lambda invoke --payload '{"dryRun": true}'
 * --cli-binary-format raw-in-base64-out}.
 *
 * <p>Previously ran two repairs in parallel; the meeting-participants rebuild went with the join
 * table it maintained, since day-keyed storage has nothing derived left to drift. What remains is
 * they run concurrently on their own threads rather than one after the other.
 *
 * <p>Builds its own plain SDK clients rather than using {@code DynamoDbClientProvider}/ {@code
 * CognitoIdentityProviderClientProvider} - those exist to prime a connection ahead of a SnapStart
 * snapshot for the resolvers/post-confirmation functions, which needs {@code
 * dynamodb:DescribeTable}; this Lambda has no SnapStart config and its own dedicated IAM role
 * deliberately doesn't grant that action, scoped to only what these repairs actually do.
 */
public final class DatabaseRepairHandler
    implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  @Override
  public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
    final boolean dryRun = event != null && Boolean.TRUE.equals(event.get("dryRun"));

    final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
    final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");

    try (DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
        CognitoIdentityProviderClient cognitoClient =
            CognitoIdentityProviderClient.builder().build()) {

      final CreateMissingPersonsRepair.Result missingPersonsResult =
          runCreateMissingPersonsRepair(
              cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);

      final Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("dryRun", dryRun);
      summary.put("personsCreated", missingPersonsResult.repaired());
      summary.put("personsAlreadyLinked", missingPersonsResult.alreadyLinked());
      return summary;
    }
  }

  private static CreateMissingPersonsRepair.Result runCreateMissingPersonsRepair(
      final CognitoIdentityProviderClient cognitoClient,
      final DynamoDbClient dynamoDbClient,
      final String userPoolId,
      final String peopleTableName,
      final boolean dryRun) {
    System.out.println(
        "Repair: creating a Person for every confirmed Cognito user that doesn't have one"
            + (dryRun ? " (dry run - no changes will be made)" : "")
            + "...");

    final CreateMissingPersonsRepair.Result result =
        CreateMissingPersonsRepair.run(
            cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);

    System.out.println(
        "Done: "
            + result.repaired()
            + " Person record(s) "
            + (dryRun ? "would be " : "")
            + "created, "
            + result.alreadyLinked()
            + " user(s) already had one.");
    return result;
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
}
