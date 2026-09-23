// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.api.Auth;
import com.bettercloud.vault.response.AuthResponse;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link VaultTokenRenewalManager} - the AppRole background token renewal task.
 * <p>
 * The package-private constructor accepts an injectable {@link ScheduledExecutorService}, so the
 * scheduled runnable is captured and invoked synchronously here rather than relying on a real
 * background thread/timer. {@link Vault}/{@link Auth}/{@link AuthResponse} (all plain, non-final
 * classes from the bettercloud driver) are mocked directly - no live Vault instance is involved.
 * </p>
 */
class VaultTokenRenewalManagerTest {

    private Vault vault;
    private Auth auth;
    private VaultConfig vaultConfig;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        vault = mock(Vault.class);
        auth = mock(Auth.class);
        when(vault.auth()).thenReturn(auth);
        vaultConfig = new VaultConfig();
        scheduler = mock(ScheduledExecutorService.class);
    }

    @SuppressWarnings("unchecked")
    private Runnable captureScheduledTask() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(captor.capture(), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // start() - TOKEN vs APPROLE branch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("start() is a no-op for the TOKEN auth method - nothing is scheduled")
    void start_tokenAuthMethod_doesNotSchedule() {
        VaultAuthConfig tokenAuth = VaultAuthConfig.forToken("s.token");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, vaultConfig, tokenAuth, scheduler);

        manager.start();

        verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("start() schedules renewal at the configured interval for the APPROLE auth method")
    void start_approleAuthMethod_schedulesAtConfiguredInterval() {
        VaultAuthConfig approleAuth = VaultAuthConfig.forAppRole("role", "secret", "approle", 120L);
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, vaultConfig, approleAuth, scheduler);

        manager.start();

        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(120L), eq(120L), eq(TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------
    // renewOrReLogin (via the captured scheduled task)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("scheduled task renews successfully without touching auth().loginByAppRole")
    void scheduledTask_renewSucceeds_doesNotReLogin() throws Exception {
        AuthResponse renewal = mock(AuthResponse.class);
        when(renewal.getAuthLeaseDuration()).thenReturn(3600L);
        when(auth.renewSelf()).thenReturn(renewal);

        VaultAuthConfig approleAuth = VaultAuthConfig.forAppRole("role", "secret");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, vaultConfig, approleAuth, scheduler);
        manager.start();

        captureScheduledTask().run();

        verify(auth, times(1)).renewSelf();
        verify(auth, never()).loginByAppRole(any(), any(), any());
    }

    @Test
    @DisplayName("scheduled task falls back to AppRole re-login when renewal fails, and updates the token")
    void scheduledTask_renewFails_reLoginsAndUpdatesToken() throws Exception {
        when(auth.renewSelf()).thenThrow(new RuntimeException("token expired"));
        AuthResponse login = mock(AuthResponse.class);
        when(login.getAuthClientToken()).thenReturn("s.new-token");
        when(login.getAuthLeaseDuration()).thenReturn(1800L);
        when(auth.loginByAppRole("approle", "role", "secret")).thenReturn(login);

        VaultAuthConfig approleAuth = VaultAuthConfig.forAppRole("role", "secret", "approle", 60L);
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, vaultConfig, approleAuth, scheduler);
        manager.start();

        captureScheduledTask().run();

        verify(auth).loginByAppRole("approle", "role", "secret");
        assertThat(vaultConfig.getToken()).isEqualTo("s.new-token");
    }

    @Test
    @DisplayName("scheduled task swallows the error when both renewal and re-login fail")
    void scheduledTask_renewAndReLoginBothFail_doesNotThrow() throws Exception {
        when(auth.renewSelf()).thenThrow(new RuntimeException("token expired"));
        when(auth.loginByAppRole(any(), any(), any())).thenThrow(new RuntimeException("invalid role/secret id"));

        VaultAuthConfig approleAuth = VaultAuthConfig.forAppRole("role", "secret");
        VaultTokenRenewalManager manager = new VaultTokenRenewalManager(vault, vaultConfig, approleAuth, scheduler);
        manager.start();

        Runnable task = captureScheduledTask();

        assertThat(vaultConfig.getToken()).isNull();
        task.run(); // must not throw despite both Vault calls failing
        assertThat(vaultConfig.getToken()).isNull();
    }

    // ------------------------------------------------------------------
    // shutdown()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("shutdown() stops the scheduler even if start() was never called (TOKEN auth)")
    void shutdown_safeEvenWithoutStart() {
        VaultTokenRenewalManager manager =
                new VaultTokenRenewalManager(vault, vaultConfig, VaultAuthConfig.forToken("s.token"), scheduler);

        manager.shutdown();

        verify(scheduler).shutdown();
    }

    @Test
    @DisplayName("public two-arg constructor builds a real daemon-thread scheduler without throwing")
    void publicConstructor_buildsRealScheduler() {
        VaultTokenRenewalManager manager =
                new VaultTokenRenewalManager(vault, vaultConfig, VaultAuthConfig.forToken("s.token"));

        // TOKEN auth -> start() is a no-op, shutdown() must still cleanly stop the real executor.
        manager.start();
        manager.shutdown();
    }
}
