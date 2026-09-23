// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fully isolated unit tests for {@link KeycloakSessionGuard} - the actual gate mechanism behind
 * {@code RedisUtil.getKeycloakGatedInstance()}. Uses {@link KeycloakSessionGuard#configure} with
 * a fake token fetcher throughout, so none of these tests touch a real Keycloak server, real
 * Redis, or the filesystem.
 * <p>
 * These correspond to the "3 scenarios" manually verified against the real infrastructure earlier
 * in this project's testing (valid credentials / invalid credentials / never-configured), now
 * captured as permanent, fast, repeatable automated tests instead.
 * </p>
 */
class KeycloakSessionGuardTest {

    @AfterEach
    void resetGuardState() {
        // Clears the held session AND the configured fetcher between tests, so each test starts
        // from a genuinely clean slate regardless of execution order.
        KeycloakSessionGuard.resetForTesting();
        KeycloakSessionGuard.configure(null);
    }

    @Test
    @DisplayName("Scenario 1 - valid credentials: ensureAuthenticated() succeeds")
    void validCredentials_allowsAccess() {
        KeycloakSessionGuard.configure(() -> "fake-valid-token");

        // Must not throw.
        KeycloakSessionGuard.ensureAuthenticated();
    }

    @Test
    @DisplayName("Scenario 2 - invalid credentials: ensureAuthenticated() blocks with IllegalStateException")
    void invalidCredentials_blocksAccess() {
        KeycloakSessionGuard.configure(() -> {
            throw new RuntimeException("simulated Keycloak rejection - invalid client credentials");
        });

        assertThatThrownBy(KeycloakSessionGuard::ensureAuthenticated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to authenticate with Keycloak")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("A blank token from the fetcher is treated as a failed authentication")
    void blankToken_blocksAccess() {
        KeycloakSessionGuard.configure(() -> "  ");

        assertThatThrownBy(KeycloakSessionGuard::ensureAuthenticated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to authenticate with Keycloak");
    }

    @Test
    @DisplayName("configure() never called: ensureAuthenticated() fails clearly rather than NPE-ing")
    void neverConfigured_failsWithClearMessage() {
        // resetGuardState() already set the fetcher to null for this test.
        assertThatThrownBy(KeycloakSessionGuard::ensureAuthenticated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configure(...) was never called");
    }

    @Test
    @DisplayName("A previously-failed authentication does not leave a stale token behind")
    void failedAuthentication_doesNotLeaveStaleToken() {
        KeycloakSessionGuard.configure(() -> {
            throw new RuntimeException("first attempt fails");
        });
        assertThatThrownBy(KeycloakSessionGuard::ensureAuthenticated).isInstanceOf(IllegalStateException.class);

        // Now fix the fetcher and confirm a fresh, genuine re-authentication happens - not a
        // reuse of some leftover state from the failed attempt.
        KeycloakSessionGuard.configure(() -> "fake-valid-token-after-retry");
        KeycloakSessionGuard.ensureAuthenticated();
    }

    @Test
    @DisplayName("Session caching: a second call within the TTL window does not re-fetch the token")
    void validSession_isReusedWithoutRefetching() {
        AtomicInteger fetchCount = new AtomicInteger(0);
        KeycloakSessionGuard.configure(() -> {
            fetchCount.incrementAndGet();
            return "fake-valid-token";
        });

        KeycloakSessionGuard.ensureAuthenticated();
        KeycloakSessionGuard.ensureAuthenticated();
        KeycloakSessionGuard.ensureAuthenticated();

        assertThat(fetchCount.get())
                .as("the fetcher should only be invoked once - the second and third calls should "
                        + "reuse the still-valid session, exactly like RedisUtil's own token cache "
                        + "avoids refetching on every call")
                .isEqualTo(1);
    }
}
