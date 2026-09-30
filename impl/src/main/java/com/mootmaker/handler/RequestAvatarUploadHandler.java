package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.avatar.AvatarImage;
import com.mootmaker.avatar.AvatarRejected;
import com.mootmaker.avatar.AvatarStore;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.IdAllocator;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.AvatarError;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.requestAvatarUpload} - step one of setting an
 * avatar. Admin, or the caller's own Person; see {@link Identity#requireAdminOrSelf}.
 *
 * <p>Hands back a presigned S3 URL the client PUTs the image to directly, so the bytes never pass
 * through GraphQL, which has no sensible way to carry them. Nothing about the Person changes here:
 * the upload lands in a private staging prefix, and only {@code confirmAvatarUpload} - which
 * decodes and re-encodes it - can make it anyone's avatar.
 *
 * <p>What is checked here is only what the caller <i>declares</i>. That is worth doing, because it
 * rejects the obvious mistakes before two megabytes cross a mobile connection, but it is not
 * validation of the image. See {@link AvatarImage#validateDeclaredUpload}.
 */
public class RequestAvatarUploadHandler implements RequestHandler<Map<String, Object>, Object> {

  private final PersonRepository people;
  private final AvatarStore avatars;

  public RequestAvatarUploadHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        AvatarStore.fromEnvironment());
  }

  RequestAvatarUploadHandler(
      final DynamoDbClient dynamoDbClient, final String tableName, final AvatarStore avatars) {
    this.people = new PersonRepository(dynamoDbClient, tableName);
    this.avatars = avatars;
  }

  @Override
  public Object handleRequest(final Map<String, Object> event, final Context context) {
    Identity.requireAuthenticated(event);
    final Map<String, Object> arguments = castToMap(event.get("arguments"));
    final String personId = (String) arguments.get("personId");
    Identity.requireAdminOrSelf(event, personId);

    final String contentType = (String) arguments.get("contentType");
    final long contentLength =
        arguments.get("contentLength") instanceof Number number ? number.longValue() : 0;

    try {
      AvatarImage.validateDeclaredUpload(contentType, contentLength);
    } catch (final AvatarRejected rejected) {
      return rejection(rejected.error());
    }
    if (people.findById(personId).isEmpty()) {
      return rejection(AvatarError.PersonNotFound);
    }

    // Not collision-checked, unlike a Person or Room id: the staging key is already scoped to one
    // person, and two live uploads for the same person drawing the same 8 characters would need a
    // 1-in-62^8 coincidence inside a fifteen-minute window.
    final String uploadId = IdAllocator.newId();
    final AvatarStore.PresignedUpload presigned =
        avatars.presignUpload(personId, uploadId, contentType, contentLength);

    final Map<String, Object> upload = new HashMap<>();
    upload.put("uploadId", uploadId);
    upload.put("url", presigned.url());
    upload.put("contentType", contentType);
    upload.put("contentLength", contentLength);
    upload.put("expiresAt", presigned.expiresAt().toString());

    final Map<String, Object> result = new HashMap<>();
    result.put("upload", upload);
    result.put("errors", List.of());
    return result;
  }

  private static Map<String, Object> rejection(final AvatarError error) {
    final Map<String, Object> result = new HashMap<>();
    result.put("upload", null);
    result.put("errors", List.of(error.name()));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }
}
