package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.avatar.AvatarImage;
import com.mootmaker.avatar.AvatarRejected;
import com.mootmaker.avatar.AvatarStore;
import com.mootmaker.avatar.AvatarUrls;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.AvatarError;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * AppSync direct-Lambda resolver for {@code Mutation.confirmAvatarUpload} - step two of setting an
 * avatar, and the only place an uploaded image is ever looked at. Admin, or the caller's own
 * Person; see {@link Identity#requireAdminOrSelf}.
 *
 * <p>Synchronous on purpose. Decoding and resizing an image this small takes milliseconds, so a
 * rejection comes back in the mutation's own {@code errors} array like every other rejection in
 * this API, rather than over a subscription or through a status field someone has to poll.
 *
 * <p><b>Write order: the image, then the record, then the sweep.</b> Each step is safe to stop
 * after. Stopping after the first leaves an object nothing points at, which the next set or removal
 * sweeps up. Stopping after the second leaves the previous avatar's object alongside the new one,
 * likewise swept up. The reverse order - sweep first - would leave a Person pointing at an object
 * that no longer exists: a broken avatar, rather than fifteen wasted kilobytes.
 *
 * <p><b>Idempotent, which is why the staged upload is not deleted here.</b> The served key is a
 * pure function of the person and the uploaded bytes, so confirming twice overwrites an object with
 * itself and sets the record to the value it already holds. A client whose first response was lost
 * can simply retry. Deleting the staged bytes on success would turn that retry into {@code
 * UploadNotFound}; the bucket's one-day lifecycle rule on the staging prefix removes them instead.
 */
public class ConfirmAvatarUploadHandler implements RequestHandler<Map<String, Object>, Object> {

  /** What {@code requestAvatarUpload} issues - see {@code IdAllocator}. */
  private static final Pattern UPLOAD_ID = Pattern.compile("[A-Za-z0-9]{8}");

  private final PersonRepository people;
  private final AvatarStore avatars;
  private final AvatarUrls avatarUrls;

  public ConfirmAvatarUploadHandler() {
    this(
        DynamoDbClientProvider.client(),
        System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"),
        AvatarStore.fromEnvironment(),
        AvatarUrls.fromEnvironment());
  }

  ConfirmAvatarUploadHandler(
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
    final String uploadId = (String) arguments.get("uploadId");

    if (people.findById(personId).isEmpty()) {
      return rejection(AvatarError.PersonNotFound);
    }
    // An id this API could not have issued cannot name a staged upload, so it is not worth a
    // round trip to S3 - and it keeps a caller-supplied string with arbitrary content out of an
    // object key.
    if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
      return rejection(AvatarError.UploadNotFound);
    }

    final AvatarImage.Normalised normalised;
    try {
      final Optional<byte[]> staged = avatars.readUpload(personId, uploadId);
      if (staged.isEmpty()) {
        return rejection(AvatarError.UploadNotFound);
      }
      normalised = AvatarImage.normalise(staged.get());
    } catch (final AvatarRejected rejected) {
      return rejection(rejected.error());
    }

    final String storedKey = AvatarUrls.storedKey(personId, normalised.sourceSha256());
    avatars.putAvatar(storedKey, normalised.jpeg());

    final Person updated;
    try {
      updated = people.updateAvatarUrl(personId, storedKey);
    } catch (final ConditionalCheckFailedException e) {
      // The person was deleted between the check above and this write. Their deletion has
      // already swept the prefix, so the object just written is the one thing left behind.
      avatars.deleteAllAvatars(personId);
      return rejection(AvatarError.PersonNotFound);
    }

    avatars.deleteAvatarsExcept(personId, storedKey);

    final Map<String, Object> result = new HashMap<>();
    result.put("person", updated.toResponseMap(avatarUrls));
    result.put("errors", List.of());
    return result;
  }

  private static Map<String, Object> rejection(final AvatarError error) {
    final Map<String, Object> result = new HashMap<>();
    result.put("person", null);
    result.put("errors", List.of(error.name()));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castToMap(final Object value) {
    return (Map<String, Object>) value;
  }
}
