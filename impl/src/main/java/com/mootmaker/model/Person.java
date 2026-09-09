package com.mootmaker.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * {@code cognitoSubs} holds every Cognito account linked to this person - empty for people added
 * directly (guests with no login), and one entry for anyone created by the PostConfirmation sign-up
 * trigger. Backend-only, never exposed over GraphQL.
 *
 * <p><b>A list rather than a single value, and that is the whole point.</b> The forward direction -
 * which Person is this caller? - is answered by the {@code custom:personId} claim on the token, at no
 * cost. This attribute answers only the reverse, which account deletion needs, and it is free to read
 * because deletion already holds the Person item. Being a list means a second sign-in method (a
 * federated identity provider alongside a password) is a data operation rather than a schema change:
 * Cognito issues a DIFFERENT sub for a federated user, so a single-valued link would give one human
 * two Persons and two calendars.
 *
 * <p>{@code dateFormat}/{@code timeFormat} are the owner's display preferences, exposed over
 * GraphQL as non-null. The DynamoDB attributes behind them are optional - every Person written
 * before the preferences feature existed lacks them, and guest Persons never sign in to set one -
 * so {@link #fromItem} substitutes the defaults. That substitution is the single point holding the
 * schema's non-null guarantee up; see {@code PersonTest}.
 */
public record Person(String id, String name, List<String> cognitoSubs, DateFormat dateFormat, TimeFormat timeFormat) {

    private static final DateFormat DEFAULT_DATE_FORMAT = DateFormat.Iso;
    private static final TimeFormat DEFAULT_TIME_FORMAT = TimeFormat.TwentyFourHour;

    /**
     * Normalises null preferences to the defaults, so a Person can never carry a null one however
     * it was constructed - the same guarantee the GraphQL schema makes.
     */
    public Person {
        dateFormat = dateFormat == null ? DEFAULT_DATE_FORMAT : dateFormat;
        timeFormat = timeFormat == null ? DEFAULT_TIME_FORMAT : timeFormat;
        cognitoSubs = cognitoSubs == null ? List.of() : List.copyOf(cognitoSubs);
    }

    public Person(final String id, final String name) {
        this(id, name, null);
    }

    /** Convenience for the common case of exactly one linked account, or none when null. */
    public Person(final String id, final String name, final String cognitoSub) {
        this(id, name, cognitoSub == null ? List.of() : List.of(cognitoSub), null, null);
    }

    /** True when at least one Cognito account is linked - i.e. this is a person who can sign in. */
    public boolean isLinked() {
        return !cognitoSubs.isEmpty();
    }

    public Map<String, AttributeValue> toItem() {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().s(id).build());
        item.put("name", AttributeValue.builder().s(name).build());
        if (!cognitoSubs.isEmpty()) {
            item.put("cognitoSubs", AttributeValue.builder()
                    .l(cognitoSubs.stream().map(sub -> AttributeValue.builder().s(sub).build()).toList())
                    .build());
        }
        item.put("dateFormat", AttributeValue.builder().s(dateFormat.name()).build());
        item.put("timeFormat", AttributeValue.builder().s(timeFormat.name()).build());
        return item;
    }

    public Map<String, Object> toResponseMap() {
        final Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("dateFormat", dateFormat.name());
        map.put("timeFormat", timeFormat.name());
        return map;
    }

    public static Person fromItem(final Map<String, AttributeValue> item) {
        final AttributeValue cognitoSubs = item.get("cognitoSubs");
        return new Person(
                item.get("id").s(),
                item.get("name").s(),
                cognitoSubs == null ? List.<String>of() : cognitoSubs.l().stream().map(AttributeValue::s).toList(),
                readEnum(item.get("dateFormat"), DateFormat::valueOf, DEFAULT_DATE_FORMAT),
                readEnum(item.get("timeFormat"), TimeFormat::valueOf, DEFAULT_TIME_FORMAT));
    }

    /**
     * Absent attribute means "never chose", so the default applies. An unrecognised value means
     * the stored data predates or postdates this build's enum; falling back to the default beats
     * failing the whole read for a display preference nothing downstream depends on.
     */
    private static <E> E readEnum(final AttributeValue stored, final Function<String, E> parse, final E fallback) {
        if (stored == null || stored.s() == null) {
            return fallback;
        }
        try {
            return parse.apply(stored.s());
        } catch (final IllegalArgumentException e) {
            return fallback;
        }
    }
}
