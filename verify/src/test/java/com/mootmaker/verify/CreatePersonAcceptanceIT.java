package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import module java.base;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Acceptance test for creating a person and reading it back via the {@code people} query. */
class CreatePersonAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(CreatePersonAcceptanceIT.class);

  private static GraphQlClient client;
  private static Faker faker;

  @BeforeAll
  static void setUpClient() {
    client = GraphQlClient.fromEnvironment();
    faker = new Faker();
  }

  @Test
  void createdPersonIsReturnedByPeopleQuery() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String personName = faker.name().fullName();
    LOG.info("Creating person '{}'", personName);
    final JsonNode createResult =
        client.execute(
            "mutation CreatePerson($person: PersonInput!) { createPerson(person: $person) { person"
                + " { id name } errors } }",
            Map.of("person", Map.of("name", personName)));

    final String createdId = createResult.get("createPerson").get("person").get("id").asText();
    LOG.info("Created person with id '{}'", createdId);
    assertThat(
        createResult.get("createPerson").get("person").get("name").asText(), equalTo(personName));

    LOG.info("Querying people to check the created person is returned");
    final JsonNode peopleResult = client.execute("query { workspace { people { id name } } }");
    final List<String> peopleIds = new ArrayList<>();
    peopleResult
        .get("workspace")
        .get("people")
        .forEach(person -> peopleIds.add(person.get("id").asText()));

    // Not an exact-size check: reset only clears people with no linked Cognito account, so a
    // shared environment may legitimately still have other (real, signed-up) people present.
    assertThat(peopleIds, hasItem(createdId));
    LOG.info("Person '{}' was successfully returned by the people query", personName);
  }
}
