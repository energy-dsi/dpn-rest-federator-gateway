// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ReloadableX509KeyManager}, using genuinely real PKCS12 keystores
 * generated with {@code keytool} (matching {@code SSLUtilsTest}'s house style) rather than mocked
 * {@link X509KeyManager} instances, so the delegation is exercised against real certificate data.
 */
class ReloadableX509KeyManagerTest {

    private static final String PASSWORD = "changeit";

    private X509KeyManager realKeyManager(Path dir, String fileName, String alias) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        alias,
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=" + alias,
                        "-storetype",
                        "PKCS12",
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

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream is = new FileInputStream(file.toFile())) {
            keyStore.load(is, PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD.toCharArray());
        for (var manager : kmf.getKeyManagers()) {
            if (manager instanceof X509KeyManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509KeyManager produced by KeyManagerFactory");
    }

    @Test
    @DisplayName("Constructor rejects a null delegate")
    void constructor_nullDelegate_throws() {
        assertThatThrownBy(() -> new ReloadableX509KeyManager(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setDelegate rejects a null replacement")
    void setDelegate_null_throws(@TempDir Path tempDir) throws Exception {
        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(realKeyManager(tempDir, "ks1.p12", "entry-1"));

        assertThatThrownBy(() -> manager.setDelegate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getCertificateChain/getPrivateKey/getClientAliases/getServerAliases delegate to the current delegate")
    void allMethods_delegateToUnderlyingManager(@TempDir Path tempDir) throws Exception {
        X509KeyManager delegate = realKeyManager(tempDir, "ks1.p12", "entry-1");
        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(delegate);

        assertThat(manager.getCertificateChain("entry-1")).isEqualTo(delegate.getCertificateChain("entry-1"));
        assertThat(manager.getCertificateChain("entry-1")).isNotEmpty();
        assertThat(manager.getPrivateKey("entry-1")).isEqualTo(delegate.getPrivateKey("entry-1"));
        assertThat(manager.getClientAliases("RSA", null)).isEqualTo(delegate.getClientAliases("RSA", null));
        assertThat(manager.getServerAliases("RSA", null)).isEqualTo(delegate.getServerAliases("RSA", null));
        assertThat(manager.chooseClientAlias(new String[] {"RSA"}, null, null))
                .isEqualTo(delegate.chooseClientAlias(new String[] {"RSA"}, null, null));
        assertThat(manager.chooseServerAlias("RSA", null, null))
                .isEqualTo(delegate.chooseServerAlias("RSA", null, null));
    }

    @Test
    @DisplayName("setDelegate atomically swaps which certificate subsequent calls observe")
    void setDelegate_swapsUnderlyingCertificate(@TempDir Path tempDir) throws Exception {
        X509KeyManager first = realKeyManager(tempDir, "ks1.p12", "entry-1");
        X509KeyManager second = realKeyManager(tempDir, "ks2.p12", "entry-2");
        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(first);

        assertThat(manager.getCertificateChain("entry-1")).isNotEmpty();
        assertThat(manager.getCertificateChain("entry-2")).isNull();

        manager.setDelegate(second);

        assertThat(manager.getCertificateChain("entry-2")).isNotEmpty();
        assertThat(manager.getCertificateChain("entry-1")).isNull();
    }

    @Test
    @DisplayName("chooseEngineClientAlias delegates to the X509ExtendedKeyManager overload when the delegate supports it")
    void chooseEngineClientAlias_extendedDelegate_usesExtendedOverload(@TempDir Path tempDir) throws Exception {
        X509KeyManager delegate = realKeyManager(tempDir, "ks1.p12", "entry-1");
        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(delegate);

        // The real KeyManagerFactory-produced manager is itself an X509ExtendedKeyManager, so this
        // exercises the "extended" branch, not the plain-X509KeyManager fallback.
        assertThat(delegate).isInstanceOf(X509ExtendedKeyManager.class);

        // With a null SSLEngine and no matching keyType, both the extended path and a real
        // delegate return null - what matters here is that no exception is thrown and the call is
        // actually forwarded (verified by the equivalent direct call producing the same result).
        String viaManager = manager.chooseEngineClientAlias(new String[] {"RSA"}, null, null);
        String viaDelegate = ((X509ExtendedKeyManager) delegate).chooseEngineClientAlias(new String[] {"RSA"}, null, null);
        assertThat(viaManager).isEqualTo(viaDelegate);
    }

    @Test
    @DisplayName("chooseEngineServerAlias delegates to the X509ExtendedKeyManager overload when the delegate supports it")
    void chooseEngineServerAlias_extendedDelegate_usesExtendedOverload(@TempDir Path tempDir) throws Exception {
        X509KeyManager delegate = realKeyManager(tempDir, "ks1.p12", "entry-1");
        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(delegate);

        String viaManager = manager.chooseEngineServerAlias("RSA", null, null);
        String viaDelegate = ((X509ExtendedKeyManager) delegate).chooseEngineServerAlias("RSA", null, null);
        assertThat(viaManager).isEqualTo(viaDelegate);
    }

    @Test
    @DisplayName("chooseEngineClientAlias falls back to chooseClientAlias when the delegate is a plain X509KeyManager")
    void chooseEngineClientAlias_plainDelegate_fallsBackToChooseClientAlias() throws Exception {
        X509KeyManager plainDelegate = new X509KeyManager() {
            @Override
            public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
                return new String[] {"plain-alias"};
            }

            @Override
            public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) {
                return "plain-alias";
            }

            @Override
            public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
                return new String[] {"plain-alias"};
            }

            @Override
            public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) {
                return "plain-alias";
            }

            @Override
            public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
                return null;
            }

            @Override
            public java.security.PrivateKey getPrivateKey(String alias) {
                return null;
            }
        };

        ReloadableX509KeyManager manager = new ReloadableX509KeyManager(plainDelegate);

        assertThat(manager.chooseEngineClientAlias(new String[] {"RSA"}, null, null)).isEqualTo("plain-alias");
        assertThat(manager.chooseEngineServerAlias("RSA", null, null)).isEqualTo("plain-alias");
    }
}
