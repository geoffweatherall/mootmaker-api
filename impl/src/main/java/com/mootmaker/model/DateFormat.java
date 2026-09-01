package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code DateFormat} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>Purely a client display preference: this API accepts and returns ISO-8601 local date-times
 * regardless of any person's setting. Nothing server-side should ever branch on this value.
 */
public enum DateFormat {
    /** {@code MM/DD/YYYY}. */
    Usa,
    /** {@code DD/MM/YYYY}. */
    British,
    /** {@code YYYY-MM-DD}. Also the default for anyone who has never chosen. */
    Iso
}
