// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Properties;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link VaultFileBasedSslBundleInitializer}.
 * <p>
 * {@link PropertyUtil} is a static-singleton dependency ({@code initializeProperties},
 * {@code getPropertiesFromFilePath}, {@code createSecretProvider}) with no instance seam, mocked
 * via Mockito's {@code mockStatic} - the same pattern already used in
 * {@code IdpTokenServiceClientSecretImplTest}. The returned {@link SecretProvider} is backed by
 * genuinely generated RSA keys/certs (via {@code keytool}, see {@link VaultKeystoreProviderTest})
 * so the real {@link VaultKeystoreProvider} PEM-parsing and {@link KeyStore} file-writing logic in
 * this class is genuinely exercised end-to-end - only the network hop to Vault is replaced.
 * </p>
 */
class VaultFileBasedSslBundleInitializerTest {

    private static final String PASSWORD = "changeit";

    private MockedStatic<PropertyUtil> propertyUtilMock;

    private void mockPropertyUtil(Properties commonConfig, SecretProvider provider, boolean initializedOk) {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(PropertyUtil::initializeProperties).thenReturn(initializedOk);
        propertyUtilMock.when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(commonConfig);
        propertyUtilMock.when(() -> PropertyUtil.createSecretProvider(commonConfig)).thenReturn(provider);
    }

    @AfterEach
    void tearDown() {
        if (propertyUtilMock != null) {
            propertyUtilMock.close();
        }
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.key.alias");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.keystore.location");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.keystore.type");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.truststore.location");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.truststore.type");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.keystore.password");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.truststore.password");
        System.clearProperty("spring.ssl.bundle.jks.test-bundle.reload-on-update");
        System.clearProperty("spring.ssl.bundle.jks.disabled-bundle.keystore.location");
    }

    private record PemMaterial(String certPem, String keyPem) {
    }

    private PemMaterial generatePem(Path dir, String fileName, String cn) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool", "-genkeypair", "-alias", "entry", "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "365", "-dname", "CN=" + cn, "-storetype", "PKCS12",
                        "-keystore", file.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "keytool failed (exit " + exitCode + "): " + new String(process.getInputStream().readAllBytes()));
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(file)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("entry", PASSWORD.toCharArray());
        Certificate cert = ks.getCertificate("entry");
        String certPem = toPem(cert.getEncoded(), "CERTIFICATE");
        String keyPem = toPem(key.getEncoded(), "PRIVATE KEY");
        return new PemMaterial(certPem, keyPem);
    }

    private static String toPem(byte[] der, String type) {
        String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    @Test
    @DisplayName("initialise throws IllegalStateException when PropertyUtil fails to initialise")
    void initialise_propertyUtilFails_throwsIllegalStateException() {
        mockPropertyUtil(new Properties(), null, false);

        assertThatThrownBy(() -> VaultFileBasedSslBundleInitializer.initialise("test-bundle"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-bundle")
                .hasMessageContaining("FEDERATOR_SERVER_PROPERTIES");
    }

    @Test
    @DisplayName("initialise is a no-op (sets no system properties) when vault.tls.enabled=false")
    void initialise_vaultTlsDisabled_doesNothing() throws Exception {
        Properties common = new Properties();
        common.setProperty("vault.tls.enabled", "false");
        mockPropertyUtil(common, null, true);

        VaultFileBasedSslBundleInitializer.initialise("disabled-bundle");

        assertThat(System.getProperty("spring.ssl.bundle.jks.disabled-bundle.keystore.location")).isNull();
    }

    @Test
    @DisplayName("initialise writes real PKCS12 files from Vault material and configures the bundle system properties")
    void initialise_vaultTlsEnabled_writesFilesAndConfiguresProperties(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generatePem(dir, "leaf.p12", "leaf.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret("node-net/client/certificate", "certificate")).thenReturn(leaf.certPem());
        when(provider.getSecret("node-net/client/keypair", "privateKey")).thenReturn(leaf.keyPem());
        when(provider.getSecret("node-net/client/ca-chain", "chain")).thenReturn(null);

        Properties common = new Properties();
        common.setProperty("vault.tls.enabled", "true");
        common.setProperty("vault.tls.reload.interval.seconds", "0"); // disable background reload for the test
        mockPropertyUtil(common, provider, true);

        VaultFileBasedSslBundleInitializer.initialise("test-bundle");

        String keystoreLocation = System.getProperty("spring.ssl.bundle.jks.test-bundle.keystore.location");
        String truststoreLocation = System.getProperty("spring.ssl.bundle.jks.test-bundle.truststore.location");
        assertThat(keystoreLocation).startsWith("file:");
        assertThat(truststoreLocation).startsWith("file:");
        assertThat(System.getProperty("spring.ssl.bundle.jks.test-bundle.key.alias")).isEqualTo("federator");
        assertThat(System.getProperty("spring.ssl.bundle.jks.test-bundle.keystore.type")).isEqualTo("PKCS12");
        assertThat(System.getProperty("spring.ssl.bundle.jks.test-bundle.reload-on-update")).isEqualTo("true");
        String password = System.getProperty("spring.ssl.bundle.jks.test-bundle.keystore.password");
        assertThat(password).isNotBlank();

        Path keystoreFile = Path.of(keystoreLocation.substring("file:".length()));
        assertThat(Files.exists(keystoreFile)).isTrue();
        KeyStore written = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystoreFile)) {
            written.load(in, password.toCharArray());
        }
        assertThat(written.isKeyEntry("federator")).isTrue();
    }

    @Test
    @DisplayName("initialise propagates a FederatorSslException when Vault does not have the expected material")
    void initialise_missingVaultMaterial_propagatesFederatorSslException() {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret("node-net/client/certificate", "certificate")).thenReturn(null);

        Properties common = new Properties();
        common.setProperty("vault.tls.enabled", "true");
        common.setProperty("vault.tls.reload.interval.seconds", "0");
        mockPropertyUtil(common, provider, true);

        assertThatThrownBy(() -> VaultFileBasedSslBundleInitializer.initialise("test-bundle"))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("leaf certificate");
    }
}
