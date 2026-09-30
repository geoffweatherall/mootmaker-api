package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code AvatarError} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>Split across the two upload steps on purpose. {@code requestAvatarUpload} can only judge what
 * the caller <i>claims</i> - a content type and a byte count - so its rejections are cheap and
 * happen before a single byte is transferred. {@code confirmAvatarUpload} is the first point
 * anything has looked at the actual bytes, so everything that requires decoding lands there. A
 * client that passed step one has no guarantee of passing step two, and the schema says so.
 */
public enum AvatarError {
  /** Every mutation: personId did not match any existing person. */
  PersonNotFound,
  /** requestAvatarUpload only: the declared content type is not one this API accepts. */
  UnsupportedContentType,
  /**
   * requestAvatarUpload: the declared size is above the upload ceiling. Also confirmAvatarUpload,
   * in the should-be-impossible case that a larger object reached staging anyway.
   */
  UploadTooLarge,
  /** requestAvatarUpload only: the declared size is zero or negative. */
  InvalidContentLength,
  /**
   * confirmAvatarUpload only: nothing is staged under this uploadId. Either it was never uploaded,
   * the upload did not complete, or it has passed the staging lifecycle rule's expiry.
   */
  UploadNotFound,
  /** confirmAvatarUpload only: the uploaded bytes are not a JPEG or PNG image. */
  NotAnImage,
  /** confirmAvatarUpload only: the image is smaller than the minimum accepted dimension. */
  ImageTooSmall,
  /** confirmAvatarUpload only: the image is larger than the maximum accepted dimension. */
  ImageTooLarge
}
