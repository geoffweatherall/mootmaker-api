package com.mootmaker.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;

/**
 * Asserts every reserved account carries the role it is meant to, in both places a role lives: the
 * Cognito {@code custom:class} attribute (what authorization reads) and the Person's {@code
 * isAdmin} (what the app displays). The two are set by different code, and the old fixture users
 * drifted between them (mootmaker-api#40, #73).
 *
 * <ul>
 *   <li>The demo user (Terraform-managed, every environment): admin.
 *   <li>The E2E admin user: admin.
 *   <li>The E2E standard user: standard.
 *   <li>The E2E no-person user: standard, with no {@code custom:personId} and no Person at all.
 * </ul>
 *
 * The three E2E users are created and repaired by database reset (FixtureUsers), so this runs after
 * a reset, which is also what a real acceptance run does first. The assertions are about the
 * difference between the users as much as each one's value: a bug that gave them all the same class
 * would pass any single-user check.
 *
 * <p>Ephemeral environments only - the fixture users exist nowhere else.
 */
class ReservedAccountRolesAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(ReservedAccountRolesAcceptanceIT.class);

  private static CognitoIdentityProviderClient cognitoClient;
  private static GraphQlClient graphQlClient;
  private static String userPoolId;

  @BeforeAll
  static void setUp() {
    userPoolId = requireEnv("COGNITO_USER_POOL_ID");
    cognitoClient = CognitoIdentityProviderClient.builder().build();
    graphQlClient = GraphQlClient.fromEnvironment();
    DatabaseReset.reset();
  }

  @Test
  void demoUserIsAdmin() {
    assertRole(requireEnv("DEMO_USER_EMAIL"), "admin", true);
  }

  @Test
  void e2eAdminUserIsAdmin() {
    assertRole(requireEnv("E2E_ADMIN_USER_EMAIL"), "admin", true);
  }

  @Test
  void e2eStandardUserIsStandard() {
    assertRole(requireEnv("E2E_STANDARD_USER_EMAIL"), "standard", false);
  }

  @Test
  void e2eNoPersonUserIsStandardWithNoPerson() {
    final String email = requireEnv("E2E_NO_PERSON_USER_EMAIL");
    final AdminGetUserResponse user = getUser(email);
    assertEquals("standard", attribute(user, "custom:class").orElse(null), email + " class");
    assertNull(attribute(user, "custom:personId").orElse(null), email + " must have no personId");
    assertEquals(Optional.empty(), personLinkedTo(email), email + " must have no Person");
  }

  private static void assertRole(
      final String email, final String expectedClass, final boolean expectedIsAdmin) {
    final AdminGetUserResponse user = getUser(email);
    final String cognitoClass = attribute(user, "custom:class").orElse(null);
    final JsonNode person =
        personLinkedTo(email).orElseThrow(() -> new AssertionError(email + " has no Person"));
    LOG.info(
        "'{}': custom:class '{}', Person isAdmin {}",
        email,
        cognitoClass,
        person.get("isAdmin").asBoolean());
    assertEquals(expectedClass, cognitoClass, email + " custom:class");
    assertEquals(expectedIsAdmin, person.get("isAdmin").asBoolean(), email + " Person isAdmin");
  }

  private static AdminGetUserResponse getUser(final String email) {
    return cognitoClient.adminGetUser(
        AdminGetUserRequest.builder().userPoolId(userPoolId).username(email).build());
  }

  private static Optional<String> attribute(final AdminGetUserResponse user, final String name) {
    return user.userAttributes().stream()
        .filter(attribute -> name.equals(attribute.name()))
        .map(AttributeType::value)
        .findFirst();
  }

  private static Optional<JsonNode> personLinkedTo(final String email) {
    final JsonNode people =
        graphQlClient
            .execute("query { workspace { people { id name isAdmin linkedEmails } } }")
            .path("workspace")
            .path("people");
    return StreamSupport.stream(people.spliterator(), false)
        .filter(
            person ->
                StreamSupport.stream(person.path("linkedEmails").spliterator(), false)
                    .anyMatch(linked -> email.equalsIgnoreCase(linked.asText())))
        .findFirst();
  }

  private static String requireEnv(final String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name
              + " environment variable is required to run acceptance tests against the deployed"
              + " mootmaker API. Run the tests via ./verify.sh, which sets it.");
    }
    return value;
  }
}
