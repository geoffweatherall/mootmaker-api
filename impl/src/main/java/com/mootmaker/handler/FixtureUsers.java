package com.mootmaker.handler;

import module java.base;

import com.mootmaker.dynamo.IdAllocator;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminEnableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * The test fixture users every database reset ensures exist and repairs back to a known state: an
 * admin, a standard user, and a standard user with no linked Person (mootmaker-api#95). Ephemeral
 * environments only - Terraform configures none anywhere else, and then this does nothing.
 *
 * <p><b>Ensure: missing accounts are created through the real sign-up path.</b> {@code SignUp} with
 * the webapp's own client id, then {@code AdminConfirmSignUp}, which fires PostConfirmation exactly
 * as a real confirmation does - so the Person, its {@code custom:personId} claim and its default
 * class all come from the same code a real user's do. The old fixtures were made by Terraform
 * instead, and drifted from real users because of it (#73).
 *
 * <p><b>Repair, never recreate.</b> An account that exists is put back: password, enabled, email
 * verified, name and class in Cognito; name, admin flag, default preferences, no avatar and its
 * Cognito link on the Person. Recreating would be simpler to reason about, but every new Cognito
 * user counts against the pool's free monthly-active-user allowance again, and re-signing up costs
 * seconds per user on every reset where repair costs milliseconds.
 *
 * <p>Runs after the rest of the reset, so the Persons it writes are not swept up by the people
 * pass. Their avatars are deleted by the reset's avatar pass, which is told to keep nobody's.
 */
final class FixtureUsers {

  /** One fixture user, as configured by Terraform (see admin-tools.tf). */
  record Fixture(String role, String email, String name, String password) {

    boolean isAdmin() {
      return "admin".equals(role);
    }

    /** The no-person fixture exists to exercise the "account with no linked Person" path. */
    boolean hasPerson() {
      return !"no-person".equals(role);
    }
  }

  private static final List<String> ROLES = List.of("admin", "standard", "no-person");

  private final CognitoIdentityProviderClient cognitoClient;
  private final PersonRepository people;
  private final String userPoolId;
  private final String webappClientId;

  FixtureUsers(
      final CognitoIdentityProviderClient cognitoClient,
      final PersonRepository people,
      final String userPoolId,
      final String webappClientId) {
    this.cognitoClient = cognitoClient;
    this.people = people;
    this.userPoolId = userPoolId;
    this.webappClientId = webappClientId;
  }

  /**
   * Reads {@code FIXTURE_USER_<ROLE>_EMAIL/_NAME/_PASSWORD} for each role. A role with no email
   * configured is skipped, so an environment with none configured yields an empty list.
   */
  static List<Fixture> fromEnvironment(final Map<String, String> env) {
    final List<Fixture> fixtures = new ArrayList<>();
    for (final String role : ROLES) {
      final String prefix = "FIXTURE_USER_" + role.toUpperCase(Locale.ROOT).replace('-', '_');
      final String email = env.get(prefix + "_EMAIL");
      if (email == null || email.isBlank()) {
        continue;
      }
      fixtures.add(
          new Fixture(
              role,
              email,
              requireValue(env, prefix + "_NAME"),
              requireValue(env, prefix + "_PASSWORD")));
    }
    return fixtures;
  }

  /** Ensures and repairs every fixture. Returns the ids of the Persons it wrote. */
  Set<String> ensureAndRepair(final List<Fixture> fixtures) {
    final Set<String> personIds = new HashSet<>();
    for (final Fixture fixture : fixtures) {
      ensureAndRepair(fixture).ifPresent(personIds::add);
    }
    return personIds;
  }

  private Optional<String> ensureAndRepair(final Fixture fixture) {
    AdminGetUserResponse user = findUser(fixture.email()).orElse(null);
    if (user == null) {
      System.out.println("  creating fixture user through sign-up: " + fixture.email());
      signUp(fixture);
      user =
          findUser(fixture.email())
              .orElseThrow(
                  () -> new IllegalStateException("Signed up but not found: " + fixture.email()));
    } else {
      System.out.println("  repairing fixture user: " + fixture.email());
    }

    final String username = user.username();
    final String sub = attribute(user, "sub");
    repairCognito(fixture, username);

    final String existingPersonId = attribute(user, Identity.PERSON_ID_CLAIM);
    if (!fixture.hasPerson()) {
      if (existingPersonId != null) {
        people.deleteById(existingPersonId);
        cognitoClient.adminDeleteUserAttributes(
            AdminDeleteUserAttributesRequest.builder()
                .userPoolId(userPoolId)
                .username(username)
                .userAttributeNames(Identity.PERSON_ID_CLAIM)
                .build());
      }
      return Optional.empty();
    }

    final String personId;
    if (existingPersonId != null) {
      personId = existingPersonId;
    } else {
      // Only if PostConfirmation failed to set it. Claim first, then the Person - the order
      // PostConfirmationCreatePersonHandler explains.
      personId = IdAllocator.newId();
      updateAttributes(username, Map.of(Identity.PERSON_ID_CLAIM, personId));
    }
    people.put(
        new Person(
            personId,
            fixture.name(),
            List.of(sub),
            List.of(fixture.email()),
            fixture.isAdmin(),
            null,
            null,
            null,
            null));
    return Optional.of(personId);
  }

  private void signUp(final Fixture fixture) {
    cognitoClient.signUp(
        SignUpRequest.builder()
            .clientId(webappClientId)
            .username(fixture.email())
            .password(fixture.password())
            .userAttributes(AttributeType.builder().name("name").value(fixture.name()).build())
            .build());
    // Fires PostConfirmation synchronously: the Person and its claim exist when this returns.
    cognitoClient.adminConfirmSignUp(
        AdminConfirmSignUpRequest.builder()
            .userPoolId(userPoolId)
            .username(fixture.email())
            .build());
  }

  private void repairCognito(final Fixture fixture, final String username) {
    cognitoClient.adminSetUserPassword(
        AdminSetUserPasswordRequest.builder()
            .userPoolId(userPoolId)
            .username(username)
            .password(fixture.password())
            .permanent(true)
            .build());
    cognitoClient.adminEnableUser(
        AdminEnableUserRequest.builder().userPoolId(userPoolId).username(username).build());
    updateAttributes(
        username,
        Map.of(
            "email_verified",
            "true",
            "name",
            fixture.name(),
            "custom:class",
            fixture.isAdmin() ? "admin" : "standard"));
  }

  private void updateAttributes(final String username, final Map<String, String> attributes) {
    cognitoClient.adminUpdateUserAttributes(
        AdminUpdateUserAttributesRequest.builder()
            .userPoolId(userPoolId)
            .username(username)
            .userAttributes(
                attributes.entrySet().stream()
                    .map(e -> AttributeType.builder().name(e.getKey()).value(e.getValue()).build())
                    .toList())
            .build());
  }

  private Optional<AdminGetUserResponse> findUser(final String email) {
    try {
      return Optional.of(
          cognitoClient.adminGetUser(
              AdminGetUserRequest.builder().userPoolId(userPoolId).username(email).build()));
    } catch (final UserNotFoundException e) {
      return Optional.empty();
    }
  }

  private static String attribute(final AdminGetUserResponse user, final String name) {
    return user.userAttributes().stream()
        .filter(attribute -> name.equals(attribute.name()))
        .map(AttributeType::value)
        .filter(value -> !value.isBlank())
        .findFirst()
        .orElse(null);
  }

  private static String requireValue(final Map<String, String> env, final String name) {
    final String value = env.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required when its fixture's email is set.");
    }
    return value;
  }
}
