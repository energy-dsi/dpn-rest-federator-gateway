// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.client.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit tests for {@link RestClient#buildWebClient()} - previously entirely untested. Every
 * existing {@code RestClient} test uses a test subclass that overrides {@code buildWebClient()}
 * outright (see {@code RestClientBootstrapTest}'s {@code protected WebClient buildWebClient() {
 * return webClient; }}), so the real implementation - the {@code VaultTlsSupport} branch, the
 * file-based PKCS12/JKS branch, and the wrapping/rethrow on failure - has never actually been
 * exercised.
 * <p>
 * Uses {@code RestClient}'s public testing constructor, then reflection to set the private
 * {@code commonProps} field and to invoke the protected {@code buildWebClient()} directly - the
 * only way to reach the real implementation without a full bootstrap.
 * </p>
 */
class RestClientBuildWebClientTest {

    private RestClient newTestClient() {
        return new RestClient(
                Map.of(), mock(IdpTokenService.class), mock(OcspClientVerificationService.class), Duration.ofSeconds(60));
    }

    private void setCommonProps(RestClient client, Properties properties) throws Exception {
        Field field = RestClient.class.getDeclaredField("commonProps");
        field.setAccessible(true);
        field.set(client, properties);
    }

    private WebClient invokeBuildWebClient(RestClient client) throws Throwable {
        Method method = RestClient.class.getDeclaredMethod("buildWebClient");
        method.setAccessible(true);
        try {
            return (WebClient) method.invoke(client);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Runs {@code keytool -genkeypair}, producing a keystore containing one self-signed
     * certificate, in the requested storetype - the same technique proven in {@code SSLUtilsTest}
     * earlier this session, with zero new test dependency.
     */
    private Path generateKeystore(Path dir, String fileName, String storeType) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "rest-client-test",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=rest-client-build-webclient-test",
                        "-storetype",
                        storeType,
                        "-keystore",
                        file.toString(),
                        "-storepass",
                        "changeit",
                        "-keypass",
                        "changeit")
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "keytool failed (exit " + exitCode + "): " + new String(process.getInputStream().readAllBytes()));
        }
        return file;
    }

    // ------------------------------------------------------------------
    // File-based mTLS path (VaultTlsSupport not enabled) - real success case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildWebClient() builds a real WebClient from genuine PKCS12 keystore and JKS "
            + "truststore files (the file-based mTLS path, VaultTlsSupport disabled)")
    void buildWebClient_fileBasedMtls_realKeystoreAndTruststore_buildsSuccessfully(@TempDir Path tempDir)
            throws Throwable {
        // SSLUtils.createSSLContext() hardcodes PKCS12 for the keystore and JKS for the
        // truststore (unlike the auto-detecting overloads elsewhere in this codebase) - matching
        // that exactly here, not the JKS/PKCS12-either-way pattern used in other tests.
        Path keystore = generateKeystore(tempDir, "identity.p12", "PKCS12");
        Path truststore = generateKeystore(tempDir, "truststore.jks", "JKS");

        Properties properties = new Properties();
        properties.setProperty("idp.keystore.path", keystore.toString());
        properties.setProperty("idp.keystore.password", "changeit");
        properties.setProperty("idp.truststore.path", truststore.toString());
        properties.setProperty("idp.truststore.password", "changeit");

        RestClient client = newTestClient();
        setCommonProps(client, properties);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            WebClient webClient = invokeBuildWebClient(client);

            assertThat(webClient).isNotNull();
        }
    }

    @Test
    @DisplayName("buildWebClient() wraps a missing keystore file in IllegalStateException, "
            + "naming the failure as building the mTLS WebClient")
    void buildWebClient_fileBasedMtls_missingKeystoreFile_throwsIllegalStateException(@TempDir Path tempDir)
            throws Exception {
        Properties properties = new Properties();
        properties.setProperty("idp.keystore.path", tempDir.resolve("does-not-exist.p12").toString());
        properties.setProperty("idp.keystore.password", "changeit");
        properties.setProperty("idp.truststore.path", tempDir.resolve("also-missing.jks").toString());
        properties.setProperty("idp.truststore.password", "changeit");

        RestClient client = newTestClient();
        setCommonProps(client, properties);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            assertThatThrownBy(() -> invokeBuildWebClient(client))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to build mTLS WebClient");
        }
    }

    @Test
    @DisplayName("buildWebClient() wraps a wrong keystore password in IllegalStateException")
    void buildWebClient_fileBasedMtls_wrongPassword_throwsIllegalStateException(@TempDir Path tempDir)
            throws Exception {
        Path keystore = generateKeystore(tempDir, "identity.p12", "PKCS12");
        Path truststore = generateKeystore(tempDir, "truststore.jks", "JKS");

        Properties properties = new Properties();
        properties.setProperty("idp.keystore.path", keystore.toString());
        properties.setProperty("idp.keystore.password", "totally-wrong-password");
        properties.setProperty("idp.truststore.path", truststore.toString());
        properties.setProperty("idp.truststore.password", "changeit");

        RestClient client = newTestClient();
        setCommonProps(client, properties);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            assertThatThrownBy(() -> invokeBuildWebClient(client)).isInstanceOf(IllegalStateException.class);
        }
    }

    // ------------------------------------------------------------------
    // Vault-backed mTLS path (VaultTlsSupport enabled) - real success case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildWebClient() uses VaultTlsSupport's in-memory SSLContext directly when "
            + "Vault TLS is enabled, bypassing the file-based keystore/truststore properties entirely")
    void buildWebClient_vaultTlsEnabled_usesVaultSslContext() throws Throwable {
        RestClient client = newTestClient();
        // Deliberately empty commonProps - if this path incorrectly fell through to the
        // file-based branch, it would fail on the missing idp.keystore.path property, proving
        // the Vault branch was genuinely taken instead.
        setCommonProps(client, new Properties());

        SSLContext trustAllContext = SSLContext.getInstance("TLS");
        trustAllContext.init(null, null, null);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(true);
            vaultTlsMock.when(VaultTlsSupport::sslContext).thenReturn(trustAllContext);

            WebClient webClient = invokeBuildWebClient(client);

            assertThat(webClient).isNotNull();
        }
    }

    @Test
    @DisplayName("buildWebClient() wraps a VaultTlsSupport failure in IllegalStateException too")
    void buildWebClient_vaultTlsEnabled_vaultSslContextThrows_throwsIllegalStateException() throws Exception {
        RestClient client = newTestClient();
        setCommonProps(client, new Properties());

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(true);
            vaultTlsMock
                    .when(VaultTlsSupport::sslContext)
                    .thenThrow(new FederatorSslException("Vault unreachable", null));

            assertThatThrownBy(() -> invokeBuildWebClient(client))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to build mTLS WebClient");
        }
    }
}
