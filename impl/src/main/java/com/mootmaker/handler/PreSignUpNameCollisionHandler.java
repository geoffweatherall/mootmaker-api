package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Cognito PreSignUp trigger: rejects a sign-up outright when its {@code name} attribute collides
 * (case/whitespace-insensitively) with an existing Person - see mootmaker-api#70. A separate
 * trigger from {@link PostConfirmationCreatePersonHandler}, deliberately: that one already explains
 * why it can't itself reject a sign-up (the account is already confirmed by the time it runs, and
 * Cognito treats a thrown exception there as something to log and swallow, not fail the call).
 * PreSignUp is the one point in the flow where throwing actually stops account creation - before
 * any Cognito user exists at all, so there is nothing to roll back on rejection.
 *
 * <p>{@code name} is available here because the webapp already sends it as a plain (non-custom)
 * Cognito attribute on the initial {@code signUp()} call - it does not need email to be verified,
 * unlike {@code PostConfirmationCreatePersonHandler}'s job of actually creating the Person.
 *
 * <p>Unlike that trigger, a thrown exception here IS the intended behaviour, not something to avoid
 * - Cognito surfaces it to the client as the sign-up call's own failure, with this exception's
 * message included (`PreSignUp failed with error <message>.`), which is what lets the webapp show a
 * specific, readable rejection rather than a generic sign-up failure.
 */
public class PreSignUpNameCollisionHandler
    implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  private static final String TRIGGER_SOURCE_SIGN_UP = "PreSignUp_SignUp";

  /** Read by the webapp to distinguish this rejection from every other sign-up failure. */
  public static final String NAME_ALREADY_EXISTS_MESSAGE =
      "A person with this name already exists. Contact an admin if you believe this is a mistake.";

  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public PreSignUpNameCollisionHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
  }

  PreSignUpNameCollisionHandler(final DynamoDbClient dynamoDbClient, final String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  @Override
  public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
    if (!TRIGGER_SOURCE_SIGN_UP.equals(event.get("triggerSource"))) {
      return event;
    }

    final Map<String, Object> request = castToMap(event.get("request"));
    final Map<String, Object> userAttributes = castToMap(request.get("userAttributes"));
    final String name = (String) userAttributes.get("name");

    if (name != null
        && !name.isBlank()
        && new PersonRepository(dynamoDbClient, tableName)
            .findByNameIgnoringCaseAndWhitespace(name)
            .isPresent()) {
      throw new IllegalArgumentException(NAME_ALREADY_EXISTS_MESSAGE);
    }

    return event;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }
}
