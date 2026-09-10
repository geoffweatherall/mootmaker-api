package com.mootmaker.verify;

import com.fasterxml.jackson.databind.JsonNode;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import module java.base;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

/**
 * One client books a meeting; another client, already subscribed, is told which day changed.
 *
 * <p>This exercises the whole chain against a real deployed environment - the resolver Lambda, its
 * SigV4-signed call to the {@code @aws_iam}-only publish field, {@code @aws_subscribe}, and the
 * realtime transport - none of which a unit test can reach. It is the only place the IAM grant and
 * the type-level auth directives are actually proven; both fail SILENTLY, delivering nothing to the
 * subscriber while the publisher sees success.
 *
 * <p><b>Both directions are asserted</b>, and that is not thoroughness for its own sake. A rejected
 * booking must broadcast nothing, and a test that only checked "the broadcast I expected arrived"
 * would pass just as happily against an implementation that broadcasts on every call - which is
 * exactly what subscribing to {@code createMeeting} directly would have done.
 */
class DaysInvalidatedAcceptanceIT {

    private static final Logger LOG = LoggerFactory.getLogger(DaysInvalidatedAcceptanceIT.class);

    private static final String SUBSCRIPTION = "subscription S { daysInvalidated { dates } }";
    private static final String CREATE_MEETING =
            "mutation CreateMeeting($meeting: MeetingInput!) { createMeeting(meeting: $meeting) { meeting { id } errors } }";
    private static final String CREATE_MEETINGS =
            "mutation CreateMeetings($date: String!, $meetings: [MeetingInput!]!) {"
                    + " createMeetings(date: $date, meetings: $meetings) { failures { index errors } } }";

    /**
     * Generous on purpose. The publish is an extra network hop made by a Lambda that may be cold,
     * and a broadcast that is merely slow is not the failure this test is looking for.
     */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(20);

    /**
     * How long to wait before concluding nothing was broadcast. Shorter than DELIVERY_TIMEOUT
     * because it is paid in full by every run: this wait always expires, it never completes early.
     */
    private static final Duration SILENCE_TIMEOUT = Duration.ofSeconds(8);

    private static GraphQlClient client;
    private static String roomId;
    private static String organiserId;
    private static String endpoint;
    private static String accessToken;

    @BeforeAll
    static void setUp() {
        client = GraphQlClient.fromEnvironment();
        endpoint = System.getenv("GRAPHQL_API_URL");
        accessToken = client.accessTokenForSubscriptions();
        final Faker faker = new Faker();
        DatabaseReset.reset();
        roomId = client.execute("mutation($room: RoomInput!){ createRoom(room:$room){ room { id } errors } }",
                Map.of("room", Map.of("name", faker.address().city() + " Room", "capacity", 8)))
                .get("createRoom").get("room").get("id").asText();
        organiserId = client.execute("mutation($person: PersonInput!){ createPerson(person:$person){ person { id } errors } }",
                Map.of("person", Map.of("name", faker.name().fullName())))
                .get("createPerson").get("person").get("id").asText();
    }

    /** A date comfortably inside the bookable window, distinct per test so runs cannot collide. */
    private static String bookableDate(final int offsetDays) {
        return LocalDate.now().plusDays(offsetDays).toString();
    }

    private static Map<String, Object> meeting(final String date, final String from, final String to) {
        return Map.of("roomId", roomId, "organiserId", organiserId, "attendeeIds", List.of(),
                "subject", "Broadcast coverage",
                "startTime", date + "T" + from, "endTime", date + "T" + to);
    }

    private static List<String> datesFrom(final JsonNode payload) {
        final List<String> dates = new ArrayList<>();
        payload.get("data").get("daysInvalidated").get("dates").forEach(d -> dates.add(d.asText()));
        return dates;
    }

    @Test
    @DisplayName("a booking made by one client is broadcast to another")
    void broadcastsACreatedMeeting() throws Exception {
        final String date = bookableDate(31);
        try (AppSyncSubscription subscription = AppSyncSubscription.open(SUBSCRIPTION, endpoint, accessToken)) {
            subscription.awaitReady();
            LOG.info("Subscribed; booking {} from a second client", date);

            final JsonNode result = client.execute(CREATE_MEETING, Map.of("meeting", meeting(date, "09:00:00", "09:30:00")));
            assertThat("precondition: the booking must have been accepted",
                    result.get("createMeeting").get("errors").size(), equalTo(0));

            final JsonNode broadcast = subscription.awaitMessage(DELIVERY_TIMEOUT);
            assertThat("no broadcast arrived within " + DELIVERY_TIMEOUT + ". Protocol errors: "
                    + subscription.protocolErrors(), broadcast, org.hamcrest.Matchers.notNullValue());
            assertThat(datesFrom(broadcast), contains(date));
            assertThat("the transport reported no errors", subscription.protocolErrors(), empty());
        }
    }

    @Test
    @DisplayName("a rejected booking is broadcast to nobody")
    void broadcastsNothingForARejectedBooking() throws Exception {
        final String date = bookableDate(33);
        try (AppSyncSubscription subscription = AppSyncSubscription.open(SUBSCRIPTION, endpoint, accessToken)) {
            subscription.awaitReady();
            LOG.info("Subscribed; attempting a booking that must fail");

            final JsonNode result = client.execute(CREATE_MEETING,
                    Map.of("meeting", meeting(date, "09:00:00", "09:30:00").entrySet().stream()
                            .collect(HashMap::new,
                                    (m, e) -> m.put(e.getKey(), e.getKey().equals("roomId") ? "no-such-room" : e.getValue()),
                                    HashMap::putAll)));
            assertThat("precondition: the booking must have been REJECTED",
                    result.get("createMeeting").get("errors").size(), org.hamcrest.Matchers.greaterThan(0));

            assertThat("a booking that was refused changed no day, so nothing may be broadcast",
                    subscription.receivedNothingWithin(SILENCE_TIMEOUT), equalTo(true));
        }
    }

    @Test
    @DisplayName("bulk creation wakes subscribers once, not once per meeting")
    void broadcastsOncePerBulkCall() throws Exception {
        final String date = bookableDate(35);
        try (AppSyncSubscription subscription = AppSyncSubscription.open(SUBSCRIPTION, endpoint, accessToken)) {
            subscription.awaitReady();
            LOG.info("Subscribed; bulk-creating four meetings on {}", date);

            final JsonNode result = client.execute(CREATE_MEETINGS, Map.of("date", date, "meetings", List.of(
                    meeting(date, "09:00:00", "09:30:00"),
                    meeting(date, "10:00:00", "10:30:00"),
                    meeting(date, "11:00:00", "11:30:00"),
                    meeting(date, "12:00:00", "12:30:00"))));
            assertThat("precondition: every meeting must have been accepted",
                    result.get("createMeetings").get("failures").size(), equalTo(0));

            final JsonNode first = subscription.awaitMessage(DELIVERY_TIMEOUT);
            assertThat("no broadcast arrived. Protocol errors: " + subscription.protocolErrors(),
                    first, org.hamcrest.Matchers.notNullValue());
            assertThat(datesFrom(first), contains(date));

            // The point of day-scoped bulk creation: four meetings, one day, ONE wake-up.
            assertThat("four meetings on one date must not produce four broadcasts",
                    subscription.receivedNothingWithin(SILENCE_TIMEOUT), equalTo(true));
        }
    }
}
