package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminConfirmSignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserAttributesResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminEnableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminEnableUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.SignUpResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

class FixtureUsersTest {

  private static final String USER_POOL_ID = "pool-1";
  private static final String PEOPLE_TABLE = "People";

  private final FakeDynamoDbClient dynamo = new FakeDynamoDbClient();
  private final PersonRepository people = new PersonRepository(dynamo, PEOPLE_TABLE);
  private final FakePool pool = new FakePool();
  private final FixtureUsers fixtureUsers =
      new FixtureUsers(pool, people, USER_POOL_ID, "webapp-client");

  private static final FixtureUsers.Fixture ADMIN =
      new FixtureUsers.Fixture("admin", "admin@mail.test", "E2E Admin", "Admin-pw-1!");
  private static final FixtureUsers.Fixture STANDARD =
      new FixtureUsers.Fixture("standard", "standard@mail.test", "E2E Standard", "Std-pw-1!");
  private static final FixtureUsers.Fixture NO_PERSON =
      new FixtureUsers.Fixture("no-person", "none@mail.test", "E2E No Person", "None-pw-1!");

  @Test
  void readsEachConfiguredRoleFromTheEnvironment() {
    final Map<String, String> env =
        Map.of(
            "FIXTURE_USER_ADMIN_EMAIL", "a@x",
            "FIXTURE_USER_ADMIN_NAME", "A",
            "FIXTURE_USER_ADMIN_PASSWORD", "pa",
            "FIXTURE_USER_NO_PERSON_EMAIL", "n@x",
            "FIXTURE_USER_NO_PERSON_NAME", "N",
            "FIXTURE_USER_NO_PERSON_PASSWORD", "pn");

    assertEquals(
        List.of(
            new FixtureUsers.Fixture("admin", "a@x", "A", "pa"),
            new FixtureUsers.Fixture("no-person", "n@x", "N", "pn")),
        FixtureUsers.fromEnvironment(env));
  }

  @Test
  void noneConfiguredMeansNoFixtures() {
    assertEquals(List.of(), FixtureUsers.fromEnvironment(Map.of()));
  }

  @Test
  void missingAccountsAreCreatedThroughSignUpAndPostConfirmation() {
    final Set<String> written = fixtureUsers.ensureAndRepair(List.of(ADMIN, STANDARD, NO_PERSON));

    assertEquals(List.of("admin@mail.test", "standard@mail.test", "none@mail.test"), pool.signUps);
    assertEquals(2, written.size());

    final Person admin = personFor(ADMIN);
    assertEquals("E2E Admin", admin.name());
    assertTrue(admin.isAdmin());
    assertEquals("admin", pool.attribute("admin@mail.test", "custom:class"));

    final Person standard = personFor(STANDARD);
    assertFalse(standard.isAdmin());
    assertEquals("standard", pool.attribute("standard@mail.test", "custom:class"));
    assertEquals(List.of("standard@mail.test"), standard.cognitoEmails());

    // Sign-up made one; the no-person fixture must end up with neither the Person nor the claim.
    assertNull(pool.attribute("none@mail.test", Identity.PERSON_ID_CLAIM));
    assertEquals(2, people.listAll().size());
  }

  @Test
  void existingAccountsAreRepairedNotRecreated() {
    fixtureUsers.ensureAndRepair(List.of(ADMIN, STANDARD));
    final String standardPersonId = pool.attribute("standard@mail.test", Identity.PERSON_ID_CLAIM);

    // Damage it the ways a careless test could: rename, promote, change preferences and avatar,
    // change the password, disable the account.
    final Person original = personFor(STANDARD);
    people.put(
        new Person(
            original.id(),
            "Renamed by a test",
            original.cognitoSubs(),
            original.cognitoEmails(),
            true,
            com.mootmaker.model.DateFormat.values()[1],
            com.mootmaker.model.TimeFormat.values()[1],
            com.mootmaker.model.WeekStart.values()[1],
            "v1/" + original.id() + "/abc"));
    pool.setAttribute("standard@mail.test", "custom:class", "admin");
    pool.setAttribute("standard@mail.test", "name", "Renamed by a test");
    pool.passwords.put("standard@mail.test", "changed");
    pool.disabled.add("standard@mail.test");
    pool.signUps.clear();

    fixtureUsers.ensureAndRepair(List.of(ADMIN, STANDARD));

    assertEquals(List.of(), pool.signUps, "an existing account must be repaired, not re-created");
    final Person repaired = personFor(STANDARD);
    assertEquals(standardPersonId, repaired.id(), "same Person, same id");
    assertEquals(
        new Person(
            standardPersonId,
            "E2E Standard",
            original.cognitoSubs(),
            List.of("standard@mail.test"),
            false,
            null,
            null,
            null,
            null),
        repaired);
    assertEquals("standard", pool.attribute("standard@mail.test", "custom:class"));
    assertEquals("E2E Standard", pool.attribute("standard@mail.test", "name"));
    assertEquals("Std-pw-1!", pool.passwords.get("standard@mail.test"));
    assertFalse(pool.disabled.contains("standard@mail.test"));
  }

  @Test
  void aPersonThatWentMissingIsPutBackUnderTheClaimedId() {
    fixtureUsers.ensureAndRepair(List.of(STANDARD));
    final String personId = pool.attribute("standard@mail.test", Identity.PERSON_ID_CLAIM);
    people.deleteById(personId);

    fixtureUsers.ensureAndRepair(List.of(STANDARD));

    assertEquals(personId, personFor(STANDARD).id());
  }

  private Person personFor(final FixtureUsers.Fixture fixture) {
    final String personId = pool.attribute(fixture.email(), Identity.PERSON_ID_CLAIM);
    return people.findById(personId).orElseThrow();
  }

  /**
   * An in-memory user pool keyed by email. Confirming a sign-up runs the real
   * PostConfirmationCreatePersonHandler against the same fake DynamoDB, so the test exercises the
   * same Person creation a real sign-up gets.
   */
  private final class FakePool implements CognitoIdentityProviderClient {

    final List<String> signUps = new ArrayList<>();
    final Map<String, Map<String, String>> attributes = new LinkedHashMap<>();
    final Map<String, String> passwords = new HashMap<>();
    final Set<String> disabled = new HashSet<>();

    String attribute(final String email, final String name) {
      return attributes.get(email).get(name);
    }

    void setAttribute(final String email, final String name, final String value) {
      attributes.get(email).put(name, value);
    }

    private String emailFor(final String username) {
      if (attributes.containsKey(username)) {
        return username;
      }
      return attributes.entrySet().stream()
          .filter(entry -> username.equals(entry.getValue().get("sub")))
          .map(Map.Entry::getKey)
          .findFirst()
          .orElseThrow(() -> UserNotFoundException.builder().message(username).build());
    }

    @Override
    public SignUpResponse signUp(final SignUpRequest request) {
      signUps.add(request.username());
      final Map<String, String> attrs = new HashMap<>();
      attrs.put("sub", "sub-" + request.username());
      attrs.put("email", request.username());
      request.userAttributes().forEach(a -> attrs.put(a.name(), a.value()));
      attributes.put(request.username(), attrs);
      passwords.put(request.username(), request.password());
      return SignUpResponse.builder().userSub(attrs.get("sub")).build();
    }

    @Override
    public AdminConfirmSignUpResponse adminConfirmSignUp(final AdminConfirmSignUpRequest request) {
      final Map<String, String> attrs = attributes.get(emailFor(request.username()));
      final Map<String, Object> event = new HashMap<>();
      event.put("triggerSource", "PostConfirmation_ConfirmSignUp");
      event.put("userPoolId", USER_POOL_ID);
      event.put("request", Map.of("userAttributes", new HashMap<String, Object>(attrs)));
      new PostConfirmationCreatePersonHandler(dynamo, this, PEOPLE_TABLE)
          .handleRequest(event, null);
      return AdminConfirmSignUpResponse.builder().build();
    }

    @Override
    public AdminGetUserResponse adminGetUser(final AdminGetUserRequest request) {
      final String email = emailFor(request.username());
      return AdminGetUserResponse.builder()
          .username(attributes.get(email).get("sub"))
          .userAttributes(
              attributes.get(email).entrySet().stream()
                  .map(e -> AttributeType.builder().name(e.getKey()).value(e.getValue()).build())
                  .toList())
          .build();
    }

    @Override
    public AdminSetUserPasswordResponse adminSetUserPassword(
        final AdminSetUserPasswordRequest request) {
      passwords.put(emailFor(request.username()), request.password());
      return AdminSetUserPasswordResponse.builder().build();
    }

    @Override
    public AdminEnableUserResponse adminEnableUser(final AdminEnableUserRequest request) {
      disabled.remove(emailFor(request.username()));
      return AdminEnableUserResponse.builder().build();
    }

    @Override
    public AdminUpdateUserAttributesResponse adminUpdateUserAttributes(
        final AdminUpdateUserAttributesRequest request) {
      final Map<String, String> attrs = attributes.get(emailFor(request.username()));
      request.userAttributes().forEach(a -> attrs.put(a.name(), a.value()));
      return AdminUpdateUserAttributesResponse.builder().build();
    }

    @Override
    public AdminDeleteUserAttributesResponse adminDeleteUserAttributes(
        final AdminDeleteUserAttributesRequest request) {
      final Map<String, String> attrs = attributes.get(emailFor(request.username()));
      request.userAttributeNames().forEach(attrs::remove);
      return AdminDeleteUserAttributesResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "cognito-idp";
    }

    @Override
    public void close() {}
  }
}
