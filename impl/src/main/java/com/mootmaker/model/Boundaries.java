package com.mootmaker.model;

import module java.base;

/**
 * The window meetings may be viewed and booked in. One pair of dates serves both - there is no third
 * concept - and the server is the only authority on either.
 *
 * <p>That single-authority point is the whole reason this is returned to clients rather than computed
 * by them. If the browser worked out "30 days before today" for itself there would be two authorities
 * on one fact: the cleanup job uses the server's date, the browser uses the user's. A user in UTC+13,
 * or with a skewed clock, would then ask for a day the server already considers expired. Padding the
 * client's limit by a day would hide that disagreement rather than remove it, and a test would not
 * catch it, because the test would encode the same assumption the code does.
 */
public record Boundaries(String earliestRetainedDate, String latestBookableDate) {

    public boolean contains(final String date) {
        return date.compareTo(earliestRetainedDate) >= 0 && date.compareTo(latestBookableDate) <= 0;
    }

    public Map<String, Object> toResponseMap() {
        return Map.of("earliestRetainedDate", earliestRetainedDate, "latestBookableDate", latestBookableDate);
    }
}
