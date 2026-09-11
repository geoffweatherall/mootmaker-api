package com.mootmaker.verify;

/**
 * Mirrors the GraphQL {@code RoomError} enum. Constant names must match the schema's enum value
 * names exactly, since that's the literal string AppSync returns over the wire.
 */
enum RoomError {
  NameRequired,
  CapacityTooLow,
  RoomNotFound
}
