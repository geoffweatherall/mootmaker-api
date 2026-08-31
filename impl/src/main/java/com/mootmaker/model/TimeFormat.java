package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code TimeFormat} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>Purely a client display preference - see {@link DateFormat} for the same caveat.
 */
public enum TimeFormat {
    /** {@code HH:mm}. Also the default for anyone who has never chosen. */
    TwentyFourHour,
    /** {@code hh:mm A}. */
    AmPm
}
