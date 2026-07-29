package com.mootmaker.handler;

import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityTest {

    private static final String ADMIN_SCOPE = "test-mootmaker-api/admin";

    private static Map<String, Object> eventWithClaims(final Map<String, Object> claims) {
        final Map<String, Object> identity = new HashMap<>();
        identity.put("sub", "test-user");
        if (claims != null) {
            identity.put("claims", claims);
        }
        return Map.of("identity", identity);
    }

    @Test
    void requireAuthenticatedRejectsAMissingIdentity() {
        assertThrows(IllegalStateException.class, () -> Identity.requireAuthenticated(Map.of()));
        assertThrows(IllegalStateException.class, () -> Identity.requireAuthenticated(null));
    }

    @Test
    void requireAuthenticatedAllowsAnyAuthenticatedIdentity() {
        assertDoesNotThrow(() -> Identity.requireAuthenticated(eventWithClaims(null)));
    }

    @Test
    void requireAdminRejectsAnUnauthenticatedRequest() {
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(Map.of(), ADMIN_SCOPE));
    }

    @Test
    void requireAdminRejectsAStandardUser() {
        final Map<String, Object> event = eventWithClaims(Map.of("custom:class", "standard"));
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void requireAdminRejectsAUserWithNoClassClaimAtAll() {
        final Map<String, Object> event = eventWithClaims(Map.of("sub", "some-claim"));
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void requireAdminRejectsWhenThereAreNoClaimsAtAll() {
        final Map<String, Object> event = eventWithClaims(null);
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void requireAdminAllowsAUserWithTheAdminClassClaim() {
        final Map<String, Object> event = eventWithClaims(Map.of("custom:class", "admin"));
        assertDoesNotThrow(() -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void requireAdminAllowsTheM2mClientPresentingTheAdminScope() {
        final Map<String, Object> event = eventWithClaims(Map.of("scope", "test-mootmaker-api/execute test-mootmaker-api/admin"));
        assertDoesNotThrow(() -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void requireAdminRejectsAnM2mClientWithoutTheAdminScope() {
        final Map<String, Object> event = eventWithClaims(Map.of("scope", "test-mootmaker-api/execute"));
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void requireAdminRejectsEveryoneWhenTheAdminScopeIsNotConfigured() {
        final Map<String, Object> event = eventWithClaims(Map.of("scope", "test-mootmaker-api/execute test-mootmaker-api/admin"));
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(event, null));
    }

    @Test
    void requireAdminDoesNotMatchAScopeThatMerelyContainsTheAdminScopeAsASubstring() {
        // e.g. "test-mootmaker-api/admin-readonly" must not satisfy "test-mootmaker-api/admin" -
        // scope matching is by whole space-separated token, not substring.
        final Map<String, Object> event = eventWithClaims(Map.of("scope", "test-mootmaker-api/admin-readonly"));
        assertThrows(IllegalStateException.class, () -> Identity.requireAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void isAdminReturnsTrueForAnAdminWithoutThrowing() {
        final Map<String, Object> event = eventWithClaims(Map.of("custom:class", "admin"));
        assertTrue(Identity.isAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void isAdminReturnsFalseForAStandardUserWithoutThrowing() {
        final Map<String, Object> event = eventWithClaims(Map.of("custom:class", "standard"));
        assertFalse(Identity.isAdmin(event, ADMIN_SCOPE));
    }

    @Test
    void isAdminReturnsFalseForAnUnauthenticatedEventWithoutThrowing() {
        assertFalse(Identity.isAdmin(Map.of(), ADMIN_SCOPE));
    }
}
