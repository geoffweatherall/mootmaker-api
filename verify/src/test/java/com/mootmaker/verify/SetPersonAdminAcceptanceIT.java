package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceptance tests for {@code setPersonAdmin}. Limited the same way {@code
 * RenamePersonAcceptanceIT} is: the acceptance-test client authenticates as the M2M tooling client,
 * which has no Cognito user (and therefore no {@code custom:personId}) of its own, and every person
 * it creates via {@code createPerson} is a guest with no linked Cognito account. That means neither
 * a successful grant (needs a linked account) nor the self-revocation guard (needs the caller to
 * have their own linked Person) can be exercised from this suite - both are covered by {@code
 * SetPersonAdminHandlerTest} instead. What this M2M client *can* prove against a real deployed
 * environment is the guest-rejection path itself, which is exactly the scenario this suite's own
 * guest-only people naturally produce.
 */
class SetPersonAdminAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(SetPersonAdminAcceptanceIT.class);

  private static final String CREATE_PERSON_MUTATION =
      "mutation CreatePerson($name: String!) { createPerson(name: $name) { person { id } errors }"
          + " }";
  private static final String SET_PERSON_ADMIN_MUTATION =
      "mutation SetPersonAdmin($id: ID!, $isAdmin: Boolean!) { setPersonAdmin(id: $id, isAdmin:"
          + " $isAdmin) { person { id isAdmin } cognitoSyncFailed errors } }";

  private static GraphQlClient client;
  private static Faker faker;

  @BeforeAll
  static void setUpClient() {
    client = GraphQlClient.fromEnvironment();
    faker = new Faker();
  }

  private static String createPerson(final String name) {
    final JsonNode result = client.execute(CREATE_PERSON_MUTATION, Map.of("name", name));
    return result.get("createPerson").get("person").get("id").asText();
  }

  @Test
  void refusesToGrantAdminToAGuestWithNoLinkedCognitoAccount() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String personId = createPerson(faker.name().fullName());

    LOG.info("Attempting to grant admin to guest person '{}'", personId);
    final JsonNode result =
        client.execute(SET_PERSON_ADMIN_MUTATION, Map.of("id", personId, "isAdmin", true));

    final JsonNode payload = result.get("setPersonAdmin");
    assertThat(payload.get("person").isNull(), equalTo(true));
    assertThat(payload.get("errors").get(0).asText(), equalTo(PersonError.NoLinkedAccount.name()));
    assertThat(payload.get("cognitoSyncFailed").asBoolean(), equalTo(false));

    LOG.info("Querying people to check the guest was not promoted");
    final JsonNode peopleResult = client.execute("query { workspace { people { id isAdmin } } }");
    final JsonNode people = peopleResult.get("workspace").get("people");
    boolean found = false;
    for (final JsonNode person : people) {
      if (person.get("id").asText().equals(personId)) {
        found = true;
        assertThat(person.get("isAdmin").asBoolean(), equalTo(false));
      }
    }
    assertThat("the created person must still be present", found, equalTo(true));
  }

  @Test
  void returnsPersonNotFoundForAMissingId() {
    LOG.info("Checking setPersonAdmin on a non-existent person id is rejected");
    final JsonNode result =
        client.execute(
            SET_PERSON_ADMIN_MUTATION,
            Map.of("id", "00000000-0000-0000-0000-000000000000", "isAdmin", true));

    assertThat(
        result.get("setPersonAdmin").get("errors").get(0).asText(),
        equalTo(PersonError.PersonNotFound.name()));
  }
}
