package com.mootmaker.cognito;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/** Lazily-built singleton, reused across warm Lambda invocations to avoid repeated client setup cost. */
public final class CognitoIdentityProviderClientProvider {

    private static volatile CognitoIdentityProviderClient client;

    private CognitoIdentityProviderClientProvider() {
    }

    public static CognitoIdentityProviderClient client() {
        if (client == null) {
            synchronized (CognitoIdentityProviderClientProvider.class) {
                if (client == null) {
                    client = CognitoIdentityProviderClient.builder().build();
                }
            }
        }
        return client;
    }
}
