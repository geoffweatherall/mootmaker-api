package com.mootmaker.verify;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import module java.base;

/**
 * Invokes the {@code database-reset} Lambda directly (AWS IAM auth, via whatever credentials are
 * running the tests), rather than through GraphQL - {@code Mutation.reset} no longer exists (see
 * the API README's "Reset and real user accounts" section). Most acceptance tests call
 * {@link #reset()} immediately before they act, so they can't be thrown off by data left behind by
 * another test or a previous run.
 *
 * <p>Reads {@code DATABASE_RESET_FUNCTION_NAME} (exported by {@code verify.sh}, computed the same
 * deterministic way the Lambda itself is named in Terraform) and picks up its AWS region from the
 * {@code AWS_REGION} environment variable {@code authenticate.sh} exports, the same way the AWS
 * SDK would for any other caller.
 */
final class DatabaseReset {

    /**
     * The Lambda's own configured timeout is the AWS maximum, 900 seconds (see
     * designs/admin-tools-into-api.md) - a run that legitimately takes close to that long must not
     * be reported as a client-side failure while the Lambda keeps running (or even succeeds)
     * regardless. The SDK's own default {@code apiCallTimeout} is far shorter than that.
     */
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(910);

    /** One client is enough for the whole test run - the SDK client is safe to share across threads. */
    private static final LambdaClient CLIENT = LambdaClient.builder()
            .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(API_CALL_TIMEOUT).build())
            .build();

    private DatabaseReset() {
    }

    static void reset() {
        final String functionName = requireEnv("DATABASE_RESET_FUNCTION_NAME");

        final InvokeResponse response = CLIENT.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String("{}"))
                .build());

        if (response.functionError() != null) {
            throw new IllegalStateException("database-reset Lambda (" + functionName + ") failed ("
                    + response.functionError() + "): " + response.payload().asUtf8String());
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
