// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.api.Logical;
import com.bettercloud.vault.response.LogicalResponse;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VaultSecretProvider}. Construction of the underlying {@link VaultClient}
 * never touches the network for {@link VaultAuthMethod#TOKEN} auth (see {@link VaultClientTest}),
 * so this test constructs real {@link VaultSecretProvider} instances and reflects into the
 * private {@code client}/{@code vault} fields to substitute a mocked {@link Vault} for the actual
 * HTTP call made by {@link VaultSecretProvider#getSecret(String, String)}.
 */
class VaultSecretProviderTest {

    private static void setInnerVault(VaultSecretProvider provider, Vault mockVault) throws Exception {
        Field clientField = VaultSecretProvider.class.getDeclaredField("client");
        clientField.setAccessible(true);
        VaultClient client = (VaultClient) clientField.get(provider);

        Field vaultField = VaultClient.class.getDeclaredField("vault");
        vaultField.setAccessible(true);
        vaultField.set(client, mockVault);
    }

    @Test
    @DisplayName("isEnabled() is true once a VaultClient has been constructed")
    void isEnabled_true() {
        VaultSecretProvider provider =
                new VaultSecretProvider("https://vault.example.test:8200", "s.token", null, null);

        assertThat(provider.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("getSecret() delegates to the underlying VaultClient and returns its value")
    void getSecret_delegatesToVaultClient() throws Exception {
        VaultSecretProvider provider =
                new VaultSecretProvider("https://vault.example.test:8200", "s.token", null, null);

        Vault mockVault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);
        when(mockVault.logical()).thenReturn(logical);
        when(logical.read("pki-client/node-net/client/certificate")).thenReturn(response);
        when(response.getData()).thenReturn(Map.of("certificate", "leaf-pem"));
        setInnerVault(provider, mockVault);

        String secret = provider.getSecret("node-net/client/certificate", "certificate");

        assertThat(secret).isEqualTo("leaf-pem");
        verify(logical).read("pki-client/node-net/client/certificate");
    }

    @Test
    @DisplayName("2-arg constructor with an explicit VaultAuthConfig (APPROLE) also constructs successfully")
    void constructor_withExplicitAuthConfig_appRoleLoginPerformedEagerly() {
        // AppRole auth performs a real login attempt during construction (there is no seam to
        // avoid it without touching production code), so an unreachable Vault address must
        // surface as the documented RuntimeException wrapping the underlying failure - this is
        // itself meaningful behaviour to pin down: TOKEN auth defers all I/O, APPROLE does not.
        VaultAuthConfig approle = VaultAuthConfig.forAppRole("role", "secret");

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                assertThatThrownBy(() ->
                                new VaultSecretProvider("http://127.0.0.1:1", approle, null, null))
                        .isInstanceOf(RuntimeException.class));
    }

    @Test
    @DisplayName("getSecret() surfaces a RuntimeException when the underlying Vault read fails")
    void getSecret_vaultReadFails_propagatesRuntimeException() throws Exception {
        VaultSecretProvider provider =
                new VaultSecretProvider("https://vault.example.test:8200", "s.token", null, null);

        Vault mockVault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        when(mockVault.logical()).thenReturn(logical);
        when(logical.read(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.bettercloud.vault.VaultException("unreachable"));
        setInnerVault(provider, mockVault);

        assertThatThrownBy(() -> provider.getSecret("node-net/client/certificate", "certificate"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Vault read failed");
    }
}
