// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.api.Auth;
import com.bettercloud.vault.api.Logical;
import com.bettercloud.vault.response.AuthResponse;
import com.bettercloud.vault.response.LogicalResponse;
import com.bettercloud.vault.rest.RestResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link VaultClient}.
 * <p>
 * {@code VaultClient} constructs its own {@link Vault} instance internally, with no injectable
 * seam in its public API. Construction itself never touches the network for
 * {@link VaultAuthMethod#TOKEN} (the AppRole login call is skipped entirely), so this test
 * constructs real instances and then replaces the private {@code vault} field with a Mockito mock
 * via reflection to make {@link VaultClient#getSecret(String, String)} deterministic - matching
 * the approach the task instructions call out for classes with no constructor-level seam. The
 * private static helper methods ({@code loadTrustStore}, {@code resolveInitialToken}) are also
 * exercised directly via reflection, since they contain the bulk of the interesting branching
 * logic and do not require a fully constructed client.
 * </p>
 */
class VaultClientTest {

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = VaultClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ------------------------------------------------------------------
    // getSecret() - via reflection-injected mock Vault
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getSecret prefixes the path with 'pki-client/' and returns the requested key")
    void getSecret_success_returnsValueAndPrefixesPath() throws Exception {
        VaultClient client = new VaultClient("https://vault.example.test:8200", "s.token", null, null);

        Vault mockVault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);
        Map<String, String> data = new HashMap<>();
        data.put("certificate", "cert-pem-value");
        when(mockVault.logical()).thenReturn(logical);
        when(logical.read("pki-client/node-net/client/certificate")).thenReturn(response);
        when(response.getData()).thenReturn(data);
        when(response.getRestResponse()).thenReturn(null);
        setField(client, "vault", mockVault);

        String secret = client.getSecret("node-net/client/certificate", "certificate");

        assertThat(secret).isEqualTo("cert-pem-value");
        verify(logical).read("pki-client/node-net/client/certificate");
    }

    @Test
    @DisplayName("getSecret strips a single leading slash before prefixing with the PKI mount")
    void getSecret_leadingSlash_normalized() throws Exception {
        VaultClient client = new VaultClient("https://vault.example.test:8200", "s.token", null, null);

        Vault mockVault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);
        Map<String, String> data = Map.of("key", "value");
        when(mockVault.logical()).thenReturn(logical);
        when(logical.read("pki-client/node-net/client/certificate")).thenReturn(response);
        when(response.getData()).thenReturn(data);
        setField(client, "vault", mockVault);

        String secret = client.getSecret("/node-net/client/certificate", "key");

        assertThat(secret).isEqualTo("value");
    }

    @Test
    @DisplayName("getSecret wraps a Vault read failure as RuntimeException")
    void getSecret_vaultReadThrows_wrapsRuntimeException() throws Exception {
        VaultClient client = new VaultClient("https://vault.example.test:8200", "s.token", null, null);

        Vault mockVault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        when(mockVault.logical()).thenReturn(logical);
        when(logical.read(any())).thenThrow(new com.bettercloud.vault.VaultException("connection refused"));
        setField(client, "vault", mockVault);

        assertThatThrownBy(() -> client.getSecret("node-net/client/certificate", "certificate"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Vault read failed")
                .hasCauseInstanceOf(com.bettercloud.vault.VaultException.class);
    }

    @Test
    @DisplayName("getSecret wraps a null data map (missing secret) as RuntimeException")
    void getSecret_nullDataMap_wrapsRuntimeException() throws Exception {
        VaultClient client = new VaultClient("https://vault.example.test:8200", "s.token", null, null);

        Vault mockVault = mock(Vault.class);
        Logical logical = mock(Logical.class);
        LogicalResponse response = mock(LogicalResponse.class);
        when(mockVault.logical()).thenReturn(logical);
        when(logical.read(any())).thenReturn(response);
        when(response.getData()).thenReturn(null);
        setField(client, "vault", mockVault);

        assertThatThrownBy(() -> client.getSecret("node-net/client/certificate", "certificate"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Vault read failed")
                .hasCauseInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------
    // getAuthConfig() / shutdown()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getAuthConfig returns the config the client was constructed with")
    void getAuthConfig_returnsConstructedConfig() throws Exception {
        VaultAuthConfig authConfig = VaultAuthConfig.forToken("s.token");
        VaultClient client = new VaultClient("https://vault.example.test:8200", authConfig, null, null);

        assertThat(client.getAuthConfig()).isSameAs(authConfig);
    }

    @Test
    @DisplayName("shutdown() delegates to the renewal manager without throwing (TOKEN auth, no-op renewal)")
    void shutdown_delegatesToRenewalManager() throws Exception {
        VaultClient client = new VaultClient("https://vault.example.test:8200", "s.token", null, null);

        client.shutdown();
    }

    // ------------------------------------------------------------------
    // loadTrustStore (private static) - exercised via reflection with a real PKCS12 file
    // ------------------------------------------------------------------

    private static KeyStore invokeLoadTrustStore(String path, String password) throws Exception {
        Method method = VaultClient.class.getDeclaredMethod("loadTrustStore", String.class, String.class);
        method.setAccessible(true);
        return (KeyStore) method.invoke(null, path, password);
    }

    private Path generatePkcs12(Path dir) throws Exception {
        Path file = dir.resolve("truststore.p12");
        Process process = new ProcessBuilder(
                        "keytool", "-genkeypair", "-alias", "entry", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "365", "-dname", "CN=vault-client-test", "-storetype", "PKCS12",
                        "-keystore", file.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "keytool failed (exit " + exitCode + "): " + new String(process.getInputStream().readAllBytes()));
        }
        return file;
    }

    @Test
    @DisplayName("loadTrustStore loads a genuine PKCS12 file")
    void loadTrustStore_genuinePkcs12_loads(@TempDir Path dir) throws Exception {
        Path p12 = generatePkcs12(dir);

        KeyStore trustStore = invokeLoadTrustStore(p12.toString(), "changeit");

        assertThat(trustStore).isNotNull();
        assertThat(trustStore.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("loadTrustStore returns null and does not throw when the path/password are invalid")
    void loadTrustStore_invalidPath_returnsNull() throws Exception {
        KeyStore trustStore = invokeLoadTrustStore("/no/such/file.p12", "irrelevant");

        assertThat(trustStore).isNull();
    }

    @Test
    @DisplayName("loadTrustStore returns null when the password is wrong for an otherwise valid file")
    void loadTrustStore_wrongPassword_returnsNull(@TempDir Path dir) throws Exception {
        Path p12 = generatePkcs12(dir);

        KeyStore trustStore = invokeLoadTrustStore(p12.toString(), "totally-wrong");

        assertThat(trustStore).isNull();
    }

    @Test
    @DisplayName("loadTrustStore returns null (never throws) for a null path")
    void loadTrustStore_nullPath_returnsNull() throws Exception {
        assertThat(invokeLoadTrustStore(null, "anything")).isNull();
    }

    // ------------------------------------------------------------------
    // resolveInitialToken (private static) - exercised via reflection with mocked Vault
    // ------------------------------------------------------------------

    private static String invokeResolveInitialToken(Vault vault, VaultAuthConfig config) throws Exception {
        Method method = VaultClient.class.getDeclaredMethod("resolveInitialToken", Vault.class, VaultAuthConfig.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, vault, config);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("resolveInitialToken returns the configured token directly for TOKEN auth")
    void resolveInitialToken_tokenAuth_returnsConfiguredToken() throws Exception {
        Vault vault = mock(Vault.class);
        VaultAuthConfig config = VaultAuthConfig.forToken("s.my-token");

        String token = invokeResolveInitialToken(vault, config);

        assertThat(token).isEqualTo("s.my-token");
    }

    @Test
    @DisplayName("resolveInitialToken performs an AppRole login and returns the issued client token")
    void resolveInitialToken_approleAuth_performsLoginAndReturnsClientToken() throws Exception {
        Vault vault = mock(Vault.class);
        Auth auth = mock(Auth.class);
        AuthResponse authResponse = mock(AuthResponse.class);
        when(vault.auth()).thenReturn(auth);
        when(auth.loginByAppRole("approle", "role-1", "secret-1")).thenReturn(authResponse);
        when(authResponse.getAuthClientToken()).thenReturn("s.approle-issued-token");
        when(authResponse.getAuthLeaseDuration()).thenReturn(600L);
        when(authResponse.getRenewable()).thenReturn(true);

        VaultAuthConfig config = VaultAuthConfig.forAppRole("role-1", "secret-1");

        String token = invokeResolveInitialToken(vault, config);

        assertThat(token).isEqualTo("s.approle-issued-token");
        verify(auth).loginByAppRole("approle", "role-1", "secret-1");
    }

    @Test
    @DisplayName("resolveInitialToken propagates an AppRole login failure")
    void resolveInitialToken_approleLoginFails_propagates() throws Exception {
        Vault vault = mock(Vault.class);
        Auth auth = mock(Auth.class);
        when(vault.auth()).thenReturn(auth);
        when(auth.loginByAppRole(eq("approle"), eq("role-1"), eq("secret-1")))
                .thenThrow(new com.bettercloud.vault.VaultException("permission denied"));

        VaultAuthConfig config = VaultAuthConfig.forAppRole("role-1", "secret-1");

        assertThatThrownBy(() -> invokeResolveInitialToken(vault, config))
                .isInstanceOf(com.bettercloud.vault.VaultException.class)
                .hasMessageContaining("permission denied");
    }

    // ------------------------------------------------------------------
    // Constructor auth-method dispatch (backward-compatible token constructor)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the 4-arg (String token) constructor is equivalent to forToken(...)")
    void tokenConstructor_isEquivalentToForToken() throws Exception {
        VaultClient client = new VaultClient("https://vault.example.test:8200", "s.equivalent-token", null, null);

        assertThat(client.getAuthConfig().getMethod()).isEqualTo(VaultAuthMethod.TOKEN);
        assertThat(client.getAuthConfig().getToken()).isEqualTo("s.equivalent-token");
    }
}
