package com.mootmaker.handler;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesResponse;

import module java.base;

/** Minimal in-memory test double covering only the single Admin API call UpdatePersonHandler makes. */
class FakeCognitoIdentityProviderClient implements CognitoIdentityProviderClient {

    final List<AdminUpdateUserAttributesRequest> updateRequests = new ArrayList<>();
    private RuntimeException failNextUpdateWith;

    @Override
    public String serviceName() {
        return "cognito-idp";
    }

    @Override
    public void close() {
    }

    void failNextUpdateWith(final RuntimeException exception) {
        this.failNextUpdateWith = exception;
    }

    @Override
    public AdminUpdateUserAttributesResponse adminUpdateUserAttributes(final AdminUpdateUserAttributesRequest request) {
        if (failNextUpdateWith != null) {
            throw failNextUpdateWith;
        }
        updateRequests.add(request);
        return AdminUpdateUserAttributesResponse.builder().build();
    }
}
