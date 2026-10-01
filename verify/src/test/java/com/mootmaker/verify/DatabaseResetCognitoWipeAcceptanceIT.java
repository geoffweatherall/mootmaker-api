package com.mootmaker.verify;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * Proves {@code database-reset}'s Cognito-wipe survivor logic (see designs/admin-tools-into-api.md)
 * against a real deployed pool, not just the fakes {@code DatabaseResetTest} exercises: a
 * non-reserved Cognito user is deleted by reset, while the demo account (one of the two
 * Terraform-managed reserved accounts) survives. Only Cognito is checked here, since this module
 * has no direct DynamoDB access - the corresponding Person-survival rule is covered by {@code
 * DatabaseResetTest} in mootmaker-api/impl.
 *
 * <p>Not run against {@code production}: reset's Cognito wipe is itself skipped there (see {@code
 * ALLOW_COGNITO_WIPE}), so this test would find its throwaway user was never deleted - a reminder
 * that this suite's own definition of done is a fresh ephemeral environment, never production (see
 * mootmaker-webapp/acceptance/README.md's equivalent rule for the webapp suite).
 */
class DatabaseResetCognitoWipeAcceptanceIT {

  private static final Logger LOG =
      LoggerFactory.getLogger(DatabaseResetCognitoWipeAcceptanceIT.class);

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
    cognitoClient.adminCreateUser(
        AdminCreateUserRequest.builder()
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
    assertThrows(
        UserNotFoundException.class,
        () ->
            cognitoClient.adminGetUser(
                AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(throwawayEmail)
                    .build()));

    LOG.info("Checking the demo account survived");
    assertDoesNotThrow(
        () ->
            cognitoClient.adminGetUser(
                AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(demoUserEmail)
                    .build()));
  }

  /**
   * The fixture users are repaired, not just preserved: a test that damages one (renames it,
   * promotes it, changes its Cognito attributes) cannot leak that into the next test, because the
   * next test's reset puts it back. See FixtureUsers and mootmaker-api#95.
   */
  @Test
  void resetRepairsADamagedFixtureUser() {
    final String email = requireEnv("E2E_STANDARD_USER_EMAIL");
    final GraphQlClient graphQl = GraphQlClient.fromEnvironment();
    DatabaseReset.reset();
    final JsonNode before = personLinkedTo(graphQl, email);
    final String personId = before.get("id").asText();
    final String name = before.get("name").asText();

    LOG.info("Damaging fixture user '{}' (Person {})", email, personId);
    graphQl.execute(
        "mutation($id: ID!, $name: String!) { renamePerson(id: $id, name: $name) { errors } }",
        Map.of("id", personId, "name", "Damaged " + UUID.randomUUID()));
    graphQl.execute(
        "mutation($id: ID!) { setPersonAdmin(id: $id, isAdmin: true) { cognitoSyncFailed } }",
        Map.of("id", personId));
    assertEquals("admin", classOf(email), "precondition: the damage reached Cognito");

    DatabaseReset.reset();

    final JsonNode after = personLinkedTo(graphQl, email);
    assertEquals(personId, after.get("id").asText(), "repaired in place, not re-created");
    assertEquals(name, after.get("name").asText());
    assertEquals(false, after.get("isAdmin").asBoolean());
    assertEquals("standard", classOf(email));
  }

  private static JsonNode personLinkedTo(final GraphQlClient graphQl, final String email) {
    for (final JsonNode person :
        graphQl
            .execute("query { workspace { people { id name isAdmin linkedEmails } } }")
            .path("workspace")
            .path("people")) {
      for (final JsonNode linked : person.path("linkedEmails")) {
        if (email.equalsIgnoreCase(linked.asText())) {
          return person;
        }
      }
    }
    throw new AssertionError("No Person linked to " + email);
  }

  private static String classOf(final String email) {
    return cognitoClient
        .adminGetUser(AdminGetUserRequest.builder().userPoolId(userPoolId).username(email).build())
        .userAttributes()
        .stream()
        .filter(attribute -> "custom:class".equals(attribute.name()))
        .map(AttributeType::value)
        .findFirst()
        .orElse(null);
  }

  private static String requireEnv(final String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name
              + " environment variable is required to run acceptance tests against the deployed"
              + " mootmaker API. Run the tests via ./verify.sh, which exports it.");
    }
    return value;
  }
}
