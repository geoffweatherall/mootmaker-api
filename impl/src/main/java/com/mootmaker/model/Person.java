package com.mootmaker.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * {@code cognitoSub} is null for people added directly (e.g. guests with no login), and set to
 * the Cognito user's {@code sub} for people created by the PostConfirmation sign-up trigger, so a
 * future account-deletion flow can find and remove the Person linked to a deleted Cognito user.
 * It is a backend-only linking attribute, never exposed over GraphQL.
 *
 * <p>{@code dateFormat}/{@code timeFormat} are the owner's display preferences, exposed over
 * GraphQL as non-null. The DynamoDB attributes behind them are optional - every Person written
 * before the preferences feature existed lacks them, and guest Persons never sign in to set one -
 * so {@link #fromItem} substitutes the defaults. That substitution is the single point holding the
 * schema's non-null guarantee up; see {@code PersonTest}.
 */
public record Person(String id, String name, String cognitoSub, DateFormat dateFormat, TimeFormat timeFormat) {

    private static final DateFormat DEFAULT_DATE_FORMAT = DateFormat.Iso;
    private static final TimeFormat DEFAULT_TIME_FORMAT = TimeFormat.TwentyFourHour;

    /**
     * Normalises null preferences to the defaults, so a Person can never carry a null one however
     * it was constructed - the same guarantee the GraphQL schema makes.
     */
    public Person {
        dateFormat = dateFormat == null ? DEFAULT_DATE_FORMAT : dateFormat;
        timeFormat = timeFormat == null ? DEFAULT_TIME_FORMAT : timeFormat;
    }

    public Person(final String id, final String name) {
        this(id, name, null);
    }

    public Person(final String id, final String name, final String cognitoSub) {
        this(id, name, cognitoSub, null, null);
    }

    public Map<String, AttributeValue> toItem() {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put("id", AttributeValue.builder().s(id).build());
        item.put("name", AttributeValue.builder().s(name).build());
        if (cognitoSub != null) {
            item.put("cognitoSub", AttributeValue.builder().s(cognitoSub).build());
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
        final AttributeValue cognitoSub = item.get("cognitoSub");
        return new Person(
                item.get("id").s(),
                item.get("name").s(),
                cognitoSub != null ? cognitoSub.s() : null,
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
