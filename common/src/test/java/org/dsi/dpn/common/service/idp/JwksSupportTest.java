// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.idp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link JwksSupport}, which builds the single-key JWKS document the gateway
 * publishes for {@code private_key_jwt} verification.
 * <p>
 * Uses a genuinely real RSA certificate generated via {@code keytool} (matching the house style
 * already established in {@code SSLUtilsTest}) rather than mocking the certificate/crypto layer -
 * this exercises the real {@code RSAKey.parse(X509Certificate)} / {@code kid} derivation path.
 * </p>
 */
class JwksSupportTest {

    private static final String PASSWORD = "changeit";

    private X509Certificate generateCertificate(Path dir) throws Exception {
        Path file = dir.resolve("jwks-support-test.p12");
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "jwks-test",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=jwks-support-test",
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
        try (var in = java.nio.file.Files.newInputStream(file)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        return (X509Certificate) keyStore.getCertificate("jwks-test");
    }

    @Test
    @DisplayName("buildJwksDocument produces a single-key JWKS whose kid matches the certificate's SHA-256 thumbprint")
    void buildJwksDocument_realCertificate_producesMatchingJwks(@TempDir Path tempDir) throws Exception {
        X509Certificate cert = generateCertificate(tempDir);
        String expectedKid = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(cert);

        Map<String, Object> jwksDocument = JwksSupport.buildJwksDocument(cert);

        String json = new ObjectMapper().writeValueAsString(jwksDocument);
        JWKSet parsed = JWKSet.parse(json);

        assertThat(parsed.getKeys()).hasSize(1);
        RSAKey jwk = (RSAKey) parsed.getKeys().get(0);
        assertThat(jwk.getKeyID()).isEqualTo(expectedKid);
        assertThat(jwk.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(jwk.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwk.toRSAPublicKey().getModulus())
                .isEqualTo(((java.security.interfaces.RSAPublicKey) cert.getPublicKey()).getModulus());
    }

    @Test
    @DisplayName("JwksSupport cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = JwksSupport.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }
}
