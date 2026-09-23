// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link VaultKeystoreProvider}.
 * <p>
 * Uses {@code keytool} (via {@link ProcessBuilder}) to generate genuinely real RSA key pairs and
 * self-signed X.509 certificates in a JUnit {@code @TempDir}, the same technique
 * {@code SSLUtilsTest} uses. The generated PKCS12 keystore's entries are then read back through
 * the real {@link KeyStore} API and PEM-encoded in memory, giving this test genuine certificate
 * and PKCS#8 private-key PEM material - exactly the shape {@link VaultKeystoreProvider} parses
 * from Vault - without needing a live Vault server or any mocking of crypto primitives.
 * </p>
 * <p>{@link SecretProvider} - the only collaborator - is mocked with Mockito to simulate Vault's
 * KV responses.</p>
 */
class VaultKeystoreProviderTest {

    private static final String PASSWORD = "changeit";
    private static final String BASE_PATH = "node-net/client";
    private static final String ALIAS = "federator";

    private record PemMaterial(String certPem, String keyPem) {
    }

    private PemMaterial generatePem(Path dir, String fileName, String cn) throws Exception {
        Path file = dir.resolve(fileName);
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "entry",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=" + cn,
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

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(file)) {
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

    // ------------------------------------------------------------------
    // buildIdentityKeyStore
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildIdentityKeyStore builds a valid PKCS12 keystore from leaf cert + key, no chain")
    void buildIdentityKeyStore_noChain_success(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generatePem(dir, "leaf.p12", "leaf.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate")).thenReturn(leaf.certPem());
        when(provider.getSecret(BASE_PATH + "/keypair", "privateKey")).thenReturn(leaf.keyPem());
        when(provider.getSecret(BASE_PATH + "/ca-chain", "chain")).thenReturn(null);

        KeyStore keyStore = VaultKeystoreProvider.buildIdentityKeyStore(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray());

        assertThat(keyStore.isKeyEntry(ALIAS)).isTrue();
        Certificate[] chain = keyStore.getCertificateChain(ALIAS);
        assertThat(chain).hasSize(1);
        assertThat(((X509Certificate) chain[0]).getSubjectX500Principal().getName()).contains("leaf.test");
        assertThat(keyStore.getKey(ALIAS, PASSWORD.toCharArray())).isNotNull();
    }

    /**
     * Generates a genuinely CA-signed leaf certificate (not just two unrelated self-signed
     * certs): {@link KeyStore#setKeyEntry} validates that each certificate in the supplied chain
     * is actually signed by the next, so a meaningful chain-building test needs a real
     * issuer/subject relationship, produced here via {@code keytool}'s CSR/{@code -gencert} flow.
     */
    private PemMaterial generateCaSignedLeaf(Path dir, String leafCn, String caCn) throws Exception {
        Path caKeystore = dir.resolve("ca.p12");
        Path leafKeystore = dir.resolve("leaf.p12");
        Path caCertFile = dir.resolve("ca.cer");
        Path csrFile = dir.resolve("leaf.csr");
        Path signedCertFile = dir.resolve("leaf-signed.cer");

        run("keytool", "-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-dname", "CN=" + caCn, "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", caKeystore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD);
        run("keytool", "-exportcert", "-alias", "ca", "-file", caCertFile.toString(), "-rfc",
                "-keystore", caKeystore.toString(), "-storepass", PASSWORD);

        run("keytool", "-genkeypair", "-alias", "entry", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-dname", "CN=" + leafCn, "-storetype", "PKCS12", "-keystore", leafKeystore.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD);
        run("keytool", "-certreq", "-alias", "entry", "-file", csrFile.toString(),
                "-keystore", leafKeystore.toString(), "-storepass", PASSWORD);
        run("keytool", "-gencert", "-alias", "ca", "-infile", csrFile.toString(), "-outfile", signedCertFile.toString(),
                "-rfc", "-validity", "365", "-keystore", caKeystore.toString(), "-storepass", PASSWORD);
        run("keytool", "-importcert", "-alias", "ca", "-file", caCertFile.toString(), "-noprompt",
                "-keystore", leafKeystore.toString(), "-storepass", PASSWORD);
        run("keytool", "-importcert", "-alias", "entry", "-file", signedCertFile.toString(),
                "-keystore", leafKeystore.toString(), "-storepass", PASSWORD);

        String leafCertPem = java.nio.file.Files.readString(signedCertFile);
        String caCertPem = java.nio.file.Files.readString(caCertFile);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(leafKeystore)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("entry", PASSWORD.toCharArray());
        String keyPem = toPem(key.getEncoded(), "PRIVATE KEY");
        return new PemMaterial(leafCertPem, keyPem);
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (exit " + exitCode + "): " + String.join(" ", command)
                    + "\n" + new String(process.getInputStream().readAllBytes()));
        }
    }

    @Test
    @DisplayName("buildIdentityKeyStore appends a genuinely CA-signed chain certificate after the leaf")
    void buildIdentityKeyStore_withChain_appendsCaCerts(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generateCaSignedLeaf(dir, "leaf.test", "ca.test");
        Path caCertFile = dir.resolve("ca.cer");
        String caCertPem = java.nio.file.Files.readString(caCertFile);

        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate")).thenReturn(leaf.certPem());
        when(provider.getSecret(BASE_PATH + "/keypair", "privateKey")).thenReturn(leaf.keyPem());
        when(provider.getSecret(BASE_PATH + "/ca-chain", "chain")).thenReturn(caCertPem);

        KeyStore keyStore = VaultKeystoreProvider.buildIdentityKeyStore(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray());

        Certificate[] chain = keyStore.getCertificateChain(ALIAS);
        assertThat(chain).hasSize(2);
        assertThat(((X509Certificate) chain[0]).getSubjectX500Principal().getName()).contains("leaf.test");
        assertThat(((X509Certificate) chain[1]).getSubjectX500Principal().getName()).contains("ca.test");
        // The leaf really was issued by this CA - not just two unrelated self-signed certs.
        ((X509Certificate) chain[0]).verify(chain[1].getPublicKey());
    }

    @Test
    @DisplayName("buildIdentityKeyStore throws FederatorSslException when Vault has no leaf certificate")
    void buildIdentityKeyStore_missingCertificate_throws() {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(eq(BASE_PATH + "/certificate"), eq("certificate"))).thenReturn(null);

        assertThatThrownBy(() ->
                        VaultKeystoreProvider.buildIdentityKeyStore(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray()))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("leaf certificate")
                .hasMessageContaining(BASE_PATH + "/certificate");
    }

    @Test
    @DisplayName("buildIdentityKeyStore throws FederatorSslException when Vault's private key is blank")
    void buildIdentityKeyStore_blankPrivateKey_throws(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generatePem(dir, "leaf.p12", "leaf.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate")).thenReturn(leaf.certPem());
        when(provider.getSecret(BASE_PATH + "/keypair", "privateKey")).thenReturn("   ");

        assertThatThrownBy(() ->
                        VaultKeystoreProvider.buildIdentityKeyStore(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray()))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("private key");
    }

    // ------------------------------------------------------------------
    // fetchLeafCertificate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchLeafCertificate returns just the parsed leaf certificate")
    void fetchLeafCertificate_returnsCert(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generatePem(dir, "leaf.p12", "leaf.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate")).thenReturn(leaf.certPem());

        X509Certificate cert = VaultKeystoreProvider.fetchLeafCertificate(provider, BASE_PATH);

        assertThat(cert.getSubjectX500Principal().getName()).contains("leaf.test");
    }

    // ------------------------------------------------------------------
    // buildTrustStore
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildTrustStore adds one entry per CA certificate in the chain")
    void buildTrustStore_multipleCas_addsEntries(@TempDir Path dir) throws Exception {
        PemMaterial ca1 = generatePem(dir, "ca1.p12", "ca1.test");
        PemMaterial ca2 = generatePem(dir, "ca2.p12", "ca2.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/ca-chain", "chain"))
                .thenReturn(ca1.certPem() + ca2.certPem());

        KeyStore trustStore = VaultKeystoreProvider.buildTrustStore(provider, BASE_PATH, PASSWORD.toCharArray());

        assertThat(trustStore.size()).isEqualTo(2);
        assertThat(trustStore.isCertificateEntry("ca-0")).isTrue();
        assertThat(trustStore.isCertificateEntry("ca-1")).isTrue();
    }

    @Test
    @DisplayName("buildTrustStore returns an empty store when Vault has no CA chain")
    void buildTrustStore_nullChain_empty() throws Exception {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/ca-chain", "chain")).thenReturn(null);

        KeyStore trustStore = VaultKeystoreProvider.buildTrustStore(provider, BASE_PATH, PASSWORD.toCharArray());

        assertThat(trustStore.size()).isZero();
    }

    // ------------------------------------------------------------------
    // keyManagers / trustManagers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("keyManagers returns a working X509KeyManager built from Vault material")
    void keyManagers_success(@TempDir Path dir) throws Exception {
        PemMaterial leaf = generatePem(dir, "leaf.p12", "leaf.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate")).thenReturn(leaf.certPem());
        when(provider.getSecret(BASE_PATH + "/keypair", "privateKey")).thenReturn(leaf.keyPem());

        KeyManager[] managers = VaultKeystoreProvider.keyManagers(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray());

        assertThat(managers).isNotEmpty();
        assertThat(managers[0]).isInstanceOf(X509KeyManager.class);
        X509KeyManager km = (X509KeyManager) managers[0];
        assertThat(km.getPrivateKey(ALIAS)).isNotNull();
    }

    @Test
    @DisplayName("keyManagers wraps a provider failure as FederatorSslException")
    void keyManagers_providerThrows_wrapsFederatorSslException() {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate"))
                .thenThrow(new RuntimeException("vault unreachable"));

        assertThatThrownBy(() -> VaultKeystoreProvider.keyManagers(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray()))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to build KeyManagers")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("keyManagers propagates FederatorSslException from missing Vault material unwrapped")
    void keyManagers_missingCertificate_propagatesFederatorSslException() {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/certificate", "certificate")).thenReturn(null);

        assertThatThrownBy(() -> VaultKeystoreProvider.keyManagers(provider, BASE_PATH, ALIAS, PASSWORD.toCharArray()))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Vault did not return leaf certificate");
    }

    @Test
    @DisplayName("trustManagers returns a working X509TrustManager built from Vault CA material")
    void trustManagers_success(@TempDir Path dir) throws Exception {
        PemMaterial ca = generatePem(dir, "ca.p12", "ca.test");
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/ca-chain", "chain")).thenReturn(ca.certPem());

        TrustManager[] managers = VaultKeystoreProvider.trustManagers(provider, BASE_PATH, PASSWORD.toCharArray());

        assertThat(managers).isNotEmpty();
        assertThat(managers[0]).isInstanceOf(X509TrustManager.class);
        X509TrustManager tm = (X509TrustManager) managers[0];
        assertThat(tm.getAcceptedIssuers()).hasSize(1);
    }

    @Test
    @DisplayName("trustManagers wraps a provider failure as FederatorSslException")
    void trustManagers_providerThrows_wrapsFederatorSslException() {
        SecretProvider provider = mock(SecretProvider.class);
        when(provider.getSecret(BASE_PATH + "/ca-chain", "chain")).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> VaultKeystoreProvider.trustManagers(provider, BASE_PATH, PASSWORD.toCharArray()))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to build TrustManagers")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    @Test
    @DisplayName("VaultKeystoreProvider cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = VaultKeystoreProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
