package com.mootmaker.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import module java.base;

/**
 * Invokes the {@code history-cleanup} Lambda directly, the same way {@link DatabaseReset} invokes
 * reset: AWS IAM auth with whatever credentials are running the tests, and a function name computed
 * the same deterministic way Terraform names it.
 *
 * <p>Invoked rather than waited for on purpose. The EventBridge rule is deliberately disabled outside
 * {@code test} and {@code production}, because a schedule firing mid-run would make these tests
 * nondeterministic - so the job runs here only when a test asks it to.
 */
final class HistoryCleanup {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final LambdaClient CLIENT = LambdaClient.builder().build();

    private HistoryCleanup() {
    }

    /** Runs the job for real. Returns its summary: the boundary it advanced to, and the dates it deleted. */
    static JsonNode run() {
        return invoke("{}");
    }

    /** Reports what would go without moving the boundary or deleting anything. */
    static JsonNode dryRun() {
        return invoke("{\"dryRun\":true}");
    }

    private static JsonNode invoke(final String payload) {
        final String functionName = requireEnv("HISTORY_CLEANUP_FUNCTION_NAME");
        final InvokeResponse response = CLIENT.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String(payload))
                .build());

        final String body = response.payload().asUtf8String();
        if (response.functionError() != null) {
            throw new IllegalStateException("history-cleanup Lambda (" + functionName + ") failed ("
                    + response.functionError() + "): " + body);
        }
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (final Exception e) {
            throw new IllegalStateException("Could not parse the history-cleanup response: " + body, e);
        }
    }

    private static String requireEnv(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required to run acceptance tests "
                    + "against the deployed mootmaker API. Run the tests via ./verify.sh, which exports it.");
        }
        return value;
    }
}
