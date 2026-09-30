package com.mootmaker.avatar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.AvatarError;
import com.mootmaker.testsupport.FakeS3Client;
import com.mootmaker.testsupport.TestPresigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvatarStoreTest {

  private static final String BUCKET = "test-avatars";

  private static AvatarStore store(final FakeS3Client s3) {
    return new AvatarStore(s3, TestPresigner.create(), BUCKET);
  }

  private static byte[] bytes(final String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  // --- Presigning -----------------------------------------------------------------------

  @Test
  void presignsAPutIntoThePersonsPrivateStagingPrefix() {
    final AvatarStore.PresignedUpload upload =
        store(new FakeS3Client()).presignUpload("person-1", "UPLOAD01", "image/png", 1234);

    final URI url = URI.create(upload.url());
    assertTrue(url.getHost().startsWith(BUCKET + "."), upload.url());
    assertEquals("/uploads/person-1/UPLOAD01", url.getPath());
  }

  /**
   * The whole basis of the size limit. If either header were missing from the signature, S3 would
   * accept any type and any length through this URL, and the 2 MiB ceiling would be a suggestion.
   */
  @Test
  @DisplayName("the signature covers both Content-Type and Content-Length")
  void presignedUrlPinsContentTypeAndContentLength() {
    final AvatarStore.PresignedUpload upload =
        store(new FakeS3Client()).presignUpload("person-1", "UPLOAD01", "image/png", 1234);

    final String signedHeaders =
        Arrays.stream(URI.create(upload.url()).getRawQuery().split("&"))
            .filter(parameter -> parameter.startsWith("X-Amz-SignedHeaders="))
            .map(parameter -> URLDecoder.decode(parameter.split("=", 2)[1], StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    final List<String> headers = List.of(signedHeaders.split(";"));
    assertTrue(headers.contains("content-type"), signedHeaders);
    assertTrue(headers.contains("content-length"), signedHeaders);
  }

  @Test
  void presignedUrlExpiresAfterTheUploadLifetime() {
    final Instant before = Instant.now();

    final AvatarStore.PresignedUpload upload =
        store(new FakeS3Client()).presignUpload("person-1", "UPLOAD01", "image/png", 1234);

    final Duration lifetime = Duration.between(before, upload.expiresAt());
    assertTrue(
        lifetime.compareTo(AvatarStore.UPLOAD_URL_LIFETIME.minusSeconds(5)) >= 0
            && lifetime.compareTo(AvatarStore.UPLOAD_URL_LIFETIME.plusSeconds(5)) <= 0,
        "expected about " + AvatarStore.UPLOAD_URL_LIFETIME + ", got " + lifetime);
  }

  // --- Staged uploads -------------------------------------------------------------------

  @Test
  void readsBackAStagedUpload() {
    final FakeS3Client s3 = new FakeS3Client();
    s3.stage("uploads/person-1/UPLOAD01", bytes("staged"), "image/png");

    assertArrayEquals(bytes("staged"), store(s3).readUpload("person-1", "UPLOAD01").orElseThrow());
  }

  @Test
  void aMissingStagedUploadIsEmptyRatherThanAnError() {
    assertTrue(store(new FakeS3Client()).readUpload("person-1", "UPLOAD01").isEmpty());
  }

  @Test
  @DisplayName("one person's staged upload cannot be read as another's")
  void stagedUploadsAreScopedToThePerson() {
    final FakeS3Client s3 = new FakeS3Client();
    s3.stage("uploads/person-1/UPLOAD01", bytes("staged"), "image/png");

    assertTrue(store(s3).readUpload("person-2", "UPLOAD01").isEmpty());
  }

  @Test
  @DisplayName("an oversized staged object is refused from its reported length, without reading it")
  void refusesAnOversizedStagedUpload() {
    final FakeS3Client s3 = new FakeS3Client();
    s3.stage("uploads/person-1/UPLOAD01", bytes("small really"), "image/png");
    s3.reportedContentLength = AvatarImage.MAX_UPLOAD_BYTES + 1;

    final AvatarRejected rejected =
        assertThrows(AvatarRejected.class, () -> store(s3).readUpload("person-1", "UPLOAD01"));
    assertEquals(AvatarError.UploadTooLarge, rejected.error());
  }

  // --- Served avatars -------------------------------------------------------------------

  @Test
  void writesAnAvatarWhereTheDistributionServesItFrom() {
    final FakeS3Client s3 = new FakeS3Client();

    store(s3).putAvatar("v1/person-1/abc123", bytes("jpeg"));

    final FakeS3Client.StoredObject stored = s3.objects.get("avatars/v1/person-1/abc123.jpg");
    assertArrayEquals(bytes("jpeg"), stored.bytes());
    assertEquals("image/jpeg", stored.contentType());
    assertEquals("public, max-age=31536000, immutable", stored.cacheControl());
  }

  @Test
  void sweepDeletesEverythingUnderThePrefixExceptTheKeptKey() {
    final FakeS3Client s3 = new FakeS3Client();
    final AvatarStore store = store(s3);
    store.putAvatar("v1/person-1/old", bytes("old"));
    store.putAvatar("v1/person-1/orphan", bytes("orphan"));
    store.putAvatar("v1/person-1/new", bytes("new"));

    store.deleteAvatarsExcept("person-1", "v1/person-1/new");

    assertEquals(Set.of("avatars/v1/person-1/new.jpg"), s3.objects.keySet());
  }

  @Test
  void deleteAllLeavesNothingUnderThePrefix() {
    final FakeS3Client s3 = new FakeS3Client();
    final AvatarStore store = store(s3);
    store.putAvatar("v1/person-1/one", bytes("one"));
    store.putAvatar("v1/person-1/two", bytes("two"));

    store.deleteAllAvatars("person-1");

    assertTrue(s3.objects.isEmpty());
  }

  /**
   * The case global content-addressing got wrong, and the reason keys are per person. Two people
   * holding the identical image hold two objects, so removing one cannot break the other. The ids
   * are chosen so that one is a string prefix of the other: a sweep keyed on "avatars/v1/abc"
   * without its trailing slash would take "abcd"'s avatar with it.
   */
  @Test
  @DisplayName(
      "a sweep never touches another person's avatar, staged uploads, or a prefix-alike id")
  void sweepIsConfinedToOnePersonsPrefix() {
    final FakeS3Client s3 = new FakeS3Client();
    final AvatarStore store = store(s3);
    store.putAvatar("v1/abc/samehash", bytes("image"));
    store.putAvatar("v1/abcd/samehash", bytes("image"));
    s3.stage("uploads/abc/UPLOAD01", bytes("staged"), "image/png");

    store.deleteAllAvatars("abc");

    assertEquals(
        Set.of("avatars/v1/abcd/samehash.jpg", "uploads/abc/UPLOAD01"), s3.objects.keySet());
  }

  // --- Configuration --------------------------------------------------------------------

  @Test
  @DisplayName("an unset bucket fails at use, not at construction")
  void unsetBucketFailsOnlyWhenUsed() {
    final AvatarStore store =
        assertDoesNotThrow(() -> new AvatarStore(new FakeS3Client(), TestPresigner.create(), null));

    assertThrows(IllegalStateException.class, () -> store.deleteAllAvatars("person-1"));
    assertThrows(
        IllegalStateException.class,
        () -> store.presignUpload("person-1", "UPLOAD01", "image/png", 1));
  }
}
