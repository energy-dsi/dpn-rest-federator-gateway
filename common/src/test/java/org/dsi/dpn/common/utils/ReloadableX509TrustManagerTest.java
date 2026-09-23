// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ReloadableX509TrustManager}, using genuinely real PKCS12 truststores
 * generated with {@code keytool} (matching {@code SSLUtilsTest}/{@code ReloadableX509KeyManagerTest}'s
 * house style) rather than mocked {@link X509TrustManager} instances, so delegation is exercised
 * against real certificate data. No existing test file covered this class before.
 */
class ReloadableX509TrustManagerTest {

    private static final String PASSWORD = "changeit";

    private X509TrustManager realTrustManager(Path dir, String fileName, String alias) throws Exception {
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
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        for (var manager : tmf.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509TrustManager produced by TrustManagerFactory");
    }

    /**
     * Wraps a real {@link X509TrustManager} so it is deliberately NOT an
     * {@link X509ExtendedTrustManager}, letting tests exercise
     * {@link ReloadableX509TrustManager}'s "plain delegate" fallback branches for the
     * socket/engine overloads (the JDK's real TrustManagerFactory output IS extended, so without
     * this wrapper only the "extended" branch would ever be reached).
     */
    private static X509TrustManager plainWrap(X509TrustManager real) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                real.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                real.checkServerTrusted(chain, authType);
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return real.getAcceptedIssuers();
            }
        };
    }

    // ------------------------------------------------------------------
    // Constructor / setDelegate null checks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Constructor rejects a null delegate")
    void constructor_nullDelegate_throws() {
        assertThatThrownBy(() -> new ReloadableX509TrustManager(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setDelegate rejects a null replacement")
    void setDelegate_null_throws(@TempDir Path tempDir) throws Exception {
        ReloadableX509TrustManager manager =
                new ReloadableX509TrustManager(realTrustManager(tempDir, "ts1.p12", "entry-1"));

        assertThatThrownBy(() -> manager.setDelegate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Standard X509TrustManager methods delegate to the current delegate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getAcceptedIssuers() delegates to the current delegate")
    void getAcceptedIssuers_delegatesToCurrentDelegate(@TempDir Path tempDir) throws Exception {
        X509TrustManager delegate = realTrustManager(tempDir, "ts1.p12", "entry-1");
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(delegate);

        assertThat(manager.getAcceptedIssuers()).isEqualTo(delegate.getAcceptedIssuers());
        assertThat(manager.getAcceptedIssuers()).isNotEmpty();
    }

    @Test
    @DisplayName("checkClientTrusted/checkServerTrusted(chain, authType) delegate and accept a chain "
            + "trusted by the delegate's own issuer")
    void checkClientAndServerTrusted_trustedChain_delegatesWithoutThrowing(@TempDir Path tempDir) throws Exception {
        X509TrustManager delegate = realTrustManager(tempDir, "ts1.p12", "entry-1");
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(delegate);
        X509Certificate[] selfSignedChain = delegate.getAcceptedIssuers();

        // A self-signed cert trusts itself: calling with its own certificate as the chain should
        // not throw, for both client and server checks, on both the manager and the delegate.
        manager.checkClientTrusted(selfSignedChain, "RSA");
        manager.checkServerTrusted(selfSignedChain, "RSA");
    }

    @Test
    @DisplayName("checkClientTrusted/checkServerTrusted(chain, authType) throw CertificateException for an "
            + "untrusted chain, matching the delegate's own behaviour")
    void checkClientAndServerTrusted_untrustedChain_throwsCertificateException(@TempDir Path tempDir) throws Exception {
        X509TrustManager delegate = realTrustManager(tempDir, "ts1.p12", "entry-1");
        X509TrustManager otherManager = realTrustManager(tempDir, "ts2.p12", "entry-2");
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(delegate);
        X509Certificate[] untrustedChain = otherManager.getAcceptedIssuers();

        assertThatThrownBy(() -> manager.checkClientTrusted(untrustedChain, "RSA"))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> manager.checkServerTrusted(untrustedChain, "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    // ------------------------------------------------------------------
    // setDelegate() atomically swaps which certificate subsequent calls trust
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setDelegate atomically swaps which certificate chain is trusted by subsequent calls")
    void setDelegate_swapsTrustedIssuer(@TempDir Path tempDir) throws Exception {
        X509TrustManager first = realTrustManager(tempDir, "ts1.p12", "entry-1");
        X509TrustManager second = realTrustManager(tempDir, "ts2.p12", "entry-2");
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(first);

        X509Certificate[] firstChain = first.getAcceptedIssuers();
        X509Certificate[] secondChain = second.getAcceptedIssuers();

        // Initially trusts only 'first's issuer.
        manager.checkClientTrusted(firstChain, "RSA");
        assertThatThrownBy(() -> manager.checkClientTrusted(secondChain, "RSA")).isInstanceOf(CertificateException.class);

        manager.setDelegate(second);

        // After swap, trusts only 'second's issuer.
        manager.checkClientTrusted(secondChain, "RSA");
        assertThatThrownBy(() -> manager.checkClientTrusted(firstChain, "RSA")).isInstanceOf(CertificateException.class);
    }

    // ------------------------------------------------------------------
    // Extended (Socket / SSLEngine) overloads - "extended" branch (real delegate IS extended)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checkClientTrusted/checkServerTrusted(chain, authType, Socket) use the extended overload "
            + "when the delegate is a real X509ExtendedTrustManager")
    void checkTrusted_withSocket_extendedDelegate_usesExtendedOverload(@TempDir Path tempDir) throws Exception {
        X509TrustManager delegate = realTrustManager(tempDir, "ts1.p12", "entry-1");
        assertThat(delegate).isInstanceOf(X509ExtendedTrustManager.class);
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(delegate);
        X509Certificate[] trustedChain = delegate.getAcceptedIssuers();

        // Null socket is accepted by the extended overload (it degrades to the plain check when
        // there is no socket-specific endpoint identification algorithm configured).
        manager.checkClientTrusted(trustedChain, "RSA", (java.net.Socket) null);
        manager.checkServerTrusted(trustedChain, "RSA", (java.net.Socket) null);
    }

    @Test
    @DisplayName("checkClientTrusted/checkServerTrusted(chain, authType, SSLEngine) use the extended overload "
            + "when the delegate is a real X509ExtendedTrustManager")
    void checkTrusted_withSslEngine_extendedDelegate_usesExtendedOverload(@TempDir Path tempDir) throws Exception {
        X509TrustManager delegate = realTrustManager(tempDir, "ts1.p12", "entry-1");
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(delegate);
        X509Certificate[] trustedChain = delegate.getAcceptedIssuers();

        manager.checkClientTrusted(trustedChain, "RSA", (javax.net.ssl.SSLEngine) null);
        manager.checkServerTrusted(trustedChain, "RSA", (javax.net.ssl.SSLEngine) null);
    }

    // ------------------------------------------------------------------
    // Extended (Socket / SSLEngine) overloads - "plain delegate" fallback branch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checkClientTrusted/checkServerTrusted(chain, authType, Socket) fall back to the plain "
            + "checkXTrusted(chain, authType) when the delegate is not an X509ExtendedTrustManager")
    void checkTrusted_withSocket_plainDelegate_fallsBackToPlainCheck(@TempDir Path tempDir) throws Exception {
        X509TrustManager real = realTrustManager(tempDir, "ts1.p12", "entry-1");
        X509TrustManager plainDelegate = plainWrap(real);
        assertThat(plainDelegate).isNotInstanceOf(X509ExtendedTrustManager.class);
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(plainDelegate);
        X509Certificate[] trustedChain = real.getAcceptedIssuers();

        manager.checkClientTrusted(trustedChain, "RSA", (java.net.Socket) null);
        manager.checkServerTrusted(trustedChain, "RSA", (java.net.Socket) null);
    }

    @Test
    @DisplayName("checkClientTrusted/checkServerTrusted(chain, authType, SSLEngine) fall back to the plain "
            + "checkXTrusted(chain, authType) when the delegate is not an X509ExtendedTrustManager")
    void checkTrusted_withSslEngine_plainDelegate_fallsBackToPlainCheck(@TempDir Path tempDir) throws Exception {
        X509TrustManager real = realTrustManager(tempDir, "ts1.p12", "entry-1");
        X509TrustManager plainDelegate = plainWrap(real);
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(plainDelegate);
        X509Certificate[] trustedChain = real.getAcceptedIssuers();

        manager.checkClientTrusted(trustedChain, "RSA", (javax.net.ssl.SSLEngine) null);
        manager.checkServerTrusted(trustedChain, "RSA", (javax.net.ssl.SSLEngine) null);
    }

    @Test
    @DisplayName("checkClientTrusted(chain, authType, Socket) with a plain delegate still throws "
            + "CertificateException for an untrusted chain")
    void checkTrusted_withSocket_plainDelegate_untrustedChain_throws(@TempDir Path tempDir) throws Exception {
        X509TrustManager real = realTrustManager(tempDir, "ts1.p12", "entry-1");
        X509TrustManager otherReal = realTrustManager(tempDir, "ts2.p12", "entry-2");
        ReloadableX509TrustManager manager = new ReloadableX509TrustManager(plainWrap(real));

        assertThatThrownBy(() ->
                        manager.checkClientTrusted(otherReal.getAcceptedIssuers(), "RSA", (java.net.Socket) null))
                .isInstanceOf(CertificateException.class);
    }
}
