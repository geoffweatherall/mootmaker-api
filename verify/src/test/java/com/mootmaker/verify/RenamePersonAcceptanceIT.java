package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceptance tests for {@code renamePerson} (the admin-invoked half of the old {@code updatePerson}
 * - see {@code RenamePersonHandler}'s own doc comment) and reading the change back via the {@code
 * people} query. The acceptance-test client authenticates as the M2M tooling client, which carries
 * the admin-equivalent OAuth scope (see the API README's "M2M tooling" section), so every person it
 * creates and renames here is a guest with no linked Cognito account - there's no way to exercise a
 * real self-rename (or a non-admin rejection) from this suite, since it has no real signed-in user
 * to authenticate as; those are covered by {@code UpdateMyNameHandlerTest}/{@code
 * RenamePersonHandlerTest} instead.
 */
class RenamePersonAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(RenamePersonAcceptanceIT.class);

  private static final String CREATE_PERSON_MUTATION =
      "mutation CreatePerson($name: String!) { createPerson(name: $name) { person { id name }"
          + " errors } }";
  private static final String RENAME_PERSON_MUTATION =
      "mutation RenamePerson($id: ID!, $name: String!) { renamePerson(id: $id, name: $name) {"
          + " person { id name } errors } }";

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
  void renamedPersonIsReturnedByPeopleQuery() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String originalName = faker.name().fullName();
    LOG.info("Creating person '{}'", originalName);
    final String personId = createPerson(originalName);

    final String newName = faker.name().fullName();
    LOG.info("Renaming person '{}' to '{}'", personId, newName);
    final JsonNode renameResult =
        client.execute(RENAME_PERSON_MUTATION, Map.of("id", personId, "name", newName));

    final JsonNode renamePersonPayload = renameResult.get("renamePerson");
    assertThat(renamePersonPayload.get("errors").size(), equalTo(0));
    assertThat(renamePersonPayload.get("person").get("name").asText(), equalTo(newName));

    LOG.info("Querying people to check the rename is reflected");
    final JsonNode peopleResult = client.execute("query { workspace { people { id name } } }");
    final List<String> names = new ArrayList<>();
    peopleResult
        .get("workspace")
        .get("people")
        .forEach(
            person -> {
              if (person.get("id").asText().equals(personId)) {
                names.add(person.get("name").asText());
              }
            });
    assertThat(names, hasItem(newName));
    LOG.info("Person '{}' was successfully renamed to '{}'", personId, newName);
  }

  @Test
  void renamingAMissingPersonIsRejected() {
    LOG.info("Checking a rename of a non-existent person id is rejected");
    final JsonNode renameResult =
        client.execute(
            RENAME_PERSON_MUTATION,
            Map.of("id", "00000000-0000-0000-0000-000000000000", "name", "Anything"));

    final JsonNode renamePersonPayload = renameResult.get("renamePerson");
    assertThat(renamePersonPayload.get("person").isNull(), is(true));
    assertThat(
        renamePersonPayload.get("errors").get(0).asText(),
        equalTo(PersonError.PersonNotFound.name()));
  }

  @Test
  void renamingWithABlankNameIsRejected() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String personId = createPerson(faker.name().fullName());

    LOG.info("Checking a blank name is rejected on rename");
    final JsonNode renameResult =
        client.execute(RENAME_PERSON_MUTATION, Map.of("id", personId, "name", "   "));

    final JsonNode renamePersonPayload = renameResult.get("renamePerson");
    assertThat(renamePersonPayload.get("person").isNull(), is(true));
    assertThat(
        renamePersonPayload.get("errors").get(0).asText(),
        equalTo(PersonError.NameRequired.name()));
  }
}
