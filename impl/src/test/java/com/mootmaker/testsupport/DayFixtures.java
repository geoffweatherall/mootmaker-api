package com.mootmaker.testsupport;

import module java.base;

import com.mootmaker.dynamo.DayRepository;
import com.mootmaker.model.Day;
import com.mootmaker.model.MeetingRecord;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Builds the items a day-keyed meetings table actually holds, so tests can state what they mean -
 * "these meetings exist" - rather than assembling day items, pointers and a config row by hand.
 *
 * <p>It groups by date deliberately rather than taking a date argument: a meeting's date is derived
 * from its own start time, exactly as {@code DayRepository} derives it, so a fixture cannot put a
 * meeting in a day item that disagrees with its own timestamps. That was possible when tests wrote
 * per-meeting items, and it is the kind of impossible state a fixture should not be able to build.
 */
public final class DayFixtures {

  /**
   * Permissive enough that no test date falls outside it, so only tests about the window mention
   * it.
   */
  public static final String DEFAULT_EARLIEST_RETAINED_DATE = "2000-01-01";

  private DayFixtures() {}

  /** Day items for the given meetings, grouped by their own dates, plus one pointer each. */
  public static List<Map<String, AttributeValue>> dayItems(final MeetingRecord... meetings) {
    final Map<String, List<MeetingRecord>> byDate =
        Arrays.stream(meetings)
            .collect(
                Collectors.groupingBy(
                    MeetingRecord::date, LinkedHashMap::new, Collectors.toList()));

    final List<Map<String, AttributeValue>> items = new ArrayList<>();
    byDate.forEach((date, onThatDate) -> items.add(new Day(date, 1, onThatDate).toItem()));
    Arrays.stream(meetings)
        .forEach(
            meeting ->
                items.add(
                    Map.of(
                        "pk",
                            AttributeValue.builder()
                                .s(DayRepository.POINTER_PK_PREFIX + meeting.id())
                                .build(),
                        "date", AttributeValue.builder().s(meeting.date()).build())));
    // Included because a real table always has one, and because a fixture that replaces the table
    // would otherwise silently remove it - leaving every write path failing for a reason that has
    // nothing to do with what the test is about.
    items.add(retentionConfig(DEFAULT_EARLIEST_RETAINED_DATE));
    return items;
  }

  /**
   * The config item Terraform seeds when the table is created. Tests that exercise a write path
   * need it, because the bookable window cannot be computed without it - which is the same reason a
   * real environment without one refuses every booking.
   */
  public static Map<String, AttributeValue> retentionConfig(final String earliestRetainedDate) {
    return Map.of(
        "pk", AttributeValue.builder().s(DayRepository.RETENTION_CONFIG_PK).build(),
        "earliestRetainedDate", AttributeValue.builder().s(earliestRetainedDate).build());
  }

  /**
   * Adds one meeting to whatever the table already holds, merging into its day item rather than
   * replacing it - so a fixture can build a day up a meeting at a time, the way the handlers do.
   */
  public static void addMeeting(
      final FakeDynamoDbClient client, final String tableName, final MeetingRecord meeting) {
    final List<Map<String, AttributeValue>> items =
        client.tables.computeIfAbsent(tableName, _ -> new ArrayList<>());
    final String partitionKey = Day.partitionKey(meeting.date());
    final List<MeetingRecord> alreadyThere =
        items.stream()
            .filter(item -> partitionKey.equals(item.get("pk").s()))
            .findFirst()
            .map(Day::fromItem)
            .map(Day::meetings)
            .orElse(List.of());

    items.removeIf(item -> partitionKey.equals(item.get("pk").s()));
    items.add(
        new Day(
                meeting.date(),
                1,
                Stream.concat(alreadyThere.stream(), Stream.of(meeting)).toList())
            .toItem());
    items.add(
        Map.of(
            "pk",
                AttributeValue.builder().s(DayRepository.POINTER_PK_PREFIX + meeting.id()).build(),
            "date", AttributeValue.builder().s(meeting.date()).build()));
  }

  /** Every meeting the table currently holds, read back out of its day items. */
  public static List<MeetingRecord> meetingsIn(
      final FakeDynamoDbClient client, final String tableName) {
    return client.tables.getOrDefault(tableName, List.of()).stream()
        .filter(item -> item.get("pk").s().startsWith(Day.PK_PREFIX))
        .map(Day::fromItem)
        .flatMap(day -> day.meetings().stream())
        .toList();
  }

  /** Seeds meetings and a permissive retention boundary into the fake's meetings table. */
  public static void seed(
      final FakeDynamoDbClient client,
      final String tableName,
      final String earliestRetainedDate,
      final MeetingRecord... meetings) {
    final List<Map<String, AttributeValue>> items = new ArrayList<>(dayItems(meetings));
    items.add(retentionConfig(earliestRetainedDate));
    client.tables.put(tableName, items);
  }
}
