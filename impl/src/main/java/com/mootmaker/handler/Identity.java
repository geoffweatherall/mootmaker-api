package com.mootmaker.handler;

import module java.base;

/**
 * Defence-in-depth authentication/authorization checks. AppSync's Cognito user-pool authoriser
 * validates the caller's JWT before invoking any resolver, and puts the token's claims in the
 * {@code identity.claims} field of the context it forwards to the Lambda. Every handler re-checks
 * that the identity is present before running any logic, so a mis-configured resolver (e.g. an
 * accidentally public API) still refuses to do work.
 */
final class Identity {

    /**
     * OAuth scope (e.g. {@code test-mootmaker-api/admin}) that grants admin-equivalent access to
     * the M2M tooling clients (the acceptance tests, mootmaker-demo-data) - that client
     * authenticates via client_credentials with no Cognito user behind it at all, so it can never
     * carry a {@code custom:class} claim the way a real signed-in user's ID token does. Set by
     * Terraform (see cognito.tf's admin resource-server scope and lambda.tf).
     */
    private static final String ADMIN_SCOPE_ENV_VAR = "COGNITO_ADMIN_SCOPE";

    private Identity() {
    }

    /** Throws if the AppSync context has no authenticated identity; call before any handler logic. */
    static void requireAuthenticated(final Map<String, Object> event) {
        if (event == null || event.get("identity") == null) {
            throw new IllegalStateException("Unauthorized: request has no authenticated identity");
        }
    }

    /**
     * Throws unless the caller is either a real signed-in user whose token carries
     * {@code custom:class=admin}, or the M2M tooling client presenting the admin scope (see
     * {@link #ADMIN_SCOPE_ENV_VAR}). Call before any handler logic that only admins may perform.
     */
    static void requireAdmin(final Map<String, Object> event) {
        requireAdmin(event, System.getenv(ADMIN_SCOPE_ENV_VAR));
    }

    /** Package-private overload so {@code IdentityTest} can supply the admin scope directly rather than a real environment variable. */
    static void requireAdmin(final Map<String, Object> event, final String adminScope) {
        requireAuthenticated(event);
        if (!isAdmin(event, adminScope)) {
            throw new IllegalStateException("Forbidden: admin access required");
        }
    }

    /**
     * Non-throwing check for the same condition {@link #requireAdmin} enforces - for handlers like
     * {@code UpdatePersonHandler} that allow admin access as just one of several ways a request can
     * be authorized (the other being "this is your own record"), rather than admin being the only
     * acceptable outcome.
     */
    static boolean isAdmin(final Map<String, Object> event) {
        return isAdmin(event, System.getenv(ADMIN_SCOPE_ENV_VAR));
    }

    /** Package-private overload so {@code IdentityTest} can supply the admin scope directly rather than a real environment variable. */
    static boolean isAdmin(final Map<String, Object> event, final String adminScope) {
        final Map<String, Object> identity = castToMap(event == null ? null : event.get("identity"));
        if (identity == null) {
            return false;
        }
        final Map<String, Object> claims = castToMap(identity.get("claims"));
        if (claims == null) {
            return false;
        }
        return "admin".equals(claims.get("custom:class")) || hasAdminScope(claims, adminScope);
    }

    private static boolean hasAdminScope(final Map<String, Object> claims, final String adminScope) {
        final Object scopeClaim = claims.get("scope");
        if (adminScope == null || adminScope.isBlank() || !(scopeClaim instanceof String scope)) {
            return false;
        }
        return Arrays.asList(scope.split(" ")).contains(adminScope);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(final Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }
}
