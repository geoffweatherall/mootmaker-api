package com.mootmaker.handler;

import com.mootmaker.model.MeetingError;

import module java.base;

/**
 * Carries validation failures out of a read-modify-write.
 *
 * <p>An exception rather than a return value because the checks that need it run <i>inside</i>
 * {@code DayRepository.mutate}'s change function, which must return a day. Throwing both reports the
 * failure and abandons the write, which is exactly the behaviour wanted: a day that is full, or a
 * room that has just been taken, must not be written.
 *
 * <p>These are validation results, not faults. Callers turn them into the typed {@code errors} array
 * the schema promises - a client renders them, and none of them is ever a 500.
 */
final class MeetingRejected extends RuntimeException {

    private final transient List<String> errors;

    MeetingRejected(final MeetingError... errors) {
        this(Arrays.stream(errors).map(MeetingError::name).toList());
    }

    MeetingRejected(final List<String> errors) {
        super(String.join(", ", errors), null, false, false);
        this.errors = List.copyOf(errors);
    }

    List<String> errors() {
        return errors;
    }
}
