package com.mootmaker.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The preference fields are non-null over GraphQL but optional as DynamoDB attributes - every
 * Person written before the preferences feature existed lacks them. {@link Person#fromItem} is the
 * single point holding that guarantee up, so these tests pin it directly rather than only reaching
 * it through a handler.
 */
class PersonTest {

    private static Map<String, AttributeValue> itemWithoutPreferences() {
        return Map.of(
                "id", AttributeValue.builder().s("person-1").build(),
                "name", AttributeValue.builder().s("Ada Lovelace").build());
    }

    @Test
    void defaultsToIsoAndTwentyFourHourWhenTheAttributesAreAbsent() {
        final Person person = Person.fromItem(itemWithoutPreferences());

        assertEquals(DateFormat.Iso, person.dateFormat());
        assertEquals(TimeFormat.TwentyFourHour, person.timeFormat());
    }

    @Test
    void readsStoredPreferencesBack() {
        final Map<String, AttributeValue> item = new HashMap<>(itemWithoutPreferences());
        item.put("dateFormat", AttributeValue.builder().s("British").build());
        item.put("timeFormat", AttributeValue.builder().s("AmPm").build());

        final Person person = Person.fromItem(item);

        assertEquals(DateFormat.British, person.dateFormat());
        assertEquals(TimeFormat.AmPm, person.timeFormat());
    }

    @Test
    void fallsBackToTheDefaultForAnUnrecognisedStoredValue() {
        final Map<String, AttributeValue> item = new HashMap<>(itemWithoutPreferences());
        item.put("dateFormat", AttributeValue.builder().s("Klingon").build());

        final Person person = Person.fromItem(item);

        assertEquals(DateFormat.Iso, person.dateFormat());
    }

    @Test
    void normalisesNullPreferencesPassedToTheConstructor() {
        final Person person = new Person("person-1", "Ada Lovelace", "sub-1", null, null);

        assertEquals(DateFormat.Iso, person.dateFormat());
        assertEquals(TimeFormat.TwentyFourHour, person.timeFormat());
    }

    @Test
    void roundTripsThroughAnItemWithoutLosingPreferences() {
        final Person original = new Person("person-1", "Ada", "sub-1", DateFormat.Usa, TimeFormat.AmPm);

        final Person restored = Person.fromItem(original.toItem());

        assertEquals(original, restored);
    }

    @Test
    void exposesPreferencesOverGraphQlAsTheirLiteralEnumNames() {
        final Person person = new Person("person-1", "Ada", "sub-1", DateFormat.British, TimeFormat.AmPm);

        final Map<String, Object> response = person.toResponseMap();

        assertEquals("British", response.get("dateFormat"));
        assertEquals("AmPm", response.get("timeFormat"));
    }
}
