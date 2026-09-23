// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.common.utils;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gates access to Redis behind a valid Keycloak session.
 * <p>
 * Ported from {@code dpn-federator-gateway}'s {@code KeycloakSessionGuard} (same package-local
 * class name, same behaviour) so the REST Federator Gateway enforces the same Redis access
 * control as the gRPC Federator Gateway. The REST Federator Server is, like federator-server,
 * a headless backend service authenticating to Keycloak via the OAuth2 client-credentials grant
 * (no human user, no browser "login redirect"). The practical equivalent of "redirect to login"
 * for a backend service is: automatically re-authenticate by requesting a fresh token from
 * Keycloak.
 * </p>
 * <p>
 * This guard maintains its own in-memory session state, deliberately NOT backed by Redis. If it
 * were backed by Redis, calling it before every Redis access would be circular for
 * {@code IdpTokenServiceClientSecretImpl}'s own token cache, which itself checks Redis first
 * specifically to avoid calling Keycloak on every request. For that reason this guard is
 * intended to be called from Redis call sites that have no existing relationship with Keycloak,
 * not from the IDP token cache's own Redis reads/writes.
 * </p>
 * <p>
 * KNOWN LIMITATION (inherited from the source implementation): {@code fetchToken()} on the
 * existing {@code IdpTokenService} interface returns only the token string, not its actual
 * expiry. This guard therefore uses a conservative fixed re-check interval
 * ({@link #SESSION_TTL_MILLIS}) rather than the token's real {@code expires_in} value.
 * </p>
 */
public final class KeycloakSessionGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger("KeycloakSessionGuard");

    /** Conservative re-check interval; see KNOWN LIMITATION above. */
    private static final long SESSION_TTL_MILLIS = 55_000L;

    private static volatile Supplier<String> tokenFetcher;
    private static volatile String currentToken;
    private static volatile long expiryEpochMillis;

    private KeycloakSessionGuard() {}

    /**
     * Wires this guard to a real token-fetching function. Call once at application startup,
     * after the redis-gating IdpTokenService instance has been constructed, e.g.:
     * <pre>
     *     KeycloakSessionGuard.configure(redisIdpTokenService::fetchToken);
     * </pre>
     *
     * @param fetcher a function that performs a real Keycloak token fetch and returns the token,
     *                throwing on failure.
     */
    public static void configure(Supplier<String> fetcher) {
        tokenFetcher = fetcher;
    }

    /**
     * Ensures a valid Keycloak session exists before Redis is accessed. Call this immediately
     * before any {@code RedisUtil} operation at call sites that should be gated (see class
     * Javadoc for which sites that applies to).
     * <p>
     * If no token is held, or the held token has passed its conservative re-check interval,
     * this method blocks and re-authenticates against Keycloak before returning. If
     * authentication fails, it throws rather than allowing the caller to proceed to Redis.
     * </p>
     *
     * @throws IllegalStateException if {@link #configure(Supplier)} was never called, or if
     *                                Keycloak authentication fails.
     */
    public static synchronized void ensureAuthenticated() {
        if (tokenFetcher == null) {
            throw new IllegalStateException(
                    "KeycloakSessionGuard.configure(...) was never called - cannot gate Redis access");
        }

        long now = System.currentTimeMillis();

        if (currentToken == null) {
            LOGGER.warn("No Keycloak token available - authenticating before Redis access");
            authenticate();
        } else if (now >= expiryEpochMillis) {
            LOGGER.warn("Keycloak token expired - re-authenticating before Redis access");
            authenticate();
        } else {
            LOGGER.debug("Existing Keycloak session still valid - proceeding to Redis");
        }
    }

    private static void authenticate() {
        try {
            currentToken = tokenFetcher.get();
            if (currentToken == null || currentToken.isBlank()) {
                throw new IllegalStateException("Keycloak returned no token");
            }
            expiryEpochMillis = System.currentTimeMillis() + SESSION_TTL_MILLIS;
            LOGGER.info("Keycloak authentication successful - Redis access permitted");
        } catch (Exception e) {
            currentToken = null;
            LOGGER.error("Keycloak authentication failed - Redis access blocked", e);
            throw new IllegalStateException(
                    "Unable to authenticate with Keycloak; Redis access blocked until a valid session is obtained",
                    e);
        }
    }

    /** Test/diagnostic helper - clears the held session, forcing re-authentication on next call. */
    static void resetForTesting() {
        currentToken = null;
        expiryEpochMillis = 0L;
    }
}
