package com.mootmaker.avatar;

/**
 * Turns the value stored on a Person into the absolute URL a client receives, and back.
 *
 * <p>DynamoDB stores {@code v1/<personId>/<sha256>} - the non-boilerplate part and nothing else.
 * Storing the absolute URL instead would bake an environment's hostname into the data, so a
 * production snapshot restored into an ephemeral environment would serve production's avatars.
 * Returning the bare stored value instead would push host knowledge onto every client, which is the
 * implicit coupling this whole design exists to remove. So the host is configuration, applied on
 * the way out.
 *
 * <p>The {@code v1/} segment stays inside the stored value rather than being added here, and that
 * distinction is load bearing: the processing version is per-avatar state, not a global setting. If
 * normalisation ever changes, records written under v1 must keep resolving to the v1 objects they
 * actually wrote while new uploads go to v2. A version applied here would silently repoint every
 * existing avatar at an object nobody ever wrote.
 *
 * <p>See mootmaker/designs/archive/person-avatar-upload-refactor.md.
 */
public final class AvatarUrls {

  /** Every avatar is normalised to JPEG, so the extension is a constant rather than stored. */
  private static final String EXTENSION = ".jpg";

  private static final AvatarUrls FROM_ENVIRONMENT =
      new AvatarUrls(System.getenv("AVATARS_BASE_URL"));

  private final String baseUrl;

  public AvatarUrls(final String baseUrl) {
    // Tolerating a trailing slash here rather than trusting Terraform to never grow one: the cost
    // is one call, and the failure it prevents is a double slash in every avatar URL in an
    // environment, which nothing would reject and a human would have to notice.
    this.baseUrl =
        baseUrl == null || baseUrl.isBlank()
            ? null
            : baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /**
   * Reads {@code AVATARS_BASE_URL}, set by this project's own Terraform (see avatars.tf).
   *
   * <p>Deliberately configuration rather than something rebuilt here from the environment name:
   * production drops the environment segment from its hostname, so a rule expressed in two places
   * would be wrong in exactly one environment - the one that matters.
   */
  public static AvatarUrls fromEnvironment() {
    return FROM_ENVIRONMENT;
  }

  /**
   * The absolute URL for a stored key, or null for a person with no avatar.
   *
   * <p>Returns null rather than throwing when the base URL is unset. The same jar backs
   * database-reset and database-repair, which have no reason to build an avatar URL - the same
   * reasoning as {@code DaysInvalidatedPublisher.fromEnvironment()} degrading to a no-op there.
   */
  public String absolute(final String storedKey) {
    if (storedKey == null || baseUrl == null) {
      return null;
    }
    return baseUrl + "/" + storedKey + EXTENSION;
  }

  /** The key to store for a person's newly uploaded avatar, given the hash of the source bytes. */
  public static String storedKey(final String personId, final String sourceSha256) {
    return "v1/" + personId + "/" + sourceSha256;
  }

  /**
   * The S3 object key a stored value maps to. The served prefix is not part of the stored value or
   * of the public URL: CloudFront's {@code origin_path} supplies it, which is also what makes the
   * {@code uploads/} staging prefix unreachable through the distribution at all.
   */
  public static String objectKey(final String storedKey) {
    return "avatars/" + storedKey + EXTENSION;
  }

  /** The S3 prefix holding everything belonging to one person - what deletion operates on. */
  public static String personPrefix(final String personId) {
    return "avatars/v1/" + personId + "/";
  }
}
