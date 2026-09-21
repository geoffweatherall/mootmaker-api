package com.mootmaker.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import module java.base;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;

/**
 * Asserts the fixture Cognito users created by {@code deploy/terraform/cognito.tf} actually carry
 * the {@code custom:class} they're meant to: {@code demo} must be {@code admin} (see {@code
 * aws_cognito_user.demo}) and {@code e2e} must be {@code standard} (see {@code
 * aws_cognito_user.e2e}). See mootmaker-api#40.
 *
 * <p>This has to be an acceptance test, not a unit test: both users' {@code custom:*} attributes
 * are set via {@code lifecycle { ignore_changes = [attributes] } } (see mootmaker-api#39), a
 * Terraform create/update convergence quirk that only shows up against a real deployed pool - a
 * unit test against a fake Cognito client would only prove the Terraform config says what it says,
 * not that Terraform actually applied it correctly. mootmaker-api#39 shipped a fix (#41) without
 * any check that would have caught the original bug in the first place; this test exists so a
 * regression - either user's class silently reverting to empty, or the two swapping - gets caught
 * rather than assumed. The assertion is deliberately about the *difference* between the two users,
 * not just demo's value in isolation, since a bug that set both to the same class would pass a
 * single-user check.
 *
 * <p>Not run against {@code production}: nothing about {@code custom:class} is skipped there
 * (unlike {@code database-reset}'s Cognito wipe), but this suite's own definition of done is a
 * fresh ephemeral environment, never production - see {@code
 * DatabaseResetCognitoWipeAcceptanceIT}'s equivalent note.
 */
class DemoAndE2eUserRolesAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(DemoAndE2eUserRolesAcceptanceIT.class);
  private static final String CLASS_ATTRIBUTE = "custom:class";

  private static CognitoIdentityProviderClient cognitoClient;
  private static String userPoolId;
  private static String demoUserEmail;
  private static String e2eUserEmail;

  @BeforeAll
  static void setUp() {
    userPoolId = requireEnv("COGNITO_USER_POOL_ID");
    demoUserEmail = requireEnv("DEMO_USER_EMAIL");
    e2eUserEmail = requireEnv("E2E_USER_EMAIL");
    cognitoClient = CognitoIdentityProviderClient.builder().build();
  }

  @Test
  void demoUserIsAdminAndE2eUserIsStandard() {
    final String demoClass = classAttributeOf(demoUserEmail);
    final String e2eClass = classAttributeOf(e2eUserEmail);

    LOG.info("demo user '{}' has custom:class '{}'", demoUserEmail, demoClass);
    LOG.info("e2e user '{}' has custom:class '{}'", e2eUserEmail, e2eClass);

    assertEquals("admin", demoClass, "demo user's custom:class");
    assertEquals("standard", e2eClass, "e2e user's custom:class");
  }

  private static String classAttributeOf(final String username) {
    final AdminGetUserResponse response =
        cognitoClient.adminGetUser(
            AdminGetUserRequest.builder().userPoolId(userPoolId).username(username).build());
    return response.userAttributes().stream()
        .filter(attribute -> CLASS_ATTRIBUTE.equals(attribute.name()))
        .map(AttributeType::value)
        .findFirst()
        .orElseGet(() -> fail(username + " has no " + CLASS_ATTRIBUTE + " attribute set"));
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
