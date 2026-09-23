// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.ocsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link OcspClientVerificationServiceImpl#buildHttpClient(Properties)} - the real
 * implementation was previously entirely untested, since {@code OcspClientVerificationServiceImplTest}
 * always overrides it with an anonymous subclass returning a mock {@code HttpClient}.
 *
 * <p>Uses the same real-{@code keytool}-keystore technique as
 * {@code RestClientBuildWebClientTest} / {@code common}'s {@code SSLUtilsTest} rather than mocking
 * crypto material, and calls the protected method directly (same package as the class under test).
 */
class OcspClientVerificationServiceImplBuildHttpClientTest {

    private OcspClientVerificationServiceImpl newService(Properties props) {
        return new OcspClientVerificationServiceImpl(props, mock(IdpTokenService.class));
    }

    private Path generateKeystore(Path dir, String fileName, String storeType) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "ocsp-client-test",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=ocsp-client-build-httpclient-test",
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

    @Test
    @DisplayName("buildHttpClient() builds a real HttpClient from genuine PKCS12/JKS files "
            + "(file-based path, VaultTlsSupport disabled)")
    void buildHttpClient_fileBasedMtls_realKeystoreAndTruststore_buildsSuccessfully(@TempDir Path tempDir)
            throws Exception {
        Path keystore = generateKeystore(tempDir, "identity.p12", "PKCS12");
        Path truststore = generateKeystore(tempDir, "truststore.jks", "JKS");

        Properties properties = new Properties();
        properties.setProperty("management.node.base.url", "https://mn.test");
        properties.setProperty("idp.keystore.path", keystore.toString());
        properties.setProperty("idp.keystore.password", "changeit");
        properties.setProperty("idp.truststore.path", truststore.toString());
        properties.setProperty("idp.truststore.password", "changeit");

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            OcspClientVerificationServiceImpl service = newService(properties);
            HttpClient httpClient = service.buildHttpClient(properties);

            assertThat(httpClient).isNotNull();
            assertThat(httpClient.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        }
    }

    @Test
    @DisplayName("buildHttpClient() wraps a missing keystore file in FederatorSslException")
    void buildHttpClient_fileBasedMtls_missingKeystoreFile_throwsFederatorSslException(@TempDir Path tempDir) {
        Properties properties = new Properties();
        properties.setProperty("management.node.base.url", "https://mn.test");
        properties.setProperty("idp.keystore.path", tempDir.resolve("does-not-exist.p12").toString());
        properties.setProperty("idp.keystore.password", "changeit");
        properties.setProperty("idp.truststore.path", tempDir.resolve("also-missing.jks").toString());
        properties.setProperty("idp.truststore.password", "changeit");

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(false);

            // The constructor itself calls buildHttpClient(properties), so the real
            // implementation's exception-wrapping is exercised right here.
            assertThatThrownBy(() -> newService(properties))
                    .isInstanceOf(FederatorSslException.class)
                    .hasMessageContaining("Failed to build OCSP HttpClient");
        }
    }

    @Test
    @DisplayName("buildHttpClient() uses VaultTlsSupport's in-memory SSLContext directly when Vault "
            + "TLS is enabled, bypassing the file-based keystore/truststore properties entirely")
    void buildHttpClient_vaultTlsEnabled_usesVaultSslContext() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("management.node.base.url", "https://mn.test");
        // Deliberately no keystore/truststore properties - if this incorrectly fell through to
        // the file-based branch it would fail, proving the Vault branch was genuinely taken.

        SSLContext trustAllContext = SSLContext.getInstance("TLS");
        trustAllContext.init(null, null, null);

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(true);
            vaultTlsMock.when(VaultTlsSupport::sslContext).thenReturn(trustAllContext);

            OcspClientVerificationServiceImpl service = newService(properties);
            HttpClient httpClient = service.buildHttpClient(properties);

            assertThat(httpClient).isNotNull();
        }
    }

    @Test
    @DisplayName("buildHttpClient() wraps a VaultTlsSupport failure in FederatorSslException too")
    void buildHttpClient_vaultTlsEnabled_vaultSslContextThrows_throwsFederatorSslException() {
        Properties properties = new Properties();
        properties.setProperty("management.node.base.url", "https://mn.test");

        try (MockedStatic<VaultTlsSupport> vaultTlsMock = mockStatic(VaultTlsSupport.class)) {
            vaultTlsMock.when(VaultTlsSupport::isVaultTlsEnabled).thenReturn(true);
            vaultTlsMock.when(VaultTlsSupport::sslContext)
                    .thenThrow(new FederatorSslException("Vault unreachable", null));

            assertThatThrownBy(() -> newService(properties))
                    .isInstanceOf(FederatorSslException.class)
                    .hasMessageContaining("Failed to build OCSP HttpClient");
        }
    }
}
