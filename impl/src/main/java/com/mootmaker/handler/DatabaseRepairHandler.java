package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * Lambda entry point for database repair - formerly the standalone
 * {@code mootmaker-admin-tools/database-repair} Lambda, now merged back into this repo (see
 * designs/admin-tools-into-api.md). Runs maintenance repairs directly against this environment's
 * Cognito user pool and DynamoDB tables - unlike a GraphQL resolver, this bypasses the API surface
 * entirely, since what it needs to read and fix (the full Cognito user list, the raw
 * meeting-participants join table) isn't exposed there.
 *
 * <p>Invoked directly - {@code aws lambda invoke}, the AWS console, or the AWS SDK - never through
 * a wrapper script. {@code event}'s {@code "dryRun"} boolean field controls whether either repair
 * actually writes anything, e.g. {@code aws lambda invoke --payload '{"dryRun": true}'
 * --cli-binary-format raw-in-base64-out}.
 *
 * <p>The two repairs touch entirely different tables (People vs. Meetings/meeting-participants), so
 * they run concurrently on their own threads rather than one after the other.
 */
public final class DatabaseRepairHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final boolean dryRun = event != null && Boolean.TRUE.equals(event.get("dryRun"));

        final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
        final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
        final String meetingsTableName = requireEnv("MEETINGS_TABLE_NAME");
        final String meetingParticipantsTableName = requireEnv("MEETING_PARTICIPANTS_TABLE_NAME");

        final DynamoDbClient dynamoDbClient = DynamoDbClientProvider.client();
        final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClientProvider.client();

        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<CreateMissingPersonsRepair.Result> missingPersonsFuture = executor.submit(() ->
                    runCreateMissingPersonsRepair(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun));
            final Future<RebuildMeetingParticipantsRepair.Result> participantsFuture = executor.submit(() ->
                    runRebuildMeetingParticipantsRepair(dynamoDbClient, meetingsTableName, meetingParticipantsTableName, dryRun));

            final CreateMissingPersonsRepair.Result missingPersonsResult = getResult(missingPersonsFuture);
            final RebuildMeetingParticipantsRepair.Result participantsResult = getResult(participantsFuture);

            final Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("dryRun", dryRun);
            summary.put("personsCreated", missingPersonsResult.repaired());
            summary.put("personsAlreadyLinked", missingPersonsResult.alreadyLinked());
            summary.put("participantRowsCreated", participantsResult.created());
            summary.put("participantRowsRemoved", participantsResult.removed());
            summary.put("participantRowsAlreadyCorrect", participantsResult.alreadyCorrect());
            return summary;
        } finally {
            executor.shutdown();
        }
    }

    private static CreateMissingPersonsRepair.Result runCreateMissingPersonsRepair(
            final CognitoIdentityProviderClient cognitoClient, final DynamoDbClient dynamoDbClient,
            final String userPoolId, final String peopleTableName, final boolean dryRun) {
        System.out.println("Repair: creating a Person for every confirmed Cognito user that doesn't have one"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, userPoolId, peopleTableName, dryRun);

        System.out.println("Done: " + result.repaired() + " Person record(s) " + (dryRun ? "would be " : "")
                + "created, " + result.alreadyLinked() + " user(s) already had one.");
        return result;
    }

    private static RebuildMeetingParticipantsRepair.Result runRebuildMeetingParticipantsRepair(
            final DynamoDbClient dynamoDbClient, final String meetingsTableName,
            final String meetingParticipantsTableName, final boolean dryRun) {
        System.out.println("Repair: rebuilding meeting-participants from the meetings table"
                + (dryRun ? " (dry run - no changes will be made)" : "") + "...");

        final RebuildMeetingParticipantsRepair.Result result =
                RebuildMeetingParticipantsRepair.run(dynamoDbClient, meetingsTableName, meetingParticipantsTableName, dryRun);

        System.out.println("Done: " + result.created() + " participant row(s) " + (dryRun ? "would be " : "") + "created, "
                + result.removed() + " " + (dryRun ? "would be " : "") + "removed, " + result.alreadyCorrect() + " already correct.");
        return result;
    }

    private static <T> T getResult(final Future<T> future) {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a repair to finish", e);
        }
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " environment variable is required. This function must be deployed via ./deploy.sh, which sets it.");
        }
        return value;
    }
}
