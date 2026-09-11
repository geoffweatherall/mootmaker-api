package com.mootmaker.verify;

import module java.base;

/**
 * Dates that are inside the window the API will accept bookings in.
 *
 * <p>Meetings may only be created between the stored retention boundary and the booking horizon,
 * and the retention boundary <b>moves</b> - it advances one Monday every week. A hardcoded date is
 * therefore not a constant at all: it is a date that silently falls out of the bookable window some
 * weeks after it was written, failing a test that has nothing to do with dates. Three of these
 * tests had already crossed that line by the time the rule was enforced.
 *
 * <p>A week ahead of today is comfortably inside both bounds and stays there.
 */
final class BookableDates {

  /** Far enough ahead to be unambiguously bookable, near enough to be nowhere near the horizon. */
  private static final int DAYS_AHEAD = 7;

  private BookableDates() {}

  static LocalDate day() {
    return LocalDate.now(ZoneOffset.UTC).plusDays(DAYS_AHEAD);
  }

  /** e.g. {@code at("09:00:00")} - an ISO-8601 local date-time on the bookable day. */
  static String at(final String time) {
    return day() + "T" + time;
  }

  /** The same time on the following day, for the rules about spanning midnight. */
  static String nextDayAt(final String time) {
    return day().plusDays(1) + "T" + time;
  }
}
