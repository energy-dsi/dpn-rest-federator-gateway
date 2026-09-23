// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link HttpClientFactoryUtils}, using real {@code keytool}-generated PKCS12/JKS
 * key- and truststores (house style, see {@code SSLUtilsTest}) and {@link VaultTlsSupport} mocked
 * via {@link org.mockito.Mockito#mockStatic} to select between the Vault-sourced and file-based
 * mTLS branches without a real Vault connection.
 */
class HttpClientFactoryUtilsTest {

    private static final String PASSWORD = "changeit";

    private Path generateKeystore(Path dir, String fileName, String storeType) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "test-entry",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=http-client-factory-test",
                        "-storetype",
                        storeType,
                        "-keystore",
                        file.toString(),
                        "-storepass",
                        PASSWORD,
                        "-keypass",
                        PASSWORD)
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "keytool failed (exit " + exitCode + "): "
                            + new String(process.getInputStream().readAllBytes()));
        }
        return file;
    }

    @Test
    @DisplayName("createHttpClientWithMtls uses VaultTlsSupport.sslContext() when Vault TLS is enabled")
    void createHttpClientWithMtls_vaultTlsEnabled_usesVaultSslContext() throws Exception {
        SSLContext fakeVaultContext = SSLContext.getInstance("TLS");
        fakeVaultContext.init(null, null, null);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(true);
            vaultTlsMock.when(VaultTlsSupport::sslContext).thenReturn(fakeVaultContext);

            HttpClient client = HttpClientFactoryUtils.createHttpClientWithMtls(new Properties());

            assertThat(client).isNotNull();
            assertThat(client.sslContext()).isSameAs(fakeVaultContext);
            assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        }
    }

    @Test
    @DisplayName("createHttpClientWithMtls builds an SSLContext from keystore/truststore file properties when Vault TLS is disabled")
    void createHttpClientWithMtls_vaultTlsDisabled_buildsFromFileProperties(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir, "client.p12", "PKCS12");
        Path truststore = generateKeystore(tempDir, "truststore.jks", "JKS");

        Properties properties = new Properties();
        properties.setProperty("idp.keystore.path", keystore.toString());
        properties.setProperty("idp.keystore.password", PASSWORD);
        properties.setProperty("idp.truststore.path", truststore.toString());
        properties.setProperty("idp.truststore.password", PASSWORD);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            HttpClient client = HttpClientFactoryUtils.createHttpClientWithMtls(properties);

            assertThat(client).isNotNull();
            assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        }
    }

    @Test
    @DisplayName("createHttpClientWithMtls wraps any failure in FederatorSslException")
    void createHttpClientWithMtls_failure_wrapsInFederatorSslException() {
        Properties properties = new Properties();
        properties.setProperty("idp.keystore.path", "/does/not/exist.p12");
        properties.setProperty("idp.keystore.password", PASSWORD);
        properties.setProperty("idp.truststore.path", "/does/not/exist-either.jks");
        properties.setProperty("idp.truststore.password", PASSWORD);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            assertThatThrownBy(() -> HttpClientFactoryUtils.createHttpClientWithMtls(properties))
                    .isInstanceOf(FederatorSslException.class)
                    .hasMessageContaining("Failed to create HttpClient with SSL context");
        }
    }

    @Test
    @DisplayName("createHttpClient(properties) reads the default 'idp.' truststore prefix")
    void createHttpClient_defaultPrefix_usesIdpTruststore(@TempDir Path tempDir) throws Exception {
        Path truststore = generateKeystore(tempDir, "truststore.jks", "JKS");
        Properties properties = new Properties();
        properties.setProperty("idp.truststore.path", truststore.toString());
        properties.setProperty("idp.truststore.password", PASSWORD);

        HttpClient client = HttpClientFactoryUtils.createHttpClient(properties);

        assertThat(client).isNotNull();
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    @DisplayName("createHttpClient(properties, prefix) uses the prefix-specific truststore when configured")
    void createHttpClient_customPrefix_usesPrefixSpecificTruststore(@TempDir Path tempDir) throws Exception {
        Path redisTruststore = generateKeystore(tempDir, "redis-truststore.jks", "JKS");
        Path idpTruststore = generateKeystore(tempDir, "idp-truststore.jks", "JKS");

        Properties properties = new Properties();
        properties.setProperty("redis.idp.truststore.path", redisTruststore.toString());
        properties.setProperty("redis.idp.truststore.password", PASSWORD);
        properties.setProperty("idp.truststore.path", idpTruststore.toString());
        properties.setProperty("idp.truststore.password", PASSWORD);

        HttpClient client = HttpClientFactoryUtils.createHttpClient(properties, "redis.idp.");

        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("createHttpClient(properties, prefix) falls back to the 'idp.' truststore when no prefix-specific one is configured")
    void createHttpClient_customPrefix_fallsBackToIdpTruststoreWhenUnconfigured(@TempDir Path tempDir) throws Exception {
        Path idpTruststore = generateKeystore(tempDir, "idp-truststore.jks", "JKS");

        Properties properties = new Properties();
        // No redis.idp.truststore.path configured at all.
        properties.setProperty("idp.truststore.path", idpTruststore.toString());
        properties.setProperty("idp.truststore.password", PASSWORD);

        HttpClient client = HttpClientFactoryUtils.createHttpClient(properties, "redis.idp.");

        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("createHttpClient wraps a truststore load failure in FederatorSslException")
    void createHttpClient_missingTruststore_wrapsInFederatorSslException() {
        Properties properties = new Properties();
        properties.setProperty("idp.truststore.path", "/does/not/exist.jks");
        properties.setProperty("idp.truststore.password", PASSWORD);

        assertThatThrownBy(() -> HttpClientFactoryUtils.createHttpClient(properties))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to create HttpClient");
    }

    @Test
    @DisplayName("HttpClientFactoryUtils cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = HttpClientFactoryUtils.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
