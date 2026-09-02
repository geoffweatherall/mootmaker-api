package com.mootmaker.verify;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@code database-reset}'s Cognito-wipe survivor logic (see designs/admin-tools-into-api.md)
 * against a real deployed pool, not just the fakes {@code DatabaseResetTest} exercises: a
 * non-reserved Cognito user is deleted by reset, while the demo account (one of the two
 * Terraform-managed reserved accounts) survives. Only Cognito is checked here, since this module
 * has no direct DynamoDB access - the corresponding Person-survival rule is covered by
 * {@code DatabaseResetTest} in mootmaker-api/impl.
 *
 * <p>Not run against {@code production}: reset's Cognito wipe is itself skipped there (see
 * {@code ALLOW_COGNITO_WIPE}), so this test would find its throwaway user was never deleted - a
 * reminder that this suite's own definition of done is a fresh ephemeral environment, never
 * production (see mootmaker-webapp/acceptance/README.md's equivalent rule for the webapp suite).
 */
class DatabaseResetCognitoWipeAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseResetCognitoWipeAcceptanceIT.class);

    private static CognitoIdentityProviderClient cognitoClient;
    private static String userPoolId;
    private static String demoUserEmail;

    @BeforeAll
    static void setUp() {
        userPoolId = requireEnv("COGNITO_USER_POOL_ID");
        demoUserEmail = requireEnv("DEMO_USER_EMAIL");
        cognitoClient = CognitoIdentityProviderClient.builder().build();
    }

    @Test
    void resetDeletesAThrowawayUserButPreservesTheDemoAccount() {
        final String throwawayEmail = "acceptance-test-" + UUID.randomUUID() + "@example.com";
        LOG.info("Creating a throwaway Cognito user '{}'", throwawayEmail);
        cognitoClient.adminCreateUser(AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(throwawayEmail)
                .userAttributes(
                        AttributeType.builder().name("email").value(throwawayEmail).build(),
                        AttributeType.builder().name("email_verified").value("true").build())
                .messageAction(MessageActionType.SUPPRESS)
                .build());

        LOG.info("Resetting the database");
        DatabaseReset.reset();

        LOG.info("Checking the throwaway user was deleted");
        assertThrows(UserNotFoundException.class, () -> cognitoClient.adminGetUser(AdminGetUserRequest.builder()
                .userPoolId(userPoolId)
                .username(throwawayEmail)
                .build()));

        LOG.info("Checking the demo account survived");
        assertDoesNotThrow(() -> cognitoClient.adminGetUser(AdminGetUserRequest.builder()
                .userPoolId(userPoolId)
                .username(demoUserEmail)
                .build()));
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required to run acceptance tests "
                    + "against the deployed mootmaker API. Run the tests via ./verify.sh, which exports it.");
        }
        return value;
    }
}
