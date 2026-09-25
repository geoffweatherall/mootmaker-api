package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code WeekStart} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 *
 * <p>Purely a client display preference for a date-picker calendar grid: which day its weeks start
 * on. Deliberately not inferred from locale - see {@code Person.weekStart}'s own doc.
 */
public enum WeekStart {
  /** Also the default for anyone who has never chosen. */
  Monday,
  Sunday
}
