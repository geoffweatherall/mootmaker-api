package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code PersonError} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 */
public enum PersonError {
  NameRequired,
  /** createPerson only: another person already has this name (case/whitespace-insensitive). */
  NameAlreadyExists,
  /** renamePerson/setPersonAdmin/deletePerson only: id did not match any existing person. */
  PersonNotFound,
  /** updateMyName only: the caller has no linked Person, so there is nothing to rename. */
  NoLinkedPerson,
  /** deletePerson only: the caller targeted their own Person. Use deleteMyAccount instead. */
  CannotDeleteSelf,
  /** deletePerson only: this Person belongs to a reserved system account. */
  ReservedAccount,
  /** setPersonAdmin only: the target has no linked Cognito account. */
  NoLinkedAccount,
  /** setPersonAdmin only: no one can remove their own admin access through this mutation. */
  CannotRevokeOwnAdminAccess
}
