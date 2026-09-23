// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Properties;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleRegistry;

/**
 * Unit tests for {@link VaultSslBundleRegistrar}. Same {@link PropertyUtil} static-mocking
 * approach as {@link VaultFileBasedSslBundleInitializerTest}; {@link SslBundleRegistry} is a
 * simple Spring Boot interface mocked directly with Mockito.
 */
class VaultSslBundleRegistrarTest {

    private static final String PASSWORD = "changeit";

    private MockedStatic<PropertyUtil> propertyUtilMock;

    private void mockPropertyUtil(Properties commonConfig, SecretProvider provider) {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(commonConfig);
        propertyUtilMock.when(() -> PropertyUtil.createSecretProvider(commonConfig)).thenReturn(provider);
    }

    @AfterEach
    void tearDown() {
        if (propertyUtilMock != null) {
            propertyUtilMock.close();
        }
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
        return new PemMaterial(toPem(cert.getEncoded(), "CERTIFICATE"), toPem(key.getEncoded(), "PRIVATE KEY"));
    }

    private static String toPem(byte[] der, String type) {
        String base64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    @Test
    @DisplayName("registerBundles does nothing when vault.tls.enabled=false")
    void registerBundles_vaultTlsDisabled_doesNothing() {
        Properties common = new Properties();
        common.setProperty("vault.tls.enabled", "false");
        mockPropertyUtil(common, null);
        SslBundleRegistry registry = mock(SslBundleRegistry.class);

        new VaultSslBundleRegistrar("federator-tls").registerBundles(registry);

        verify(registry, never()).registerBundle(any(), any());
    }

    @Test
    @DisplayName("registerBundles registers a real, working SslBundle built from Vault material")
    void registerBundles_vaultTlsEnabled_registersWorkingBundle(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generatePem(dir, "leaf.p12", "leaf.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret("node-net/client/certificate", "certificate")).thenReturn(leaf.certPem());
        when(provider.getSecret("node-net/client/keypair", "privateKey")).thenReturn(leaf.keyPem());
        when(provider.getSecret("node-net/client/ca-chain", "chain")).thenReturn(null);

        Properties common = new Properties();
        common.setProperty("vault.tls.enabled", "true");
        common.setProperty("vault.tls.reload.interval.seconds", "0");
        mockPropertyUtil(common, provider);

        SslBundleRegistry registry = mock(SslBundleRegistry.class);
        new VaultSslBundleRegistrar("federator-tls").registerBundles(registry);

        ArgumentCaptor<SslBundle> captor = ArgumentCaptor.forClass(SslBundle.class);
        verify(registry).registerBundle(eq("federator-tls"), captor.capture());
        SslBundle bundle = captor.getValue();
        assertThat(bundle.getStores().getKeyStore().isKeyEntry("federator")).isTrue();
    }

    @Test
    @DisplayName("registerBundles wraps a build failure as IllegalStateException")
    void registerBundles_buildFails_wrapsIllegalStateException() {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret("node-net/client/certificate", "certificate")).thenReturn(null);

        Properties common = new Properties();
        common.setProperty("vault.tls.enabled", "true");
        common.setProperty("vault.tls.reload.interval.seconds", "0");
        mockPropertyUtil(common, provider);

        SslBundleRegistry registry = mock(SslBundleRegistry.class);

        assertThatThrownBy(() -> new VaultSslBundleRegistrar("federator-tls").registerBundles(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("federator-tls");
        verify(registry, never()).registerBundle(any(), any());
    }
}
