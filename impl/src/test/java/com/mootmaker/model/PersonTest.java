package com.mootmaker.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The preference fields are non-null over GraphQL but optional as DynamoDB attributes - every
 * Person written before the preferences feature existed lacks them. {@code isAdmin} is optional the
 * same way, for the same reason (every Person written before admin status existed lacks it) - and
 * defaults to {@code false} rather than a sentinel enum value, since it has no "never chosen" third
 * state to represent. {@link Person#fromItem} is the single point holding both guarantees up, so
 * these tests pin it directly rather than only reaching it through a handler.
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
    assertEquals(WeekStart.Monday, person.weekStart());
  }

  @Test
  void readsStoredPreferencesBack() {
    final Map<String, AttributeValue> item = new HashMap<>(itemWithoutPreferences());
    item.put("dateFormat", AttributeValue.builder().s("British").build());
    item.put("timeFormat", AttributeValue.builder().s("AmPm").build());
    item.put("weekStart", AttributeValue.builder().s("Sunday").build());

    final Person person = Person.fromItem(item);

    assertEquals(DateFormat.British, person.dateFormat());
    assertEquals(TimeFormat.AmPm, person.timeFormat());
    assertEquals(WeekStart.Sunday, person.weekStart());
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
    final Person person =
        new Person(
            "person-1",
            "Ada Lovelace",
            List.of("sub-1"),
            List.of("ada@example.com"),
            false,
            null,
            null,
            null);

    assertEquals(DateFormat.Iso, person.dateFormat());
    assertEquals(TimeFormat.TwentyFourHour, person.timeFormat());
    assertEquals(WeekStart.Monday, person.weekStart());
  }

  @Test
  void roundTripsThroughAnItemWithoutLosingPreferences() {
    final Person original =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1"),
            List.of("ada@example.com"),
            false,
            DateFormat.Usa,
            TimeFormat.AmPm,
            WeekStart.Sunday);

    final Person restored = Person.fromItem(original.toItem());

    assertEquals(original, restored);
  }

  @Test
  void exposesPreferencesOverGraphQlAsTheirLiteralEnumNames() {
    final Person person =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1"),
            List.of(),
            false,
            DateFormat.British,
            TimeFormat.AmPm,
            WeekStart.Sunday);

    final Map<String, Object> response = person.toResponseMap();

    assertEquals("British", response.get("dateFormat"));
    assertEquals("AmPm", response.get("timeFormat"));
    assertEquals("Sunday", response.get("weekStart"));
  }

  @Test
  void defaultsToNotAdminAndNoLinkedEmailsWhenTheAttributesAreAbsent() {
    final Person person = Person.fromItem(itemWithoutPreferences());

    assertFalse(person.isAdmin());
    assertTrue(person.cognitoEmails().isEmpty());
  }

  @Test
  void roundTripsAdminStatusAndLinkedEmailsThroughAnItem() {
    final Person original =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1", "sub-2"),
            List.of("ada@example.com", "ada@work.example.com"),
            true,
            null,
            null,
            null);

    final Person restored = Person.fromItem(original.toItem());

    assertEquals(original, restored);
    assertTrue(restored.isAdmin());
    assertEquals(List.of("ada@example.com", "ada@work.example.com"), restored.cognitoEmails());
  }

  @Test
  void exposesAdminStatusAndLinkedEmailsOverGraphQl() {
    final Person person =
        new Person(
            "person-1",
            "Ada",
            List.of("sub-1"),
            List.of("ada@example.com"),
            true,
            null,
            null,
            null);

    final Map<String, Object> response = person.toResponseMap();

    assertEquals(true, response.get("isAdmin"));
    assertEquals(List.of("ada@example.com"), response.get("linkedEmails"));
  }

  @Test
  void theSignUpConvenienceConstructorLinksOneAccountWithItsEmailAndIsNeverAdmin() {
    final Person person = new Person("person-1", "Ada", "sub-1", "ada@example.com");

    assertEquals(List.of("sub-1"), person.cognitoSubs());
    assertEquals(List.of("ada@example.com"), person.cognitoEmails());
    assertFalse(person.isAdmin());
  }
}
