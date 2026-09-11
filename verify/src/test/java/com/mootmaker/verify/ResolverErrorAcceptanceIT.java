package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import module java.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That a resolver's own error message reaches the client at all.
 *
 * <p>It did not. The direct-Lambda response template was {@code $util.toJson($ctx.result)} with no
 * {@code $ctx.error} branch, so a handler that threw produced a null result and the client saw only
 * AppSync's nullability complaint:
 *
 * <pre>Cannot return null for non-nullable type: 'Workspace' within parent 'Query' (/workspace)
 * </pre>
 *
 * <p>Every server-side limit, bad request and internal fault arrived as that one sentence, which
 * names the schema rather than the problem. This is deliberately an ACCEPTANCE test: the handler's
 * unit tests already prove it throws the right message, and they passed throughout. What was broken
 * was the wiring between the handler and the client, which only a deployed environment contains.
 *
 * <p>Note this never affected ordinary validation. {@code MeetingError}, {@code RoomError} and
 * {@code PersonError} come back in typed {@code errors} arrays as normal return values rather than
 * exceptions, and were always visible.
 */
class ResolverErrorAcceptanceIT {

  /** One more than Limits.MAX_DATES_PER_REQUEST, which the schema documents as 42. */
  private static final int OVER_THE_DATE_LIMIT = 43;

  private static final GraphQlClient CLIENT = GraphQlClient.fromEnvironment();

  @Test
  @DisplayName(
      "a resolver's own message reaches the client, rather than a generic non-nullable complaint")
  void surfacesTheResolversMessage() {
    final List<String> tooManyDates =
        IntStream.range(0, OVER_THE_DATE_LIMIT)
            .mapToObj(offset -> LocalDate.now().plusDays(offset).toString())
            .toList();

    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                CLIENT.execute(
                    "query TooManyDates($dates: [String!]) { workspace(dates: $dates) { "
                        + "days { date } } }",
                    Map.of("dates", tooManyDates)));

    // The number the caller asked for and the limit they hit - enough to fix the call without
    // reading the server's source.
    assertThat(thrown.getMessage(), containsString("Too many dates requested"));
    assertThat(thrown.getMessage(), containsString(String.valueOf(OVER_THE_DATE_LIMIT)));
    assertThat(thrown.getMessage(), containsString("42"));
  }

  @Test
  @DisplayName("the nullability complaint no longer stands in for the real reason")
  void doesNotReportOnlyTheNullabilityError() {
    // Asserted separately and deliberately: AppSync may still ADD its nullability error
    // alongside the real one, and that is fine. What must never happen again is that being the
    // only thing present. Written as an assertion on the message rather than on error count, so
    // it keeps working if AppSync changes how many entries it returns.
    final List<String> tooManyDates =
        IntStream.range(0, OVER_THE_DATE_LIMIT)
            .mapToObj(offset -> LocalDate.now().plusDays(offset).toString())
            .toList();

    final IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                CLIENT.execute(
                    "query TooManyDates($dates: [String!]) { workspace(dates: $dates) { "
                        + "days { date } } }",
                    Map.of("dates", tooManyDates)));

    final String withoutTheRealReason = thrown.getMessage().replace("Too many dates requested", "");
    assertThat(
        "the only error present was the nullability one",
        withoutTheRealReason,
        not(containsString("Too many dates requested")));
    assertThat(thrown.getMessage(), containsString("Too many dates requested"));
  }
}
