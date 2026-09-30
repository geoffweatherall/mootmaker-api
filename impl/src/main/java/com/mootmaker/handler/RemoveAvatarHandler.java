package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.avatar.AvatarStore;
import com.mootmaker.avatar.AvatarUrls;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.AvatarError;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.removeAvatar}. Admin, or the caller's own
 * Person; see {@link Identity#requireAdminOrSelf}.
 *
 * <p>Not a special case: it is {@code confirmAvatarUpload}'s sweep with nothing kept and the record
 * cleared. A success for someone who has no avatar, since the state asked for is the state they are
 * already in.
 *
 * <p><b>Write order: the record, then the objects</b> - the same direction as setting one, for the
 * same reason. Stopping in between leaves an object nothing points at, which the next set or
 * removal sweeps up. The reverse would leave a Person pointing at a deleted object.
 */
public class RemoveAvatarHandler implements RequestHandler<Map<String, Object>, Object> {

  private final PersonRepository people;
  private final AvatarStore avatars;
  private final AvatarUrls avatarUrls;

  public RemoveAvatarHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        AvatarStore.fromEnvironment(),
        AvatarUrls.fromEnvironment());
  }

  RemoveAvatarHandler(
      final DynamoDbClient dynamoDbClient,
      final String tableName,
      final AvatarStore avatars,
      final AvatarUrls avatarUrls) {
    this.people = new PersonRepository(dynamoDbClient, tableName);
    this.avatars = avatars;
    this.avatarUrls = avatarUrls;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);
    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String personId = (String) arguments.get("personId");
    Identity.requireAdminOrSelf(event, personId);

    final Map<String, Object> result = new HashMap<>();
    final Person updated;
    try {
      updated = people.updateAvatarUrl(personId, null);
    } catch (final ConditionalCheckFailedException e) {
      result.put("person", null);
      result.put("errors", List.of(AvatarError.PersonNotFound.name()));
      return result;
    }
    avatars.deleteAllAvatars(personId);

    result.put("person", updated.toResponseMap(avatarUrls));
    result.put("errors", List.of());
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }
}
