package com.mootmaker.handler;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesResponse;

import module java.base;

/** Minimal in-memory test double covering only the Admin API calls the handlers under test make. */
class FakeCognitoIdentityProviderClient implements CognitoIdentityProviderClient {

    final List<AdminUpdateUserAttributesRequest> updateRequests = new ArrayList<>();
    final List<AdminDeleteUserRequest> deleteRequests = new ArrayList<>();
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

    @Override
    public AdminDeleteUserResponse adminDeleteUser(final AdminDeleteUserRequest request) {
        deleteRequests.add(request);
        return AdminDeleteUserResponse.builder().build();
    }
}
