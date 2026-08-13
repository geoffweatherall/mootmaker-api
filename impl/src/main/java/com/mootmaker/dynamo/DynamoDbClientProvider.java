package com.mootmaker.dynamo;

import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

/** Lazily-built singleton, reused across warm Lambda invocations to avoid repeated client setup cost. */
public final class DynamoDbClientProvider {

    private static volatile DynamoDbClient client;

    // org.crac.Context only holds a WeakReference to a registered Resource, so this field has to
    // exist purely to keep a strong reference alive - an unreferenced Resource can be garbage
    // collected before Lambda ever calls afterRestore() on it.
    private static Resource afterRestorePriming;

    private DynamoDbClientProvider() {
    }

    public static DynamoDbClient client() {
        if (client == null) {
            synchronized (DynamoDbClientProvider.class) {
                if (client == null) {
                    client = DynamoDbClient.builder().build();
                    // Runs synchronously here, in the same INIT-phase code path that constructs
                    // the client - strictly before Lambda takes a SnapStart snapshot (if
                    // SnapStart is on), so no beforeCheckpoint hook is needed for this half; a
                    // plain eager call in this static/lazy-init context already lands before the
                    // checkpoint. See primeConnection's javadoc for why the *other* half (the
                    // network connection, redone in afterRestorePriming below) does need one.
                    primeConnection(client);
                    afterRestorePriming = new AfterRestorePriming(client);
                    Core.getGlobalContext().register(afterRestorePriming);
                }
            }
        }
        return client;
    }

    /**
     * Loads/JIT-warms the DynamoDB SDK's marshallers, HTTP client internals, and credential/
     * signing chain by making one real, side-effect-free DescribeTable call - the same cost a
     * handler's first real request would otherwise pay at interpreter speed. Called eagerly from
     * {@link #client()} above (baking it into the SnapStart snapshot, since it runs before
     * checkpoint) and again from {@link AfterRestorePriming} (since the TCP/TLS connection itself
     * never survives a snapshot/restore cycle regardless of what's primed - see AWS's SnapStart
     * networking guidance - so it always needs re-establishing post-restore, just cheaply now
     * that the classes are already loaded). ROOMS_TABLE_NAME is set for every handler regardless
     * of whether it uses the rooms table (see lambda.tf's lambda_env_vars), so any table name
     * primes the same shared client equally well.
     */
    private static void primeConnection(final DynamoDbClient client) {
        final String tableName = System.getenv("ROOMS_TABLE_NAME");
        if (tableName != null) {
            client.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
        }
    }

    /**
     * Unlike class loading/JIT state, a snapshot's network connections don't survive restore, and
     * nothing re-runs static/lazy-init code on restore - only a registered CRaC afterRestore hook
     * fires again each time a new execution environment resumes from the same snapshot, which is
     * why this half can't just be more eager code the way {@link #client()}'s first
     * {@code primeConnection} call is.
     */
    private record AfterRestorePriming(DynamoDbClient client) implements Resource {

        @Override
        public void beforeCheckpoint(final Context<? extends Resource> context) {
        }

        @Override
        public void afterRestore(final Context<? extends Resource> context) {
            primeConnection(client);
        }
    }
}
