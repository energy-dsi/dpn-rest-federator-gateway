// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Additional unit tests for {@link SSLUtils}, covering the methods {@link SSLUtilsTest} does not
 * exercise: {@code createKeyManagerFromP12} (both overloads), {@code createKeyManagerFromPem}
 * (which internally exercises {@code parsePkcs8PrivateKey}), and
 * {@link SSLUtils#createSSLContext(String, String, String, String)} (the keystore+truststore
 * overload, as opposed to {@code createSSLContextWithTrustStore}, which {@code SSLUtilsTest}
 * already covers). Uses real {@code keytool}-generated material in {@code @TempDir}, matching the
 * house style of {@code SSLUtilsTest}.
 */
class SSLUtilsAdditionalTest {

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
                        "CN=ssl-utils-additional-test",
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

    // ------------------------------------------------------------------
    // createKeyManagerFromP12(String, String) / (InputStream, String)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createKeyManagerFromP12(String, String) loads a real PKCS12 keystore file")
    void createKeyManagerFromP12_pathOverload_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "client.p12", "PKCS12");

        KeyManager[] keyManagers = SSLUtils.createKeyManagerFromP12(p12File.toString(), PASSWORD);

        assertThat(keyManagers).isNotEmpty();
        assertThat(SSLUtils.extractX509KeyManager(keyManagers)).isNotNull();
    }

    @Test
    @DisplayName("createKeyManagerFromP12(String, String) throws FederatorSslException when the file does not exist")
    void createKeyManagerFromP12_pathOverload_missingFile_throws(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.p12");

        assertThatThrownBy(() -> SSLUtils.createKeyManagerFromP12(missing.toString(), PASSWORD))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("createKeyManagerFromP12(InputStream, String) loads a real PKCS12 keystore stream")
    void createKeyManagerFromP12_streamOverload_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "client.p12", "PKCS12");

        KeyManager[] keyManagers;
        try (InputStream in = new FileInputStream(p12File.toFile())) {
            keyManagers = SSLUtils.createKeyManagerFromP12(in, PASSWORD);
        }

        assertThat(keyManagers).isNotEmpty();
    }

    @Test
    @DisplayName("createKeyManagerFromP12(InputStream, String) rejects a null input stream")
    void createKeyManagerFromP12_streamOverload_nullStream_throws() {
        assertThatThrownBy(() -> SSLUtils.createKeyManagerFromP12((InputStream) null, PASSWORD))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("not set");
    }

    @Test
    @DisplayName("createKeyManagerFromP12(InputStream, String) rejects a null password")
    void createKeyManagerFromP12_streamOverload_nullPassword_throws(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "client.p12", "PKCS12");
        try (InputStream in = new FileInputStream(p12File.toFile())) {
            assertThatThrownBy(() -> SSLUtils.createKeyManagerFromP12(in, null))
                    .isInstanceOf(FederatorSslException.class)
                    .hasMessageContaining("not set");
        }
    }

    @Test
    @DisplayName("createKeyManagerFromP12(InputStream, String) throws FederatorSslException when the password is wrong")
    void createKeyManagerFromP12_streamOverload_wrongPassword_throws(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "client.p12", "PKCS12");
        try (InputStream in = new FileInputStream(p12File.toFile())) {
            assertThatThrownBy(() -> SSLUtils.createKeyManagerFromP12(in, "totally-wrong"))
                    .isInstanceOf(FederatorSslException.class)
                    .hasMessageContaining("Failed to load client P12 keystore");
        }
    }

    // ------------------------------------------------------------------
    // createKeyManagerFromPem(String, String) - also exercises parsePkcs8PrivateKey
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createKeyManagerFromPem loads a real self-signed cert + unencrypted PKCS8 key")
    void createKeyManagerFromPem_realCertAndKey_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        // Generate a real self-signed cert + PKCS12 keystore via keytool, then export both the
        // certificate (PEM) and the private key (PEM, PKCS8, unencrypted) from it - giving
        // createKeyManagerFromPem genuinely real, matching cert/key material.
        Path p12File = generateKeystore(tempDir, "for-pem-export.p12", "PKCS12");

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(p12File.toFile())) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        Certificate cert = keyStore.getCertificate("test-entry");
        PrivateKey privateKey = (PrivateKey) keyStore.getKey("test-entry", PASSWORD.toCharArray());

        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + wrap(Base64.getEncoder().encodeToString(cert.getEncoded()))
                + "\n-----END CERTIFICATE-----\n";
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
                + wrap(Base64.getEncoder().encodeToString(privateKey.getEncoded()))
                + "\n-----END PRIVATE KEY-----\n";

        Path certFile = tempDir.resolve("cert.pem");
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(certFile, certPem, StandardCharsets.UTF_8);
        Files.writeString(keyFile, keyPem, StandardCharsets.UTF_8);

        KeyManager[] keyManagers = SSLUtils.createKeyManagerFromPem(certFile.toString(), keyFile.toString());

        assertThat(keyManagers).isNotEmpty();
        X509KeyManager x509 = SSLUtils.extractX509KeyManager(keyManagers);
        assertThat(x509.getCertificateChain("dashboard")).isNotEmpty();
    }

    @Test
    @DisplayName("createKeyManagerFromPem throws FederatorSslException when the cert file does not exist")
    void createKeyManagerFromPem_missingCertFile_throws(@TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(keyFile, "not-really-a-key");

        assertThatThrownBy(() ->
                        SSLUtils.createKeyManagerFromPem(tempDir.resolve("missing-cert.pem").toString(), keyFile.toString()))
                .isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("createKeyManagerFromPem throws FederatorSslException when the certificate PEM contains no certificates")
    void createKeyManagerFromPem_emptyCertificatePem_throws(@TempDir Path tempDir) throws Exception {
        Path certFile = tempDir.resolve("empty-cert.pem");
        Path keyFile = tempDir.resolve("key.pem");
        Files.writeString(certFile, "");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> SSLUtils.createKeyManagerFromPem(certFile.toString(), keyFile.toString()))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("No certificates found");
    }

    @Test
    @DisplayName("createKeyManagerFromPem propagates IllegalArgumentException when the private key PEM contains "
            + "characters that are not valid Base64 - parsePkcs8PrivateKey's Base64 decode step is only reached "
            + "after cert parsing, and createKeyManagerFromPem's catch clause only covers IOException/"
            + "GeneralSecurityException, not IllegalArgumentException, so this propagates un-wrapped rather than "
            + "as a FederatorSslException")
    void createKeyManagerFromPem_malformedKey_throws(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "for-pem-export2.p12", "PKCS12");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(p12File.toFile())) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        Certificate cert = keyStore.getCertificate("test-entry");
        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + wrap(Base64.getEncoder().encodeToString(cert.getEncoded()))
                + "\n-----END CERTIFICATE-----\n";

        Path certFile = tempDir.resolve("cert.pem");
        Path keyFile = tempDir.resolve("bad-key.pem");
        Files.writeString(certFile, certPem);
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\nnot-valid-base64-key-data!!!\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> SSLUtils.createKeyManagerFromPem(certFile.toString(), keyFile.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Illegal base64 character");
    }

    private static String wrap(String base64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // createSSLContext(String, String, String, String) - keystore + truststore overload
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createSSLContext builds a working SSLContext from a genuine PKCS12 keystore and JKS truststore")
    void createSSLContext_realKeystoreAndTruststore_buildsSslContext(@TempDir Path tempDir) throws Exception {
        Path keystoreFile = generateKeystore(tempDir, "keystore.p12", "PKCS12");
        Path truststoreFile = generateKeystore(tempDir, "truststore.jks", "JKS");

        SSLContext sslContext =
                SSLUtils.createSSLContext(keystoreFile.toString(), PASSWORD, truststoreFile.toString(), PASSWORD);

        assertThat(sslContext).isNotNull();
        assertThat(sslContext.getProtocol()).isEqualTo("TLS");
    }

    @Test
    @DisplayName("createSSLContext throws FederatorSslException when the keystore file does not exist")
    void createSSLContext_missingKeystore_throws(@TempDir Path tempDir) throws Exception {
        Path truststoreFile = generateKeystore(tempDir, "truststore.jks", "JKS");
        Path missingKeystore = tempDir.resolve("missing.p12");

        assertThatThrownBy(() ->
                        SSLUtils.createSSLContext(missingKeystore.toString(), PASSWORD, truststoreFile.toString(), PASSWORD))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to create SSLContext");
    }

    @Test
    @DisplayName("createSSLContext throws FederatorSslException when the keystore password is wrong")
    void createSSLContext_wrongKeystorePassword_throws(@TempDir Path tempDir) throws Exception {
        Path keystoreFile = generateKeystore(tempDir, "keystore.p12", "PKCS12");
        Path truststoreFile = generateKeystore(tempDir, "truststore.jks", "JKS");

        assertThatThrownBy(() -> SSLUtils.createSSLContext(
                        keystoreFile.toString(), "totally-wrong", truststoreFile.toString(), PASSWORD))
                .isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("createSSLContext throws FederatorSslException when the truststore file does not exist")
    void createSSLContext_missingTruststore_throws(@TempDir Path tempDir) throws Exception {
        Path keystoreFile = generateKeystore(tempDir, "keystore.p12", "PKCS12");
        Path missingTruststore = tempDir.resolve("missing.jks");

        assertThatThrownBy(() ->
                        SSLUtils.createSSLContext(keystoreFile.toString(), PASSWORD, missingTruststore.toString(), PASSWORD))
                .isInstanceOf(FederatorSslException.class);
    }
}
