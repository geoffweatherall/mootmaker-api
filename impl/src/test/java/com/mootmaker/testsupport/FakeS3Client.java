package com.mootmaker.testsupport;

import module java.base;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Minimal in-memory test double covering only the operations {@code AvatarStore} uses, in the same
 * spirit as {@link FakeDynamoDbClient}: one bucket's worth of objects, keyed by object key.
 *
 * <p>Listing is never truncated, so pagination is not exercised. A person's prefix holds one object
 * in steady state and two for an instant, a long way short of the thousand-key page size.
 */
public class FakeS3Client implements S3Client {

  /** An object's bytes and the headers it was written with. */
  public record StoredObject(byte[] bytes, String contentType, String cacheControl) {}

  /** Sorted, because real S3 lists keys in lexicographic order. */
  public final SortedMap<String, StoredObject> objects = new TreeMap<>();

  /** Every key passed to {@code deleteObject}, in order - including ones that did not exist. */
  public final List<String> deletedKeys = new ArrayList<>();

  /**
   * When set, reported as an object's Content-Length in place of its real size - the only way to
   * exercise the oversized-staged-upload guard without allocating megabytes in a unit test.
   */
  public Long reportedContentLength;

  /** Puts an object in place directly, standing in for a client's presigned PUT. */
  public void stage(final String key, final byte[] bytes, final String contentType) {
    objects.put(key, new StoredObject(bytes, contentType, null));
  }

  @Override
  public String serviceName() {
    return "s3";
  }

  @Override
  public void close() {}

  @Override
  public synchronized PutObjectResponse putObject(
      final PutObjectRequest request, final RequestBody body) {
    try (InputStream stream = body.contentStreamProvider().newStream()) {
      objects.put(
          request.key(),
          new StoredObject(stream.readAllBytes(), request.contentType(), request.cacheControl()));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return PutObjectResponse.builder().build();
  }

  @Override
  public synchronized ResponseInputStream<GetObjectResponse> getObject(
      final GetObjectRequest request) {
    final StoredObject object = objects.get(request.key());
    if (object == null) {
      throw NoSuchKeyException.builder().message("The specified key does not exist.").build();
    }
    final long contentLength =
        reportedContentLength != null ? reportedContentLength : object.bytes().length;
    return new ResponseInputStream<>(
        GetObjectResponse.builder()
            .contentLength(contentLength)
            .contentType(object.contentType())
            .build(),
        new ByteArrayInputStream(object.bytes()));
  }

  @Override
  public synchronized ListObjectsV2Response listObjectsV2(final ListObjectsV2Request request) {
    final String prefix = request.prefix() == null ? "" : request.prefix();
    final List<S3Object> contents =
        objects.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .map(key -> S3Object.builder().key(key).build())
            .toList();
    return ListObjectsV2Response.builder().contents(contents).isTruncated(false).build();
  }

  /** Succeeds for a key that does not exist, exactly as real S3 does. */
  @Override
  public synchronized DeleteObjectResponse deleteObject(final DeleteObjectRequest request) {
    deletedKeys.add(request.key());
    objects.remove(request.key());
    return DeleteObjectResponse.builder().build();
  }
}
