package com.mootmaker.dynamo;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * Measures a DynamoDB item the way DynamoDB itself does, in UTF-8 bytes.
 *
 * <p>This is what makes layer 3 of the item-size guarantee a measurement rather than another
 * estimate. The limits in {@code Limits} are a model of this; a model can drift when the persisted
 * shape changes, so the write path measures the real serialised item and refuses anything over the
 * cap regardless of what the model predicted.
 *
 * <p>Following AWS's documented rules: an attribute costs its name plus its value; strings and binary
 * cost their raw length; a number costs roughly one byte per two significant digits plus one; lists
 * and maps carry three bytes of overhead plus one byte per element, and a map's element names count
 * too. It is close rather than exact - which is fine, because it is only ever used with headroom
 * against the cap, never to predict a value someone depends on.
 */
public final class ItemSizer {

    private static final int CONTAINER_OVERHEAD_BYTES = 3;
    private static final int ELEMENT_OVERHEAD_BYTES = 1;

    private ItemSizer() {
    }

    public static int sizeOf(final Map<String, AttributeValue> item) {
        int total = 0;
        for (final Map.Entry<String, AttributeValue> attribute : item.entrySet()) {
            total += utf8Length(attribute.getKey()) + sizeOf(attribute.getValue());
        }
        return total;
    }

    public static int sizeOf(final AttributeValue value) {
        if (value.s() != null) {
            return utf8Length(value.s());
        }
        if (value.n() != null) {
            return numberBytes(value.n());
        }
        if (value.b() != null) {
            return value.b().asByteArray().length;
        }
        if (value.bool() != null || Boolean.TRUE.equals(value.nul())) {
            return 1;
        }
        if (value.hasL()) {
            int total = CONTAINER_OVERHEAD_BYTES;
            for (final AttributeValue element : value.l()) {
                total += ELEMENT_OVERHEAD_BYTES + sizeOf(element);
            }
            return total;
        }
        if (value.hasM()) {
            int total = CONTAINER_OVERHEAD_BYTES;
            for (final Map.Entry<String, AttributeValue> entry : value.m().entrySet()) {
                total += ELEMENT_OVERHEAD_BYTES + utf8Length(entry.getKey()) + sizeOf(entry.getValue());
            }
            return total;
        }
        if (value.hasSs()) {
            return value.ss().stream().mapToInt(ItemSizer::utf8Length).sum();
        }
        if (value.hasNs()) {
            return value.ns().stream().mapToInt(ItemSizer::numberBytes).sum();
        }
        return 0;
    }

    /** UTF-8 bytes, not {@code String.length()} - Java strings are UTF-16, and the two differ. */
    public static int utf8Length(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int numberBytes(final String number) {
        final String digits = number.replaceAll("[^0-9]", "").replaceFirst("^0+(?=.)", "");
        return Math.max(1, (digits.length() + 1) / 2) + 1 + (number.startsWith("-") ? 1 : 0);
    }
}
