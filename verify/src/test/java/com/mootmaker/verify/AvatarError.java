package com.mootmaker.verify;

/**
 * Mirrors the GraphQL {@code AvatarError} enum. Constant names must match the schema's enum value
 * names exactly, since that's the literal string AppSync returns over the wire.
 */
enum AvatarError {
  PersonNotFound,
  UnsupportedContentType,
  UploadTooLarge,
  InvalidContentLength,
  UploadNotFound,
  NotAnImage,
  ImageTooSmall,
  ImageTooLarge
}
