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
 * Acceptance tests for {@code deletePerson}. The M2M acceptance-test client has no {@code
 * custom:personId} of its own, so neither {@code CannotDeleteSelf} nor {@code ReservedAccount} can
 * be exercised from this suite (no real linked/reserved account to target) - both are covered by
 * {@code DeletePersonHandlerTest} instead. What this suite proves against a real deployed
 * environment is the parts a unit test can't: the happy-path delete, and the meeting cascade
 * actually reaching a real AppSync/DynamoDB `createMeeting` booking.
 */
class DeletePersonAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(DeletePersonAcceptanceIT.class);

  private static final String CREATE_PERSON_MUTATION =
      "mutation CreatePerson($name: String!) { createPerson(name: $name) { person { id } errors }"
          + " }";
  private static final String DELETE_PERSON_MUTATION =
      "mutation DeletePerson($id: ID!) { deletePerson(id: $id) { people { id } errors } }";

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
  void deletedPersonIsNoLongerReturnedByPeopleQuery() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final String personId = createPerson(faker.name().fullName());

    LOG.info("Deleting person '{}'", personId);
    final JsonNode deleteResult = client.execute(DELETE_PERSON_MUTATION, Map.of("id", personId));
    assertThat(deleteResult.get("deletePerson").get("errors").size(), equalTo(0));

    LOG.info("Querying people to check the person is gone");
    final JsonNode peopleResult = client.execute("query { workspace { people { id } } }");
    final List<String> peopleIds = new ArrayList<>();
    peopleResult
        .get("workspace")
        .get("people")
        .forEach(person -> peopleIds.add(person.get("id").asText()));
    assertThat(peopleIds.contains(personId), equalTo(false));
  }

  @Test
  void cancelsAnUpcomingMeetingTheDeletedPersonOrganises() {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();

    final JsonNode roomResult =
        client.execute(
            "mutation CreateRoom($room: RoomInput!) { createRoom(room: $room) { room { id }"
                + " errors } }",
            Map.of("room", Map.of("name", faker.address().city() + " Room", "capacity", 5)));
    final String roomId = roomResult.get("createRoom").get("room").get("id").asText();
    final String organiserId = createPerson(faker.name().fullName());

    LOG.info("Booking a real meeting organised by person '{}'", organiserId);
    final JsonNode meetingResult =
        client.execute(
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) {"
                + " meeting { id } errors } }",
            Map.of(
                "meeting",
                Map.of(
                    "roomId", roomId,
                    "organiserId", organiserId,
                    "attendeeIds", List.of(),
                    "subject", faker.company().catchPhrase(),
                    "startTime", BookableDates.at("10:00:00"),
                    "endTime", BookableDates.at("10:30:00"))));
    final String meetingId = meetingResult.get("createMeeting").get("meeting").get("id").asText();

    LOG.info("Deleting the organiser '{}'", organiserId);
    client.execute(DELETE_PERSON_MUTATION, Map.of("id", organiserId));

    LOG.info("Checking the meeting no longer resolves");
    final JsonNode meetingQuery =
        client.execute(
            "query Meeting($id: ID!) { meeting(id: $id) { id } }", Map.of("id", meetingId));
    assertThat(meetingQuery.get("meeting").isNull(), equalTo(true));
  }

  @Test
  void returnsPersonNotFoundForAMissingId() {
    LOG.info("Checking a delete of a non-existent person id is rejected");
    final JsonNode deleteResult =
        client.execute(
            DELETE_PERSON_MUTATION, Map.of("id", "00000000-0000-0000-0000-000000000000"));

    assertThat(
        deleteResult.get("deletePerson").get("errors").get(0).asText(),
        equalTo(PersonError.PersonNotFound.name()));
  }
}
