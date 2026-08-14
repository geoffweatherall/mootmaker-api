package com.mootmaker.handler;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.MasterKeyProvider;
import com.amazonaws.encryptionsdk.jce.JceMasterKey;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real {@link AwsCrypto}/{@link MasterKeyProvider} decrypt call path this handler
 * uses in production, but against a local {@link JceMasterKey} instead of a real
 * {@code KmsMasterKeyProvider} - there's no AWS KMS key to test against until the account's SCP
 * allows the {@code kms} service (see testing-strategy.md). {@code AwsCrypto.decryptData} takes
 * any {@code MasterKeyProvider}, and the handler under test never constructs its own provider (one
 * is injected via the package-private constructor, same pattern as every other handler's test), so
 * this genuinely verifies the handler's decrypt/parse/store logic - only the specific choice of
 * {@code KmsMasterKeyProvider.buildStrict(keyArn)} in {@link
 * CustomEmailSenderBypassHandler#buildMasterKeyProvider} remains unverified until a real key
 * exists.
 */
class CustomEmailSenderBypassHandlerTest {

    private static final AwsCrypto CRYPTO = AwsCrypto.builder().build();

    private static MasterKeyProvider<?> localMasterKeyProvider() throws Exception {
        final KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        final SecretKey secretKey = keyGenerator.generateKey();
        return JceMasterKey.getInstance(secretKey, "mootmaker-test", "test-key-1", "AES/GCM/NoPadding");
    }

    private static byte[] encryptCode(final MasterKeyProvider<?> masterKeyProvider, final String code) {
        final CryptoResult<byte[], ?> result = CRYPTO.encryptData(masterKeyProvider, code.getBytes(StandardCharsets.UTF_8));
        return result.getResult();
    }

    private static Map<String, Object> customEmailSenderEvent(final String triggerSource, final String email, final byte[] ciphertext) {
        final Map<String, Object> userAttributes = new HashMap<>();
        userAttributes.put("email", email);
        userAttributes.put("sub", "sub-1");
        final Map<String, Object> request = new HashMap<>();
        request.put("type", "customEmailSenderRequestV1");
        request.put("userAttributes", userAttributes);
        request.put("code", Base64.getEncoder().encodeToString(ciphertext));
        final Map<String, Object> event = new HashMap<>();
        event.put("triggerSource", triggerSource);
        event.put("userPoolId", "pool-1");
        event.put("request", request);
        event.put("response", new HashMap<>());
        return event;
    }

    @Test
    void decryptsAndStoresTheCodeForSignUp() throws Exception {
        final MasterKeyProvider<?> masterKeyProvider = localMasterKeyProvider();
        final byte[] ciphertext = encryptCode(masterKeyProvider, "123456");
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CustomEmailSenderBypassHandler handler =
                new CustomEmailSenderBypassHandler(fakeClient, CRYPTO, masterKeyProvider, "TestEmailCodes");

        final Map<String, Object> event = customEmailSenderEvent("CustomEmailSender_SignUp", "ada@example.com", ciphertext);
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result, "Cognito requires the trigger to return the event");
        final List<Map<String, AttributeValue>> stored = fakeClient.tables.get("TestEmailCodes");
        assertEquals(1, stored.size());
        assertEquals("ada@example.com", stored.getFirst().get("email").s());
        assertEquals("123456", stored.getFirst().get("code").s());
        assertEquals("CustomEmailSender_SignUp", stored.getFirst().get("triggerSource").s());
        assertTrue(Long.parseLong(stored.getFirst().get("expiresAt").n()) > Instant.now().getEpochSecond(),
                "expiresAt must be a future TTL, not already-expired");
    }

    @Test
    void decryptsAndStoresTheCodeForForgotPassword() throws Exception {
        final MasterKeyProvider<?> masterKeyProvider = localMasterKeyProvider();
        final byte[] ciphertext = encryptCode(masterKeyProvider, "654321");
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CustomEmailSenderBypassHandler handler =
                new CustomEmailSenderBypassHandler(fakeClient, CRYPTO, masterKeyProvider, "TestEmailCodes");

        handler.handleRequest(customEmailSenderEvent("CustomEmailSender_ForgotPassword", "ada@example.com", ciphertext), null);

        assertEquals("654321", fakeClient.tables.get("TestEmailCodes").getFirst().get("code").s());
    }

    @Test
    void ignoresTriggerSourcesOtherThanSignUpOrForgotPassword() throws Exception {
        final MasterKeyProvider<?> masterKeyProvider = localMasterKeyProvider();
        final byte[] ciphertext = encryptCode(masterKeyProvider, "123456");
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        final CustomEmailSenderBypassHandler handler =
                new CustomEmailSenderBypassHandler(fakeClient, CRYPTO, masterKeyProvider, "TestEmailCodes");

        handler.handleRequest(customEmailSenderEvent("CustomEmailSender_AdminCreateUser", "ada@example.com", ciphertext), null);

        assertTrue(fakeClient.tables.getOrDefault("TestEmailCodes", List.of()).isEmpty());
    }

    @Test
    void doesNotAttemptDecryptionWhenDisabled() {
        final FakeDynamoDbClient fakeClient = new FakeDynamoDbClient();
        // No master key provider at all - matches production when TEST_EMAIL_BYPASS_KMS_KEY_ARN
        // is unset, exactly as it should be for every non-ephemeral environment.
        final CustomEmailSenderBypassHandler handler = new CustomEmailSenderBypassHandler(fakeClient, CRYPTO, null, "");

        final Map<String, Object> event = customEmailSenderEvent("CustomEmailSender_SignUp", "ada@example.com", new byte[0]);
        final Map<String, Object> result = handler.handleRequest(event, null);

        assertSame(event, result);
        assertTrue(fakeClient.tables.isEmpty(), "must not attempt any DynamoDB write when disabled");
    }
}
