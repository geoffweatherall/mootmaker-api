package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.cognito.CognitoIdentityProviderClientProvider;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import module java.base;

/**
 * Lambda entry point for database reset - formerly {@code Mutation.reset} in this API, then the
 * standalone {@code mootmaker-admin-tools/database-reset} Lambda, now merged back into this repo
 * (see designs/admin-tools-into-api.md). Invoked directly - {@code aws lambda invoke}, the AWS
 * console, or the AWS SDK - never through a wrapper script; the input payload is unused, there is
 * nothing to configure per invocation. Deletes every stored room and meeting, and - except in
 * {@code production} - wipes the Cognito user pool down to the two Terraform-managed reserved
 * accounts (demo, e2e) and every Person still linked to one of them. See {@link DatabaseReset} for
 * what actually gets deleted and why.
 *
 * <p>{@code ALLOW_COGNITO_WIPE} is computed by Terraform from the target environment
 * ({@code environment != "production"}), not read from the invoke payload - whether wiping Cognito
 * is allowed is a property of which environment this Lambda is deployed to, decided once at deploy
 * time, structurally impossible to override per-invocation.
 */
public final class DatabaseResetHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        final String roomsTableName = requireEnv("ROOMS_TABLE_NAME");
        final String peopleTableName = requireEnv("PEOPLE_TABLE_NAME");
        final String meetingsTableName = requireEnv("MEETINGS_TABLE_NAME");
        final String meetingParticipantsTableName = requireEnv("MEETING_PARTICIPANTS_TABLE_NAME");
        final String userPoolId = requireEnv("COGNITO_USER_POOL_ID");
        final boolean allowCognitoWipe = Boolean.parseBoolean(requireEnv("ALLOW_COGNITO_WIPE"));
        final Set<String> reservedEmails = parseReservedEmails(System.getenv("RESERVED_ACCOUNT_EMAILS"));

        final DynamoDbClient dynamoDbClient = DynamoDbClientProvider.client();
        final CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClientProvider.client();

        // The Cognito wipe (if allowed) runs first, synchronously, because the DynamoDB people
        // deletion below needs its result (which Cognito subs survived) to know which Persons to
        // keep. Rooms, meetings, and people are otherwise independent of each other, so once that's
        // known they run concurrently, same as before this Lambda gained a Cognito step.
        final int cognitoUsersDeleted;
        final boolean cognitoWipeSkipped = !allowCognitoWipe;
        final Set<String> survivingSubs;
        if (allowCognitoWipe) {
            System.out.println("Wiping the Cognito user pool (reserved accounts excepted)...");
            final DatabaseReset.CognitoWipeResult wipeResult = DatabaseReset.wipeCognitoPool(cognitoClient, userPoolId, reservedEmails);
            cognitoUsersDeleted = wipeResult.usersDeleted();
            survivingSubs = wipeResult.survivingSubs();
        } else {
            System.out.println("Skipping the Cognito wipe: this environment is production.");
            cognitoUsersDeleted = 0;
            survivingSubs = Set.of();
        }

        final ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            final Future<Integer> roomsFuture = executor.submit(() -> DatabaseReset.deleteAllItems(dynamoDbClient, roomsTableName));
            final Future<Integer> peopleFuture = executor.submit(() -> allowCognitoWipe
                    ? DatabaseReset.deletePeopleNotLinkedTo(dynamoDbClient, peopleTableName, survivingSubs)
                    : DatabaseReset.deleteUnlinkedPeople(dynamoDbClient, peopleTableName));
            final Future<Integer> meetingsFuture = executor.submit(() -> DatabaseReset.deleteAllMeetingsAndParticipants(
                    dynamoDbClient, meetingsTableName, meetingParticipantsTableName));

            final int roomsDeleted = getResult(roomsFuture);
            final int peopleDeleted = getResult(peopleFuture);
            final int meetingsDeleted = getResult(meetingsFuture);

            System.out.println("Deleted " + roomsDeleted + " room(s), " + peopleDeleted + " person(s), " + meetingsDeleted
                    + " meeting(s) (and their participant rows)" + (cognitoWipeSkipped ? "." : ", " + cognitoUsersDeleted
                    + " Cognito user(s)."));

            final Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("roomsDeleted", roomsDeleted);
            summary.put("peopleDeleted", peopleDeleted);
            summary.put("meetingsDeleted", meetingsDeleted);
            summary.put("cognitoWipeSkipped", cognitoWipeSkipped);
            summary.put("cognitoUsersDeleted", cognitoUsersDeleted);
            return summary;
        } finally {
            executor.shutdown();
        }
    }

    private static <T> T getResult(final Future<T> future) {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(cause);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a deletion pass to finish", e);
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

    /** Same parsing DeleteMyAccountHandler uses for the same env var - case-insensitive, comma-separated. */
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
