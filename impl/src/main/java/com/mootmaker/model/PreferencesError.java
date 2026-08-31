package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code PreferencesError} enum. Constant names must match the schema's enum
 * value names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>Deliberately separate from {@link PersonError} rather than reusing its {@code PersonNotFound}:
 * that value is documented as "id did not match any existing person", which is not what happens
 * here - there is no id, the caller simply has no Person of their own - and its sibling
 * {@code NameRequired} would be permanently unreachable in a preferences result.
 */
public enum PreferencesError {
    /** The caller has no linked Person, so there is nothing to store a preference against. */
    NoLinkedPerson
}
