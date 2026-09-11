package com.mootmaker.testsupport;

import module java.base;

import com.mootmaker.realtime.DayBroadcaster;

/**
 * Captures what would have been broadcast.
 *
 * <p>Records every call as its own entry rather than flattening the dates together, because how
 * many broadcasts happened is as much the point as which dates they carried: bulk creation must
 * wake subscribers ONCE for 99 meetings on one date, and a flattened list could not tell that from
 * 99 separate broadcasts of the same date.
 */
public final class RecordingBroadcaster implements DayBroadcaster {

  public final List<List<String>> broadcasts = new ArrayList<>();

  @Override
  public void publish(final Collection<String> dates) {
    broadcasts.add(List.copyOf(dates));
  }

  /** Every date broadcast, across all calls, for assertions that do not care about grouping. */
  public List<String> allDates() {
    return broadcasts.stream().flatMap(List::stream).toList();
  }
}
