package com.mootmaker.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvatarUrlsTest {

  private static final AvatarUrls URLS = new AvatarUrls("https://avatars.mootmaker.com");

  @Test
  void resolvesAStoredKeyToAnAbsoluteUrl() {
    assertEquals(
        "https://avatars.mootmaker.com/v1/person-1/abc123.jpg",
        URLS.absolute("v1/person-1/abc123"));
  }

  @Test
  void hasNoUrlForAPersonWithNoAvatar() {
    assertNull(URLS.absolute(null));
  }

  /**
   * The same jar backs database-reset and database-repair, which have no reason to build an avatar
   * URL and are not given the base to do it with. Degrading to null matches {@code
   * DaysInvalidatedPublisher.fromEnvironment()} rather than failing a reset over a URL nobody asked
   * for.
   */
  @Test
  @DisplayName("degrades to null rather than throwing when no base URL is configured")
  void toleratesAnUnconfiguredBaseUrl() {
    assertNull(new AvatarUrls(null).absolute("v1/person-1/abc123"));
    assertNull(new AvatarUrls("").absolute("v1/person-1/abc123"));
  }

  /**
   * Terraform does not produce a trailing slash today, and this asserts it would not matter if it
   * ever did. The failure it prevents is a double slash in every avatar URL in an environment -
   * which nothing rejects, and which a human would have to happen to notice.
   */
  @Test
  void aTrailingSlashOnTheBaseUrlDoesNotDoubleUp() {
    assertEquals(
        "https://avatars.mootmaker.com/v1/person-1/abc123.jpg",
        new AvatarUrls("https://avatars.mootmaker.com/").absolute("v1/person-1/abc123"));
  }

  /**
   * The version lives inside the stored key, not in the composition. If normalisation ever changes,
   * records written under v1 must keep resolving to the v1 objects they actually wrote while new
   * uploads go to v2 - a version applied on the way out would silently repoint every existing
   * avatar at an object nobody wrote.
   */
  @Test
  void theStoredKeyCarriesItsOwnProcessingVersion() {
    assertEquals("v1/person-1/abc123", AvatarUrls.storedKey("person-1", "abc123"));
    assertEquals(
        "https://avatars.mootmaker.com/v2/person-1/abc123.jpg",
        URLS.absolute("v2/person-1/abc123"));
  }

  /**
   * The served prefix is in the bucket but not in the public URL - CloudFront's origin_path
   * supplies it, which is also what makes the uploads/ staging prefix unreachable through the
   * distribution.
   */
  @Test
  void theObjectKeyCarriesTheServedPrefixButTheUrlDoesNot() {
    assertEquals("avatars/v1/person-1/abc123.jpg", AvatarUrls.objectKey("v1/person-1/abc123"));
    assertEquals("avatars/v1/person-1/", AvatarUrls.personPrefix("person-1"));
  }

  /**
   * Deletion operates on a prefix, so it must not match a person whose id merely starts the same.
   */
  @Test
  void aPersonPrefixIsDelimitedSoItCannotMatchALongerId() {
    assertEquals("avatars/v1/abc/", AvatarUrls.personPrefix("abc"));
    assertEquals("avatars/v1/abcd/", AvatarUrls.personPrefix("abcd"));
  }
}
