package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import module java.base;

import com.mootmaker.model.Person;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import org.junit.jupiter.api.Test;

class PreSignUpNameCollisionHandlerTest {

  private static Map<String, Object> signUpEvent(final String name) {
    final Map<String, Object> userAttributes = new HashMap<>();
    userAttributes.put("name", name);
    userAttributes.put("email", "willy@example.com");
    final Map<String, Object> request = new HashMap<>();
    request.put("userAttributes", userAttributes);
    final Map<String, Object> event = new HashMap<>();
    event.put("triggerSource", "PreSignUp_SignUp");
    event.put("request", request);
    return event;
  }

  private static FakeDynamoDbClient clientWithPerson(final String name) {
    final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
    fakeClient.tables.put(
        "People", new ArrayList<>(List.of(new Person("person-1", name).toItem())));
    return fakeClient;
  }

  @Test
  void allowsSignUpWhenNoExistingPersonSharesTheName() {
    final FakeDynamoDbClient fakeClient = clientWithPerson("Alan Turing");
    final PreSignUpNameCollisionHandler handler =
        new PreSignUpNameCollisionHandler(fakeClient, "People");

    final Map<String, Object> event = signUpEvent("Willy Wombat");
    final Map<String, Object> result = handler.handleRequest(event, null);

    assertSame(event, result, "Cognito requires the trigger to return the event unmodified");
  }

  @Test
  void rejectsSignUpWhenAnExistingPersonHasTheExactSameName() {
    final FakeDynamoDbClient fakeClient = clientWithPerson("Willy Wombat");
    final PreSignUpNameCollisionHandler handler =
        new PreSignUpNameCollisionHandler(fakeClient, "People");

    final IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> handler.handleRequest(signUpEvent("Willy Wombat"), null));
    assertEquals(PreSignUpNameCollisionHandler.NAME_ALREADY_EXISTS_MESSAGE, thrown.getMessage());
  }

  @Test
  void rejectsSignUpIgnoringCaseAndSurroundingWhitespace() {
    final FakeDynamoDbClient fakeClient = clientWithPerson("Willy Wombat");
    final PreSignUpNameCollisionHandler handler =
        new PreSignUpNameCollisionHandler(fakeClient, "People");

    assertThrows(
        IllegalArgumentException.class,
        () -> handler.handleRequest(signUpEvent("  willy WOMBAT"), null));
  }

  @Test
  void ignoresEventsFromOtherTriggerSources() {
    final FakeDynamoDbClient fakeClient = clientWithPerson("Willy Wombat");
    final PreSignUpNameCollisionHandler handler =
        new PreSignUpNameCollisionHandler(fakeClient, "People");

    final Map<String, Object> event = signUpEvent("Willy Wombat");
    event.put("triggerSource", "PreSignUp_AdminCreateUser");

    final Map<String, Object> result = handler.handleRequest(event, null);

    assertSame(event, result, "not a real sign-up call, so the collision check is skipped");
  }
}
