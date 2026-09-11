package com.mootmaker.testsupport;

import module java.base;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * Minimal in-memory test double covering only the Admin API calls the handlers under test make.
 * {@code DatabaseReset.wipeCognitoPool} issues its {@code adminDeleteUser} calls concurrently (see
 * {@code ConcurrencyUtils.runInParallel}), so the methods below are synchronized - a real {@code
 * CognitoIdentityProviderClient} handles concurrent calls from multiple threads fine, and this fake
 * needs to behave the same way, since a plain {@code ArrayList} silently drops entries under
 * concurrent, unsynchronized {@code add()} calls.
 */
public class FakeCognitoIdentityProviderClient implements CognitoIdentityProviderClient {

  public final List<AdminUpdateUserAttributesRequest> updateRequests = new ArrayList<>();
  public final List<AdminDeleteUserRequest> deleteRequests = new ArrayList<>();

  /** Backing store for {@link #listUsers}. Tests populate this directly. */
  public final List<UserType> users = new ArrayList<>();

  /** Lets a test force multi-page pagination; defaults to returning every user in one page. */
  public int listUsersPageSize = Integer.MAX_VALUE;

  private RuntimeException failNextUpdateWith;
  private String failUpdateOfAttribute;
  private RuntimeException failUpdateOfException;

  @Override
  public String serviceName() {
    return "cognito-idp";
  }

  @Override
  public void close() {}

  /**
   * Fails only the update that carries a given attribute. The trigger now issues two updates -
   * custom:personId, then custom:class - so "fail the next one" can no longer name which failure is
   * being exercised, and the two have very different consequences.
   */
  public void failUpdateOf(final String attributeName, final RuntimeException exception) {
    this.failUpdateOfAttribute = attributeName;
    this.failUpdateOfException = exception;
  }

  public void failNextUpdateWith(final RuntimeException exception) {
    this.failNextUpdateWith = exception;
  }

  @Override
  public synchronized AdminUpdateUserAttributesResponse adminUpdateUserAttributes(
      final AdminUpdateUserAttributesRequest request) {
    if (failUpdateOfAttribute != null
        && request.userAttributes().stream()
            .anyMatch(a -> a.name().equals(failUpdateOfAttribute))) {
      throw failUpdateOfException;
    }
    if (failNextUpdateWith != null) {
      throw failNextUpdateWith;
    }
    updateRequests.add(request);
    return AdminUpdateUserAttributesResponse.builder().build();
  }

  @Override
  public synchronized AdminDeleteUserResponse adminDeleteUser(
      final AdminDeleteUserRequest request) {
    deleteRequests.add(request);
    return AdminDeleteUserResponse.builder().build();
  }

  /**
   * Paginates {@code users} at {@link #listUsersPageSize} per page, using the offset into the list
   * as the pagination token - real enough to exercise a caller's pagination loop without modelling
   * Cognito's actual token format.
   */
  @Override
  public ListUsersResponse listUsers(final ListUsersRequest request) {
    final int offset =
        request.paginationToken() == null ? 0 : Integer.parseInt(request.paginationToken());
    final int end = Math.min(offset + listUsersPageSize, users.size());
    final List<UserType> page = users.subList(offset, end);
    final String nextToken = end < users.size() ? String.valueOf(end) : null;
    return ListUsersResponse.builder().users(page).paginationToken(nextToken).build();
  }
}
