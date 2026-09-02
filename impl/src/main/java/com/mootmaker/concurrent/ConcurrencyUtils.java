package com.mootmaker.concurrent;

import module java.base;

/**
 * Bounded-concurrency helper shared by {@code DatabaseResetHandler} and {@code DatabaseRepairHandler}
 * (see {@code com.mootmaker.handler}) to speed up their independent per-item AWS calls without
 * throwing an unnecessary burst of requests at DynamoDB/Cognito. Both tools previously carried their
 * own identical copy of this when they were separate Maven projects in mootmaker-admin-tools; merging
 * into this one repo removed the reason to keep two copies.
 *
 * <p>{@code mootmaker-demo-data}'s sample-data-generator keeps its own separate copy - it stays a
 * different repository, with no shared-code mechanism to this one.
 */
public final class ConcurrencyUtils {

    /**
     * Deliberately modest - enough to meaningfully speed up a run without throwing an unnecessary
     * burst of requests at DynamoDB/Cognito.
     */
    public static final int MAX_CONCURRENT_REQUESTS = 8;

    private ConcurrencyUtils() {
    }

    /**
     * Runs {@code action} for every item, on a bounded pool of {@value #MAX_CONCURRENT_REQUESTS}
     * threads, and waits for them all to finish. The first failure is rethrown after every task has
     * completed, same as a sequential loop would have failed on the first bad item - just not
     * necessarily the same item, since order isn't guaranteed under parallel execution.
     */
    public static <T> void runInParallel(final List<T> items, final Consumer<T> action) {
        if (items.isEmpty()) {
            return;
        }
        final ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT_REQUESTS, items.size()));
        try {
            final List<Future<?>> futures = new ArrayList<>(items.size());
            for (final T item : items) {
                futures.add(executor.submit(() -> action.accept(item)));
            }
            RuntimeException firstFailure = null;
            for (final Future<?> future : futures) {
                try {
                    future.get();
                } catch (final ExecutionException e) {
                    if (firstFailure == null) {
                        final Throwable cause = e.getCause();
                        firstFailure = cause instanceof RuntimeException runtimeException
                                ? runtimeException
                                : new IllegalStateException(cause);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for parallel tasks to finish", e);
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        } finally {
            executor.shutdown();
        }
    }
}
