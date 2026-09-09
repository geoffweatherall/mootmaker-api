package com.mootmaker.testsupport;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import module java.base;

/**
 * Minimal in-memory test double covering only the operations the handlers under test use.
 * {@code DatabaseReset}/{@code DatabaseRepairHandler}'s repairs issue their per-item calls
 * concurrently (see {@code ConcurrencyUtils.runInParallel}), so every method here is synchronized -
 * a real {@code DynamoDbClient} handles concurrent calls from multiple threads fine, and this fake
 * needs to behave the same way, since a plain {@code ArrayList} silently drops entries (or throws)
 * under concurrent, unsynchronized mutation.
 */
public class FakeDynamoDbClient implements DynamoDbClient {

    /** Two-character operators must be checked before their one-character prefixes (">=" before ">"). */
    private static final List<String> COMPARATORS = List.of(">=", "<=", "=", ">", "<");

    public final Map<String, List<Map<String, AttributeValue>>> tables = new HashMap<>();

    /**
     * Runs immediately before each transaction commits, so a test can simulate another writer getting
     * in first. Without a hook like this, optimistic-locking retries cannot be exercised at all.
     */
    public Runnable beforeWrite = () -> { };

    /** How many transactions have been attempted, so a test can assert a retry actually happened. */
    public int transactionAttempts;

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public void close() {
    }

    /**
     * Replaces any existing item with the same "id" (matching real DynamoDB PutItem semantics for
     * every table here, which are all keyed by "id" - meeting-participants is the one exception,
     * but nothing under test writes to it via a bare putItem rather than transactWriteItems).
     * Previously this just appended unconditionally, which was fine when only create* handlers
     * existed (never a pre-existing item to collide with) but silently left a stale duplicate
     * behind once update* handlers started overwriting an existing item.
     */
    @Override
    public synchronized PutItemResponse putItem(final PutItemRequest request) {
        replace(request.tableName(), request.item());
        return PutItemResponse.builder().build();
    }

    /**
     * Replaces on whichever partition key the item carries. The meetings table is keyed by "pk" and
     * everything else by "id"; keying only on "id" (as this did) silently appended duplicates for
     * every day item, which looks like data corruption a long way from its cause.
     */
    private void replace(final String tableName, final Map<String, AttributeValue> item) {
        final List<Map<String, AttributeValue>> items = tables.computeIfAbsent(tableName, _ -> new ArrayList<>());
        final String keyName = item.containsKey("pk") ? "pk" : "id";
        final AttributeValue key = item.get(keyName);
        if (key != null) {
            items.removeIf(existing -> key.equals(existing.get(keyName)));
        }
        items.add(item);
    }

    private Map<String, AttributeValue> find(final String tableName, final Map<String, AttributeValue> item) {
        final String keyName = item.containsKey("pk") ? "pk" : "id";
        return tables.getOrDefault(tableName, List.of()).stream()
                .filter(existing -> item.get(keyName).equals(existing.get(keyName)))
                .findFirst()
                .orElse(null);
    }

    /**
     * Only the two condition shapes DayRepository writes are modelled, and anything else throws
     * rather than silently passing - a fake that quietly accepts an unmodelled condition would turn
     * a broken conditional write into a green test.
     */
    private boolean conditionHolds(final String tableName, final Put put) {
        final String condition = put.conditionExpression();
        if (condition == null) {
            return true;
        }
        final Map<String, AttributeValue> existing = find(tableName, put.item());
        if ("attribute_not_exists(pk)".equals(condition)) {
            return existing == null;
        }
        if ("version = :expected".equals(condition)) {
            final AttributeValue expected = put.expressionAttributeValues().get(":expected");
            return existing != null && expected.n().equals(existing.get("version").n());
        }
        throw new UnsupportedOperationException("FakeDynamoDbClient does not model the condition: " + condition);
    }

    /**
     * All-or-nothing, like the real thing: every condition is checked before anything is written.
     * Checking as it goes would let a later failure leave earlier writes applied, which is exactly
     * the behaviour the day-item tests exist to disprove.
     */
    @Override
    public synchronized TransactWriteItemsResponse transactWriteItems(final TransactWriteItemsRequest request) {
        transactionAttempts++;
        beforeWrite.run();

        for (final TransactWriteItem transactItem : request.transactItems()) {
            if (transactItem.put() != null && !conditionHolds(transactItem.put().tableName(), transactItem.put())) {
                throw TransactionCanceledException.builder()
                        .message("ConditionalCheckFailed for " + transactItem.put().item()).build();
            }
        }
        for (final TransactWriteItem transactItem : request.transactItems()) {
            final Put put = transactItem.put();
            if (put != null) {
                replace(put.tableName(), put.item());
            }
            final Delete delete = transactItem.delete();
            if (delete != null) {
                final List<Map<String, AttributeValue>> items = tables.get(delete.tableName());
                if (items != null) {
                    items.removeIf(item -> matchesKey(item, delete.key()));
                }
            }
        }
        return TransactWriteItemsResponse.builder().build();
    }

    @Override
    public synchronized ScanResponse scan(final ScanRequest request) {
        final List<Map<String, AttributeValue>> items = List.copyOf(tables.getOrDefault(request.tableName(), new ArrayList<>()));
        return ScanResponse.builder().items(items).count(items.size()).build();
    }

    @Override
    public synchronized GetItemResponse getItem(final GetItemRequest request) {
        final List<Map<String, AttributeValue>> items = tables.getOrDefault(request.tableName(), new ArrayList<>());
        return items.stream()
                .filter(item -> matchesKey(item, request.key()))
                .findFirst()
                .map(item -> GetItemResponse.builder().item(item).build())
                .orElseGet(() -> GetItemResponse.builder().build());
    }

    /** Matches on every attribute in key, not just "id" - meeting-participants is keyed by personId+sortKey. */
    private static boolean matchesKey(final Map<String, AttributeValue> item, final Map<String, AttributeValue> key) {
        return key.entrySet().stream().allMatch(entry -> entry.getValue().equals(item.get(entry.getKey())));
    }

    /**
     * Supports the key condition shapes the handlers under test issue against a table or GSI -
     * a single equality clause, optionally followed by " AND " and either a simple comparator
     * (=, <, <=, >, >=), a begins_with(...) call, or a BETWEEN ... AND ... range - plus an
     * optional FilterExpression of comparator clauses joined by " AND ". Attribute name tokens
     * starting with "#" are resolved via ExpressionAttributeNames, same as real DynamoDB (used
     * for names that collide with DynamoDB's reserved-word list, e.g. "bucket"). Matches by
     * filtering the table's items directly (as if every GSI were an ALL-projection copy of the
     * base table) rather than modelling indexes or partition/sort key storage, since the fake
     * only needs to behave the same as the real query, not perform like it - it also does NOT
     * model DynamoDB's reserved-word list, so (unlike real DynamoDB) it won't reject an
     * unaliased reserved word used as a literal attribute name.
     */
    @Override
    public synchronized QueryResponse query(final QueryRequest request) {
        final List<Map<String, AttributeValue>> tableItems = tables.getOrDefault(request.tableName(), new ArrayList<>());
        final Map<String, AttributeValue> values = request.expressionAttributeValues();
        final Map<String, String> names = request.expressionAttributeNames() == null ? Map.of() : request.expressionAttributeNames();

        Stream<Map<String, AttributeValue>> matches = tableItems.stream()
                .filter(item -> matchesKeyCondition(item, request.keyConditionExpression(), values, names));
        if (request.filterExpression() != null) {
            matches = matches.filter(item -> matchesFilterExpression(item, request.filterExpression(), values, names));
        }
        final List<Map<String, AttributeValue>> items = matches.toList();
        return QueryResponse.builder().items(items).count(items.size()).build();
    }

    private static boolean matchesKeyCondition(final Map<String, AttributeValue> item, final String keyConditionExpression,
            final Map<String, AttributeValue> values, final Map<String, String> names) {
        final String[] parts = keyConditionExpression.split(" AND ", 2);
        if (!matchesComparator(item, parts[0], values, names)) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        final String rest = parts[1];
        if (rest.startsWith("begins_with(")) {
            return matchesBeginsWith(item, rest, values, names);
        }
        if (rest.contains(" BETWEEN ")) {
            return matchesBetween(item, rest, values, names);
        }
        return matchesComparator(item, rest, values, names);
    }

    private static boolean matchesFilterExpression(final Map<String, AttributeValue> item, final String filterExpression,
            final Map<String, AttributeValue> values, final Map<String, String> names) {
        for (final String clause : filterExpression.split(" AND ")) {
            if (!matchesComparator(item, clause.trim(), values, names)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesComparator(final Map<String, AttributeValue> item, final String condition,
            final Map<String, AttributeValue> values, final Map<String, String> names) {
        for (final String operator : COMPARATORS) {
            final int index = condition.indexOf(" " + operator + " ");
            if (index < 0) {
                continue;
            }
            final AttributeValue actual = item.get(attributeName(condition.substring(0, index).trim(), names));
            final AttributeValue expected = values.get(condition.substring(index + operator.length() + 2).trim());
            if (actual == null) {
                return false;
            }
            final int comparison = actual.s().compareTo(expected.s());
            return switch (operator) {
                case "=" -> comparison == 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                default -> throw new IllegalArgumentException("Unsupported operator: " + operator);
            };
        }
        throw new IllegalArgumentException("Unsupported condition: " + condition);
    }

    private static boolean matchesBeginsWith(final Map<String, AttributeValue> item, final String condition,
            final Map<String, AttributeValue> values, final Map<String, String> names) {
        final String inner = condition.substring("begins_with(".length(), condition.length() - 1);
        final String[] args = inner.split(",");
        final AttributeValue actual = item.get(attributeName(args[0].trim(), names));
        final AttributeValue expected = values.get(args[1].trim());
        return actual != null && actual.s().startsWith(expected.s());
    }

    private static boolean matchesBetween(final Map<String, AttributeValue> item, final String condition,
            final Map<String, AttributeValue> values, final Map<String, String> names) {
        final String[] tokens = condition.split(" ");
        final AttributeValue actual = item.get(attributeName(tokens[0], names));
        if (actual == null) {
            return false;
        }
        final AttributeValue lower = values.get(tokens[2]);
        final AttributeValue upper = values.get(tokens[4]);
        return actual.s().compareTo(lower.s()) >= 0 && actual.s().compareTo(upper.s()) <= 0;
    }

    /** Resolves a "#alias" token via ExpressionAttributeNames; any other token is a literal attribute name. */
    private static String attributeName(final String token, final Map<String, String> names) {
        return token.startsWith("#") ? names.get(token) : token;
    }

    /** Supports only single-table requests with no unprocessed keys, which is all the handlers under test issue. */
    @Override
    public synchronized BatchGetItemResponse batchGetItem(final BatchGetItemRequest request) {
        final Map<String, List<Map<String, AttributeValue>>> responses = new HashMap<>();
        for (final Map.Entry<String, KeysAndAttributes> entry : request.requestItems().entrySet()) {
            final String tableName = entry.getKey();
            final List<Map<String, AttributeValue>> tableItems = tables.getOrDefault(tableName, new ArrayList<>());
            final List<Map<String, AttributeValue>> matches = entry.getValue().keys().stream()
                    .map(key -> key.get("id").s())
                    .flatMap(id -> tableItems.stream().filter(item -> id.equals(item.get("id").s())))
                    .toList();
            responses.put(tableName, matches);
        }
        return BatchGetItemResponse.builder().responses(responses).unprocessedKeys(Map.of()).build();
    }

    @Override
    public synchronized DeleteItemResponse deleteItem(final DeleteItemRequest request) {
        final List<Map<String, AttributeValue>> items = tables.get(request.tableName());
        if (items != null) {
            items.removeIf(item -> matchesKey(item, request.key()));
        }
        return DeleteItemResponse.builder().build();
    }
}
