package com.mootmaker.handler;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.MasterKeyProvider;
import com.amazonaws.encryptionsdk.kms.KmsMasterKeyProvider;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/**
 * Cognito {@code CustomEmailSender} trigger: when enabled (see {@link #tableName}), decrypts the
 * verification code Cognito generated for a sign-up or forgot-password request and writes it to a
 * DynamoDB table, so automated tests can read it directly via the AWS SDK instead of needing to
 * read real email - see testing-strategy.md's "Email verification code bypass" section for the
 * full design and why this trigger (not {@code CustomMessage}, which only ever receives a
 * placeholder token, never the real code) is the one that can do this.
 *
 * <p>Configuring this trigger hands it Cognito's email sending entirely for this user pool - there
 * is no way to keep Cognito's own default sending active alongside it. When enabled, this handler
 * deliberately sends no email at all rather than reimplementing sending: nothing in an ephemeral,
 * test-only environment needs to actually receive it (see Option 2 in the same testing-strategy.md
 * section for the separate mechanism that verifies real email delivery). When disabled ({@link
 * #tableName} blank, which is how every non-ephemeral environment is configured), this handler
 * still runs but returns the event's own {@code response} object completely untouched, which
 * Cognito interprets as "the trigger did nothing" and falls through to... except CustomEmailSender
 * has no such fallback: <b>configuring this trigger at all disables Cognito's default sending
 * unconditionally</b>, so it must only ever be wired into {@code lambda_config} for an
 * environment where {@link #tableName} is guaranteed non-blank - see cognito.tf's dynamic block.
 *
 * <p>Like {@link PostConfirmationCreatePersonHandler}, a thrown exception here is treated by
 * Cognito as a failure of the triggering API call itself (unlike PostConfirmation, this one runs
 * <em>before</em> anything user-visible has succeeded, so a failure here genuinely should surface
 * as a failed sign-up/reset-code request rather than being silently swallowed) - but a failure
 * limited to <em>this handler's own bypass bookkeeping</em> (the DynamoDB write, the decrypt call)
 * must not block a real code request from succeeding, so those specific steps are wrapped and
 * logged rather than thrown.
 */
public class CustomEmailSenderBypassHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomEmailSenderBypassHandler.class);
    private static final Set<String> CAPTURED_TRIGGER_SOURCES =
            Set.of("CustomEmailSender_SignUp", "CustomEmailSender_ForgotPassword");

    private final DynamoDbClient dynamoDbClient;
    private final AwsCrypto awsCrypto;
    private final MasterKeyProvider<?> masterKeyProvider;
    private final String tableName;

    public CustomEmailSenderBypassHandler() {
        this(DynamoDbClientProvider.client(), AwsCrypto.builder().build(), buildMasterKeyProvider(),
                System.getenv().getOrDefault("TEST_EMAIL_CODES_TABLE_NAME", ""));
    }

    CustomEmailSenderBypassHandler(final DynamoDbClient dynamoDbClient, final AwsCrypto awsCrypto,
            final MasterKeyProvider<?> masterKeyProvider, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.awsCrypto = awsCrypto;
        this.masterKeyProvider = masterKeyProvider;
        this.tableName = tableName;
    }

    /** {@code null} (rather than eagerly resolving a KMS ARN) when the bypass is disabled - no key exists then. */
    private static MasterKeyProvider<?> buildMasterKeyProvider() {
        final String keyArn = System.getenv("TEST_EMAIL_BYPASS_KMS_KEY_ARN");
        return keyArn == null || keyArn.isBlank() ? null : KmsMasterKeyProvider.builder().buildStrict(keyArn);
    }

    @Override
    public Map<String, Object> handleRequest(final Map<String, Object> event, final Context context) {
        if (tableName.isBlank()) {
            // Disabled for this environment (see class javadoc) - Cognito still invokes this
            // trigger for every send once it's wired into lambda_config, but cognito.tf only ever
            // wires it in for an environment where the bypass is enabled, so reaching here with a
            // blank table name would mean the Terraform side got that invariant wrong.
            LOGGER.error("CustomEmailSenderBypassHandler invoked with no bypass table configured - refusing to suppress sending");
            return event;
        }

        final String triggerSource = (String) event.get("triggerSource");
        if (CAPTURED_TRIGGER_SOURCES.contains(triggerSource)) {
            captureCode(event);
        }

        // No emailMessage/emailSubject set on the response: this trigger has taken over sending
        // entirely for this pool, and an ephemeral environment has no real recipient who needs to
        // receive anything - see class javadoc.
        return event;
    }

    private void captureCode(final Map<String, Object> event) {
        try {
            final Map<String, Object> request = castToMap(event.get("request"));
            final Map<String, Object> userAttributes = castToMap(request.get("userAttributes"));
            final String email = (String) userAttributes.get("email");
            final String encodedCiphertext = (String) request.get("code");

            final byte[] ciphertext = Base64.getDecoder().decode(encodedCiphertext);
            final CryptoResult<byte[], ?> result = awsCrypto.decryptData(masterKeyProvider, ciphertext);
            final String code = new String(result.getResult(), StandardCharsets.UTF_8);

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "email", AttributeValue.builder().s(email).build(),
                            "code", AttributeValue.builder().s(code).build(),
                            "triggerSource", AttributeValue.builder().s((String) event.get("triggerSource")).build(),
                            // DynamoDB TTL attribute (see dynamodb.tf) - a stale code from an
                            // abandoned test run is never useful, so let it expire rather than
                            // accumulating indefinitely in an environment nothing else cleans up.
                            "expiresAt", AttributeValue.builder()
                                    .n(String.valueOf(Instant.now().plus(Duration.ofHours(1)).getEpochSecond()))
                                    .build()))
                    .build());
            LOGGER.info("Captured bypass verification code for '{}'", email);
        } catch (final RuntimeException e) {
            LOGGER.error("Failed to capture bypass verification code", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return (Map<String, Object>) value;
    }
}
