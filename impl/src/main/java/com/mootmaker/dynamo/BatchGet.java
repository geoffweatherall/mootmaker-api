package com.mootmaker.dynamo;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import module java.base;

/**
 * The DynamoDB mechanics shared by {@link RoomRepository} and {@code PersonRepository} - both are
 * small tables keyed by a plain {@code id}, and neither has invariants of its own worth guarding the
 * way a day item does. Package-private on purpose: this is plumbing for the repositories, not a
 * general-purpose helper for handlers to reach for. That distinction is what the old {@code
 * BatchLoader} lost by being public and parameterised by table name.
 */
final class BatchGet {

    private static final int BATCH_GET_ITEM_LIMIT = 100;

    private BatchGet() {
    }

    static Map<String, Map<String, AttributeValue>> byId(
            final DynamoDbClient dynamoDbClient, final String tableName, final Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return chunk(List.copyOf(ids)).stream()
                .map(chunkIds -> CompletableFuture.supplyAsync(() -> fetchChunk(dynamoDbClient, tableName, chunkIds)))
                .toList()
                .stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toMap(item -> item.get("id").s(), Function.identity(), (first, _) -> first));
    }

    static List<Map<String, AttributeValue>> scan(final DynamoDbClient dynamoDbClient, final String tableName) {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).consistentRead(true).build()).items();
    }

    static void put(final DynamoDbClient dynamoDbClient, final String tableName, final Map<String, AttributeValue> item) {
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    private static List<Map<String, AttributeValue>> fetchChunk(
            final DynamoDbClient dynamoDbClient, final String tableName, final List<String> ids) {
        Map<String, KeysAndAttributes> requestItems = Map.of(tableName, KeysAndAttributes.builder()
                .keys(ids.stream().map(id -> Map.of("id", AttributeValue.builder().s(id).build())).toList())
                .build());

        final List<Map<String, AttributeValue>> items = new ArrayList<>();
        while (requestItems != null && !requestItems.isEmpty()) {
            final BatchGetItemResponse response = dynamoDbClient.batchGetItem(
                    BatchGetItemRequest.builder().requestItems(requestItems).build());
            items.addAll(response.responses().getOrDefault(tableName, List.of()));
            requestItems = response.unprocessedKeys();
        }
        return items;
    }

    private static <T> List<List<T>> chunk(final List<T> items) {
        final List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += BATCH_GET_ITEM_LIMIT) {
            chunks.add(items.subList(i, Math.min(i + BATCH_GET_ITEM_LIMIT, items.size())));
        }
        return chunks;
    }
}
