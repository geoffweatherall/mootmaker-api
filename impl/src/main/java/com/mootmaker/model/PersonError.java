package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code PersonError} enum. Constant names must match the schema's enum
 * value names exactly, since AppSync serializes/validates enum values as these literal strings.
 */
public enum PersonError {
    NameRequired,
    /** updatePerson only: id did not match any existing person. */
    PersonNotFound
}
