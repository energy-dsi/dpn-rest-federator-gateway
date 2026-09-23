// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SSLUtils}, focused specifically on this session's new JKS/PKCS12
 * auto-detection logic (see {@code loadTrustStore}, reached via
 * {@link SSLUtils#createSSLContextWithTrustStore} and both
 * {@link SSLUtils#createTrustManager(String, String)} overloads), plus the pre-existing
 * extraction/error-handling behaviour these new code paths sit alongside.
 * <p>
 * Uses {@code keytool} (via {@link ProcessBuilder}, always present in the JDK this project
 * already requires) to generate genuinely real JKS and PKCS12 keystore files in a JUnit
 * {@code @TempDir} - no mocking, no new test dependencies, matching the real-world scenario this
 * fix addresses directly: modern {@code keytool} produces PKCS12-format content by default even
 * when the destination file is named with a {@code .jks} extension.
 * </p>
 */
class SSLUtilsTest {

    private static final String PASSWORD = "changeit";

    /**
     * Runs {@code keytool -genkeypair}, producing a keystore containing one self-signed
     * certificate, in the requested storetype, at the requested path.
     */
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
                        "CN=ssl-utils-test",
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
    // Auto-detection: genuinely JKS-format file, loaded via the JKS-first attempt
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createSSLContextWithTrustStore loads a genuinely JKS-format file correctly")
    void createSSLContextWithTrustStore_genuineJks_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path jksFile = generateKeystore(tempDir, "truststore.jks", "JKS");

        var sslContext = SSLUtils.createSSLContextWithTrustStore(jksFile.toString(), PASSWORD);

        assertThat(sslContext).isNotNull();
        assertThat(sslContext.getProtocol()).isEqualTo("TLS");
    }

    // ------------------------------------------------------------------
    // Auto-detection: the actual real-world scenario this fix exists for - a genuinely
    // PKCS12-format file, named/extension ".jks" (exactly what modern keytool produces by
    // default), which the pre-fix hardcoded-JKS-only load would have rejected outright.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "createSSLContextWithTrustStore loads a PKCS12-format file even when named '.jks' "
                    + "(the real-world scenario this fix addresses)")
    void createSSLContextWithTrustStore_pkcs12NamedJks_autoDetectsAndLoads(@TempDir Path tempDir) throws Exception {
        Path misnamedFile = generateKeystore(tempDir, "truststore.jks", "PKCS12");

        var sslContext = SSLUtils.createSSLContextWithTrustStore(misnamedFile.toString(), PASSWORD);

        assertThat(sslContext).isNotNull();
    }

    @Test
    @DisplayName("createSSLContextWithTrustStore loads a genuinely PKCS12-format ('.p12') file")
    void createSSLContextWithTrustStore_genuinePkcs12_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "truststore.p12", "PKCS12");

        var sslContext = SSLUtils.createSSLContextWithTrustStore(p12File.toString(), PASSWORD);

        assertThat(sslContext).isNotNull();
    }

    @Test
    @DisplayName("createTrustManager(String, String) also auto-detects PKCS12 content under a '.jks' name")
    void createTrustManager_pkcs12NamedJks_autoDetectsAndLoads(@TempDir Path tempDir) throws Exception {
        Path misnamedFile = generateKeystore(tempDir, "otel-truststore.jks", "PKCS12");

        TrustManager[] trustManagers = SSLUtils.createTrustManager(misnamedFile.toString(), PASSWORD);

        assertThat(trustManagers).isNotEmpty();
        assertThat(SSLUtils.extractX509TrustManager(trustManagers)).isNotNull();
    }

    @Test
    @DisplayName("createTrustManager(InputStream, String) also auto-detects PKCS12 content")
    void createTrustManagerFromInputStream_pkcs12_autoDetectsAndLoads(@TempDir Path tempDir) throws Exception {
        Path p12File = generateKeystore(tempDir, "collector-truststore.jks", "PKCS12");

        TrustManager[] trustManagers;
        try (InputStream inputStream = new ByteArrayInputStream(Files.readAllBytes(p12File))) {
            trustManagers = SSLUtils.createTrustManager(inputStream, PASSWORD);
        }

        assertThat(trustManagers).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Failure cases - both formats genuinely fail (wrong password, corrupt content)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createSSLContextWithTrustStore fails with the correct exception type when the password is wrong")
    void createSSLContextWithTrustStore_wrongPassword_throwsFederatorSslException(@TempDir Path tempDir)
            throws Exception {
        Path jksFile = generateKeystore(tempDir, "truststore.jks", "JKS");

        assertThatThrownBy(() -> SSLUtils.createSSLContextWithTrustStore(jksFile.toString(), "totally-wrong"))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("Failed to create SSLContext");
    }

    @Test
    @DisplayName("createSSLContextWithTrustStore fails clearly when the file does not exist")
    void createSSLContextWithTrustStore_missingFile_throwsFederatorSslException(@TempDir Path tempDir) {
        Path missingFile = tempDir.resolve("does-not-exist.jks");

        assertThatThrownBy(() -> SSLUtils.createSSLContextWithTrustStore(missingFile.toString(), PASSWORD))
                .isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("createSSLContextWithTrustStore fails clearly when the file is neither valid JKS nor PKCS12")
    void createSSLContextWithTrustStore_corruptFile_throwsFederatorSslException(@TempDir Path tempDir)
            throws Exception {
        Path corruptFile = tempDir.resolve("corrupt.jks");
        Files.write(corruptFile, "this is not a keystore".getBytes());

        assertThatThrownBy(() -> SSLUtils.createSSLContextWithTrustStore(corruptFile.toString(), PASSWORD))
                .isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("createTrustManager(InputStream, String) rejects a null input stream")
    void createTrustManagerFromInputStream_nullStream_throwsFederatorSslException() {
        assertThatThrownBy(() -> SSLUtils.createTrustManager((InputStream) null, PASSWORD))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("not set");
    }

    @Test
    @DisplayName("createTrustManager(InputStream, String) rejects a null password")
    void createTrustManagerFromInputStream_nullPassword_throwsFederatorSslException(@TempDir Path tempDir)
            throws Exception {
        Path jksFile = generateKeystore(tempDir, "truststore.jks", "JKS");
        try (InputStream inputStream = Files.newInputStream(jksFile)) {
            assertThatThrownBy(() -> SSLUtils.createTrustManager(inputStream, null))
                    .isInstanceOf(FederatorSslException.class)
                    .hasMessageContaining("not set");
        }
    }

    // ------------------------------------------------------------------
    // extractX509TrustManager / extractX509KeyManager - pre-existing behaviour, still uncovered
    // ------------------------------------------------------------------

    @Test
    @DisplayName("extractX509TrustManager returns the X509TrustManager from a real trust manager array")
    void extractX509TrustManager_realArray_returnsManager(@TempDir Path tempDir) throws Exception {
        Path jksFile = generateKeystore(tempDir, "truststore.jks", "JKS");
        TrustManager[] trustManagers = SSLUtils.createTrustManager(jksFile.toString(), PASSWORD);

        X509TrustManager x509 = SSLUtils.extractX509TrustManager(trustManagers);

        assertThat(x509).isNotNull();
        assertThat(x509.getAcceptedIssuers()).isNotEmpty();
    }

    @Test
    @DisplayName("extractX509TrustManager throws FederatorSslException when given an empty array")
    void extractX509TrustManager_emptyArray_throws() {
        assertThatThrownBy(() -> SSLUtils.extractX509TrustManager(new TrustManager[0]))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("No X509TrustManager");
    }

    @Test
    @DisplayName("extractX509TrustManager throws FederatorSslException when given null")
    void extractX509TrustManager_null_throws() {
        assertThatThrownBy(() -> SSLUtils.extractX509TrustManager(null)).isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("extractX509KeyManager throws FederatorSslException when given an empty array")
    void extractX509KeyManager_emptyArray_throws() {
        assertThatThrownBy(() -> SSLUtils.extractX509KeyManager(new KeyManager[0]))
                .isInstanceOf(FederatorSslException.class)
                .hasMessageContaining("No X509KeyManager");
    }

    @Test
    @DisplayName("extractX509KeyManager throws FederatorSslException when given null")
    void extractX509KeyManager_null_throws() {
        assertThatThrownBy(() -> SSLUtils.extractX509KeyManager(null)).isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("SSLUtils cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = SSLUtils.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
