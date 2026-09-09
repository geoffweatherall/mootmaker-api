package com.mootmaker.dynamo;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import module java.base;

/**
 * A deliberately narrow test double: single-table, keyed on {@code pk}, and - the reason it exists
 * separately from the handler package's fake - it actually <b>honours condition expressions</b>.
 *
 * <p>The handler fake keys on {@code id} and ignores conditions entirely, which is fine for the code
 * it serves but would make every optimistic-locking test here pass vacuously. A fake that cannot fail
 * the way production fails is worse than no fake at all, because it produces green tests for broken
 * retry logic.
 *
 * <p>Only the two forms {@link DayRepository} actually writes are understood:
 * {@code attribute_not_exists(pk)} and {@code version = :expected}. Anything else throws, so a future
 * condition cannot slip through unmodelled.
 */
class FakeDayTable implements DynamoDbClient {

    final Map<String, Map<String, AttributeValue>> items = new LinkedHashMap<>();

    /** Runs immediately before each transaction commits, to simulate another writer getting in first. */
    Runnable beforeWrite = () -> { };

    int transactionAttempts;

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public void close() {
    }

    @Override
    public GetItemResponse getItem(final GetItemRequest request) {
        final Map<String, AttributeValue> item = items.get(request.key().get("pk").s());
        return GetItemResponse.builder().item(item == null ? Map.of() : item).build();
    }

    @Override
    public ScanResponse scan(final ScanRequest request) {
        final List<Map<String, AttributeValue>> all = List.copyOf(items.values());
        return ScanResponse.builder().items(all).count(all.size()).build();
    }

    @Override
    public TransactWriteItemsResponse transactWriteItems(final TransactWriteItemsRequest request) {
        transactionAttempts++;
        beforeWrite.run();

        // Real DynamoDB applies a transaction all-or-nothing, so conditions are all checked before
        // anything is written. Checking as we go would let a later failure leave earlier writes
        // applied - which is exactly the behaviour these tests are meant to prove cannot happen.
        for (final TransactWriteItem transactItem : request.transactItems()) {
            if (transactItem.put() != null && !conditionHolds(transactItem.put())) {
                throw TransactionCanceledException.builder()
                        .message("ConditionalCheckFailed for pk " + transactItem.put().item().get("pk").s()).build();
            }
        }
        for (final TransactWriteItem transactItem : request.transactItems()) {
            final Put put = transactItem.put();
            if (put != null) {
                items.put(put.item().get("pk").s(), put.item());
            }
            final Delete delete = transactItem.delete();
            if (delete != null) {
                items.remove(delete.key().get("pk").s());
            }
        }
        return TransactWriteItemsResponse.builder().build();
    }

    private boolean conditionHolds(final Put put) {
        final String condition = put.conditionExpression();
        if (condition == null) {
            return true;
        }
        final Map<String, AttributeValue> existing = items.get(put.item().get("pk").s());
        if ("attribute_not_exists(pk)".equals(condition)) {
            return existing == null;
        }
        if ("version = :expected".equals(condition)) {
            final AttributeValue expected = put.expressionAttributeValues().get(":expected");
            return existing != null && expected.n().equals(existing.get("version").n());
        }
        throw new UnsupportedOperationException("FakeDayTable does not model the condition: " + condition);
    }
}
