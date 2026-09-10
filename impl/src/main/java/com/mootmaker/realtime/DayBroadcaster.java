package com.mootmaker.realtime;

import module java.base;

/**
 * Tells connected clients that the given dates changed, so they can refetch them.
 *
 * <p>An interface with a do-nothing implementation rather than a nullable publisher, for two
 * reasons. Handlers never branch on whether broadcasting is configured - the same jar backs
 * database-reset and database-repair, which have no grant to publish and no reason to - and unit
 * tests can assert exactly which dates were broadcast without reaching AppSync, which is the only
 * way to test that a rejected write broadcasts NOTHING.
 */
@FunctionalInterface
public interface DayBroadcaster {

    /** Never throws: a failed broadcast must not fail a write that has already committed. */
    void publish(Collection<String> dates);

    /** Used wherever broadcasting is not configured. */
    DayBroadcaster NONE = dates -> { };
}
