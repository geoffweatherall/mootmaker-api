package com.mootmaker.handler;

import module java.base;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.limits.Limits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Deletes meeting history older than the retention window, weekly, on an EventBridge schedule.
 *
 * <p>This exists because {@code principles.md} requires the bill to stay flat under steady usage,
 * and meetings were the one thing that grew with time rather than with use.
 *
 * <p><b>Advance the boundary first, then delete.</b> That order is the whole design:
 *
 * <ul>
 *   <li>Deleting first opens a window where the stored boundary still promises data that is already
 *       gone. A client asking for those days gets empty results indistinguishable from "nothing was
 *       booked" - a silent lie.
 *   <li>Advancing first opens the opposite window: data that still exists but is no longer
 *       advertised. Harmless, and it corrects itself on the next run.
 * </ul>
 *
 * <p>It holds <b>even if the job dies between the two steps</b>, which is the real test of it. Any
 * ordering is fine when everything succeeds.
 *
 * <p>Catch-up is free rather than special-cased: the target is computed from today, not from the
 * stored value, so a job that has not run for a month advances to the right Monday and deletes
 * everything before it in one pass.
 *
 * <p>It does not notify connected clients - see the design. The ordering already protects every
 * reader who fetches the boundary after it advances, which is every new page load.
 */
public class HistoryCleanupHandler
    implements RequestHandler<Map<String, Object>, Map<String, Object>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(HistoryCleanupHandler.class);

  private final DayRepository days;
  private final Clock clock;

  public HistoryCleanupHandler() {
    this(
        new DayRepository(
            DynamoDbClientProvider.client(),
            System.getenv().getOrDefault("MEETINGS_TABLE_NAME", "Meetings")),
        Clock.systemUTC());
  }

  HistoryCleanupHandler(final DayRepository days, final Clock clock) {
    this.days = days;
    this.clock = clock;
  }

  HistoryCleanupHandler(
      final DynamoDbClient dynamoDbClient, final String meetingsTableName, final Clock clock) {
    this(new DayRepository(dynamoDbClient, meetingsTableName, clock), clock);
  }

  @Override
  public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
    final boolean dryRun = event != null && Boolean.TRUE.equals(event.get("dryRun"));
    final String target = targetBoundary(asOf(event));

    final Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("dryRun", dryRun);
    summary.put("boundary", target);

    if (dryRun) {
      // Reports what would go without touching either the boundary or the data, so a test can
      // assert the selection before anything is irreversible.
      final List<String> wouldDelete =
          days.scanDays().stream()
              .map(day -> day.date())
              .filter(date -> date.compareTo(target) < 0)
              .sorted()
              .toList();
      summary.put("datesDeleted", wouldDelete);
      LOGGER.info(
          "Dry run: boundary would advance to {} and {} day(s) would be deleted",
          target,
          wouldDelete.size());
      return summary;
    }

    days.advanceBoundaryTo(target);
    final List<String> deleted = days.deleteDaysBefore(target);

    summary.put("datesDeleted", deleted);
    LOGGER.info("Retention boundary advanced to {}; deleted {} day(s)", target, deleted.size());
    return summary;
  }

  /**
   * The date to treat as today, overridable by payload for tests.
   *
   * <p>The override is deliberately <b>the clock, not the boundary</b>. An acceptance test cannot
   * otherwise observe a deletion at all: writes are bounded at the retention boundary, so it cannot
   * seed a day behind it, and the boundary only moves when the calendar does - on most days a real
   * run is correctly a no-op. Overriding the boundary directly would bypass the very logic most
   * likely to be wrong; overriding today still makes the job compute its own boundary, which is
   * what the design asked for.
   *
   * <p>Absent means the real clock, so a scheduled run is unaffected and cannot be steered by an
   * unexpected payload - EventBridge sends {@code {}} explicitly for exactly that reason.
   */
  private LocalDate asOf(final Map<String, Object> event) {
    final Object supplied = event == null ? null : event.get("today");
    return supplied instanceof String date && !date.isBlank()
        ? LocalDate.parse(date)
        : LocalDate.now(clock);
  }

  /**
   * The Monday on or before (today - retention days).
   *
   * <p>Monday alignment makes "is this week reachable" an exact comparison rather than a straddling
   * judgement, at the cost of retention being a range - between 30 and 37 days depending where in
   * the week the job falls, which is why the storage bound uses 37 as its worst case.
   *
   * <p>Computed from today rather than from the stored boundary, which is what makes a missed run
   * catch up rather than fall permanently behind by however long it was down.
   */
  private String targetBoundary(final LocalDate today) {
    final LocalDate earliest = today.minusDays(Limits.RETENTION_DAYS_MINIMUM);
    return earliest.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
  }
}
