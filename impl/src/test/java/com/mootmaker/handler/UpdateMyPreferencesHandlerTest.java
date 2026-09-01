package com.mootmaker.handler;

import com.mootmaker.model.DateFormat;
import com.mootmaker.model.Person;
import com.mootmaker.model.TimeFormat;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateMyPreferencesHandlerTest {

    private static Map<String, Object> event(final String sub, final String dateFormat, final String timeFormat) {
        return Map.of(
                "identity", Map.of("sub", sub),
                "arguments", Map.of("preferences", Map.of("dateFormat", dateFormat, "timeFormat", timeFormat)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> personOf(final Object result) {
        return (Map<String, Object>) ((Map<String, Object>) result).get("person");
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorsOf(final Object result) {
        return (List<String>) ((Map<String, Object>) result).get("errors");
    }

    private static FakeDynamoDbClient clientWith(final Person... people) {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        fakeClient.tables.put("People",
                new ArrayList<>(Arrays.stream(people).map(Person::toItem).toList()));
        return fakeClient;
    }

    @Test
    void setsBothPreferencesOnTheCallersOwnPerson() {
        final FakeDynamoDbClient fakeClient = clientWith(new Person("person-1", "Ada Lovelace", "sub-1"));
        final UpdateMyPreferencesHandler handler = new UpdateMyPreferencesHandler(fakeClient, "People");

        final Object result = handler.handleRequest(event("sub-1", "British", "AmPm"), null);

        assertTrue(errorsOf(result).isEmpty());
        assertEquals("British", personOf(result).get("dateFormat"));
        assertEquals("AmPm", personOf(result).get("timeFormat"));
    }

    @Test
    void persistsThePreferencesSoTheySurviveAReRead() {
        final FakeDynamoDbClient fakeClient = clientWith(new Person("person-1", "Ada Lovelace", "sub-1"));
        final UpdateMyPreferencesHandler handler = new UpdateMyPreferencesHandler(fakeClient, "People");

        handler.handleRequest(event("sub-1", "Usa", "AmPm"), null);

        final Person stored = Person.fromItem(fakeClient.tables.get("People").getFirst());
        assertEquals(DateFormat.Usa, stored.dateFormat());
        assertEquals(TimeFormat.AmPm, stored.timeFormat());
    }

    /**
     * PutItem replaces the whole item, so a preferences-only update that rebuilt the Person from
     * its arguments alone would wipe the caller's name and unlink their Cognito login - the mirror
     * image of the hazard UpdatePersonHandler guards against in the other direction.
     */
    @Test
    void carriesNameAndCognitoSubForwardUntouched() {
        final FakeDynamoDbClient fakeClient = clientWith(new Person("person-1", "Ada Lovelace", "sub-1"));
        final UpdateMyPreferencesHandler handler = new UpdateMyPreferencesHandler(fakeClient, "People");

        handler.handleRequest(event("sub-1", "British", "AmPm"), null);

        final Map<String, AttributeValue> stored = fakeClient.tables.get("People").getFirst();
        assertEquals("Ada Lovelace", stored.get("name").s());
        assertEquals("sub-1", stored.get("cognitoSub").s());
    }

    @Test
    void reportsNoLinkedPersonWhenTheCallerHasNoPersonOfTheirOwn() {
        final FakeDynamoDbClient fakeClient = clientWith(new Person("person-1", "Ada Lovelace", "sub-1"));
        final UpdateMyPreferencesHandler handler = new UpdateMyPreferencesHandler(fakeClient, "People");

        final Object result = handler.handleRequest(event("sub-unlinked", "British", "AmPm"), null);

        assertNull(personOf(result));
        assertEquals(List.of("NoLinkedPerson"), errorsOf(result));
    }

    /**
     * Self-only with no admin override: an admin editing someone else's display preference is not
     * a thing this mutation can express, because there is no id argument at all. The caller's own
     * sub is the only target, so an admin calling it just sets their own.
     */
    @Test
    void onlyEverTouchesTheCallersOwnPerson() {
        final FakeDynamoDbClient fakeClient = clientWith(
                new Person("person-1", "Ada Lovelace", "sub-1"),
                new Person("person-2", "Alan Turing", "sub-2"));
        final UpdateMyPreferencesHandler handler = new UpdateMyPreferencesHandler(fakeClient, "People");

        handler.handleRequest(event("sub-2", "Usa", "AmPm"), null);

        final Map<String, List<Map<String, AttributeValue>>> tables = fakeClient.tables;
        final Person untouched = tables.get("People").stream()
                .map(Person::fromItem)
                .filter(person -> "person-1".equals(person.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(DateFormat.Iso, untouched.dateFormat());
        assertEquals(TimeFormat.TwentyFourHour, untouched.timeFormat());
    }

    @Test
    void rejectsUnauthenticatedRequests() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final UpdateMyPreferencesHandler handler = new UpdateMyPreferencesHandler(fakeClient, "People");

        assertThrows(IllegalStateException.class, () -> handler.handleRequest(Map.of(), null));
    }
}
