package com.mootmaker.avatar;

import module java.base;

import com.mootmaker.model.AvatarError;
import com.mootmaker.s3.S3ClientProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * The avatars bucket: one private staging prefix that clients upload into, and one served prefix
 * that only this class writes.
 *
 * <ul>
 *   <li>{@code uploads/<personId>/<uploadId>} - staged and private, expired after a day by the
 *       bucket's lifecycle rule.
 *   <li>{@code avatars/v1/<personId>/<sha256>.jpg} - served and immutable, at most one per person.
 * </ul>
 *
 * <p>CloudFront's {@code origin_path} is {@code /avatars}, so the staging prefix is unreachable
 * through the distribution by construction - not merely forbidden by a policy someone has to read
 * correctly. See avatars.tf.
 *
 * <p><b>"At most one avatar per person" is a property of the prefix, and this class is what keeps
 * it.</b> {@link #deleteAvatarsExcept} lists what is actually there rather than trusting the Person
 * record to name it, so an object orphaned by a crash between writing the image and updating the
 * record is swept up by the next set or removal instead of living forever.
 */
public final class AvatarStore {

  /**
   * Long enough for a slow mobile connection to push two megabytes, short enough that a leaked URL
   * is worthless soon after. The signature pins the key, the content type and the content length,
   * so what a leaked URL permits is one upload into one person's private staging area.
   */
  static final Duration UPLOAD_URL_LIFETIME = Duration.ofMinutes(15);

  /**
   * Honest only because the key contains a hash of the image: different bytes are a different URL,
   * so nothing cached under this one can ever go stale.
   */
  static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

  private static final String UPLOADS_PREFIX = "uploads/";

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucketName;

  public AvatarStore(final S3Client s3, final S3Presigner presigner, final String bucketName) {
    this.s3 = s3;
    this.presigner = presigner;
    this.bucketName = bucketName;
  }

  /** Reads {@code AVATARS_BUCKET_NAME}, set by this project's own Terraform (see avatars.tf). */
  public static AvatarStore fromEnvironment() {
    return new AvatarStore(
        S3ClientProvider.client(),
        S3ClientProvider.presigner(),
        System.getenv("AVATARS_BUCKET_NAME"));
  }

  /** A URL a client may PUT exactly one object to, and when it stops working. */
  public record PresignedUpload(String url, Instant expiresAt) {}

  /**
   * Presigns a PUT into this person's staging area.
   *
   * <p>Both the content type and the content length are part of the request that gets signed, so
   * the upload must carry exactly those headers. For the length that means <i>exactly</i>, not "at
   * most" - stricter than the {@code content-length-range} a presigned POST would offer, and the
   * reason the client has to declare its size up front.
   */
  public PresignedUpload presignUpload(
      final String personId,
      final String uploadId,
      final String contentType,
      final long contentLength) {
    final PresignedPutObjectRequest presigned =
        presigner.presignPutObject(
            presign ->
                presign
                    .signatureDuration(UPLOAD_URL_LIFETIME)
                    .putObjectRequest(
                        put ->
                            put.bucket(bucket())
                                .key(uploadKey(personId, uploadId))
                                .contentType(contentType)
                                .contentLength(contentLength)));
    return new PresignedUpload(presigned.url().toString(), presigned.expiration());
  }

  /**
   * The staged bytes, or empty if nothing is staged under this id.
   *
   * <p>Re-checks the size rather than trusting the signed length to have held. The presigned URL is
   * the only door into the staging prefix, so this should be unreachable - but the bytes are about
   * to be read whole into memory, and "should be unreachable" is a poor thing to rest that on.
   *
   * @throws AvatarRejected with {@link AvatarError#UploadTooLarge} if the staged object is larger
   *     than {@link AvatarImage#MAX_UPLOAD_BYTES}
   */
  public Optional<byte[]> readUpload(final String personId, final String uploadId) {
    try (ResponseInputStream<GetObjectResponse> object =
        s3.getObject(get -> get.bucket(bucket()).key(uploadKey(personId, uploadId)))) {
      final Long contentLength = object.response().contentLength();
      if (contentLength != null && contentLength > AvatarImage.MAX_UPLOAD_BYTES) {
        object.abort();
        throw new AvatarRejected(AvatarError.UploadTooLarge);
      }
      return Optional.of(object.readAllBytes());
    } catch (final NoSuchKeyException e) {
      return Optional.empty();
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed reading a staged avatar upload", e);
    }
  }

  /**
   * Writes a normalised avatar where the distribution will serve it from.
   *
   * <p>Idempotent: the key is a pure function of the person and the source bytes, so repeating this
   * overwrites an object with itself.
   */
  public void putAvatar(final String storedKey, final byte[] jpeg) {
    s3.putObject(
        put ->
            put.bucket(bucket())
                .key(AvatarUrls.objectKey(storedKey))
                .contentType(AvatarImage.OUTPUT_CONTENT_TYPE)
                .cacheControl(CACHE_CONTROL),
        RequestBody.fromBytes(jpeg));
  }

  /**
   * Deletes every avatar object belonging to a person except the one given - or all of them, if
   * {@code storedKeyToKeep} is null.
   *
   * <p>The exclusion is not an optimisation. Re-uploading the same image produces the same key, so
   * a sweep that deleted "everything that was there before" would delete the avatar just set.
   *
   * <p>Touches only that person's prefix, which is what makes deleting a person safe when someone
   * else holds the identical image: theirs is a different object under a different prefix.
   */
  public void deleteAvatarsExcept(final String personId, final String storedKeyToKeep) {
    final String keep = storedKeyToKeep == null ? null : AvatarUrls.objectKey(storedKeyToKeep);
    final List<String> doomed =
        s3
            .listObjectsV2Paginator(
                list -> list.bucket(bucket()).prefix(AvatarUrls.personPrefix(personId)))
            .contents()
            .stream()
            .map(S3Object::key)
            .filter(key -> !key.equals(keep))
            .toList();
    for (final String key : doomed) {
      s3.deleteObject(delete -> delete.bucket(bucket()).key(key));
    }
  }

  /** Deletes every avatar object belonging to a person. */
  public void deleteAllAvatars(final String personId) {
    deleteAvatarsExcept(personId, null);
  }

  private static String uploadKey(final String personId, final String uploadId) {
    return UPLOADS_PREFIX + personId + "/" + uploadId;
  }

  /**
   * Fails at use rather than at construction. The same jar backs Lambdas that never touch avatars
   * and are not given the variable, and every handler is constructed eagerly at INIT - so throwing
   * from the constructor would fail a deploy over a bucket the function was never going to use.
   */
  private String bucket() {
    if (bucketName == null || bucketName.isBlank()) {
      throw new IllegalStateException("AVATARS_BUCKET_NAME is not set");
    }
    return bucketName;
  }
}
