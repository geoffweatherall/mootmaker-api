package com.mootmaker.limits;

import module java.base;

/**
 * The meeting subject's length rule: <b>at most {@link Limits#MAX_SUBJECT_BYTES} bytes of
 * NFC-normalised UTF-8</b>. Three things about that are easy to get wrong, and all three have
 * consequences a user would see.
 *
 * <p><b>UTF-8 bytes, not {@code String.length()}.</b> Java strings are UTF-16, so {@code length()}
 * counts code <i>units</i>: an emoji outside the Basic Multilingual Plane counts 2 there and 4 in
 * UTF-8. Neither number is the other, and DynamoDB sizes string attributes in UTF-8 - so measuring
 * in the same unit the storage bills in keeps the limit and the item cost one quantity rather than
 * two that drift.
 *
 * <p><b>Normalise to NFC first.</b> "é" is either one code point (2 bytes) or "e" plus a combining
 * accent (3 bytes), depending on the writer's keyboard and OS. Without normalising, two visually
 * identical subjects have different byte counts and one can be rejected - which is unexplainable to
 * the person typing it.
 *
 * <p><b>Reject, never truncate.</b> Cutting a string at a byte boundary can split a character
 * mid-sequence and produce mojibake. A client-side counter may truncate, but on grapheme boundaries.
 */
public final class Subjects {

    private Subjects() {
    }

    /** The form that is stored: what is measured and what is written must be the same string. */
    public static String normalise(final String subject) {
        return subject == null ? null : Normalizer.normalize(subject, Normalizer.Form.NFC);
    }

    public static int byteLength(final String normalisedSubject) {
        return normalisedSubject.getBytes(StandardCharsets.UTF_8).length;
    }

    public static boolean isWithinLimit(final String normalisedSubject) {
        return byteLength(normalisedSubject) <= Limits.MAX_SUBJECT_BYTES;
    }
}
