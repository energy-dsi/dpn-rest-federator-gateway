// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.idp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.function.Supplier;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.ResilienceSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link AbstractIdpTokenService} - the shared base class behind all three IDP
 * token service implementations. Exercises {@code requireHttps} and the {@code verifyToken}/
 * {@code verifyTokenInternal} JWKS-based signature verification, previously entirely untested.
 * <p>
 * Uses genuinely real RSA key material and JWT signing/parsing (via nimbus-jose-jwt's
 * {@link RSAKeyGenerator}), matching this project's house style of exercising real crypto rather
 * than mocking it (see {@code SSLUtilsTest}). The HTTP call that fetches the JWKS document is
 * mocked at the {@link HttpClient} boundary, never a real network call. {@link PropertyUtil} is
 * mocked via {@link org.mockito.Mockito#mockStatic} exactly as in
 * {@code IdpTokenServiceClientSecretImplTest}, since {@code verifyToken} routes through
 * {@link ResilienceSupport}, which is itself backed by {@link PropertyUtil}'s static singleton.
 * </p>
 */
@SuppressWarnings("unchecked")
class AbstractIdpTokenServiceTest {

    private static final String JWKS_URL = "https://idp.example.test/certs";

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;

    /** Minimal concrete subclass so the abstract base can be constructed and exercised. */
    private static class TestableIdpTokenService extends AbstractIdpTokenService {
        TestableIdpTokenService(String idpJwksUrl, Supplier<HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
            super(idpJwksUrl, httpClientSupplier, objectMapper);
        }

        @Override
        public String fetchToken() {
            return "unused";
        }

        @Override
        public String fetchToken(String managementNodeId) {
            return "unused";
        }
    }

    @BeforeEach
    void setUp() {
        ResilienceSupport.clearForTests();

        Answer<Object> defaultToSecondArgument = invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length == 2 && args[1] instanceof String defaultValue) {
                return switch (invocation.getMethod().getName()) {
                    case "getPropertyIntValue" -> Integer.parseInt(defaultValue);
                    case "getPropertyDurationValue" -> java.time.Duration.parse(defaultValue);
                    default -> defaultValue;
                };
            }
            return null;
        };
        propertyUtilMock = mockStatic(PropertyUtil.class, defaultToSecondArgument);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(1);

        httpClient = mock(HttpClient.class);
        httpResponse = mock(HttpResponse.class);
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
        ResilienceSupport.clearForTests();
    }

    private TestableIdpTokenService newService() {
        return new TestableIdpTokenService(JWKS_URL, () -> httpClient, new ObjectMapper());
    }

    private void stubJwksResponse(int statusCode, String body) throws Exception {
        when(httpResponse.statusCode()).thenReturn(statusCode);
        when(httpResponse.body()).thenReturn(body);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);
    }

    private static RSAKey generateSigningKey(String kid) throws Exception {
        return new RSAKeyGenerator(2048)
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
    }

    private static String signedJwt(RSAKey signingKey, String kid, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-subject")
                .expirationTime(Date.from(expiry))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
        return jwt.serialize();
    }

    // ------------------------------------------------------------------
    // requireHttps
    // ------------------------------------------------------------------

    @Test
    @DisplayName("requireHttps allows an https:// URL")
    void requireHttps_httpsUrl_doesNotThrow() {
        AbstractIdpTokenService.requireHttps("some.property", "https://idp.example.test/token");
    }

    @Test
    @DisplayName("requireHttps allows an HTTPS:// URL regardless of case")
    void requireHttps_mixedCaseHttpsUrl_doesNotThrow() {
        AbstractIdpTokenService.requireHttps("some.property", "HTTPS://idp.example.test/token");
    }

    @Test
    @DisplayName("requireHttps allows a null URL (nothing configured yet)")
    void requireHttps_nullUrl_doesNotThrow() {
        AbstractIdpTokenService.requireHttps("some.property", null);
    }

    @Test
    @DisplayName("requireHttps allows a blank URL")
    void requireHttps_blankUrl_doesNotThrow() {
        AbstractIdpTokenService.requireHttps("some.property", "   ");
    }

    @Test
    @DisplayName("requireHttps rejects a plaintext http:// URL")
    void requireHttps_httpUrl_throwsIllegalStateException() {
        assertThatThrownBy(() -> AbstractIdpTokenService.requireHttps("idp.token.url", "http://idp.example.test/token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idp.token.url")
                .hasMessageContaining("must use https://");
    }

    // ------------------------------------------------------------------
    // verifyToken - success
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verifyToken returns true for a validly signed, non-expired token whose kid is in the JWKS")
    void verifyToken_validSignatureAndClaims_returnsTrue() throws Exception {
        RSAKey signingKey = generateSigningKey("kid-1");
        JWKSet publicJwkSet = new JWKSet(signingKey.toPublicJWK());
        stubJwksResponse(200, publicJwkSet.toString());

        String token = signedJwt(signingKey, "kid-1", Instant.now().plusSeconds(300));

        boolean result = newService().verifyToken(token);

        assertThat(result).isTrue();
    }

    // ------------------------------------------------------------------
    // verifyToken - failure paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("verifyToken returns false when the signature does not match the JWKS key for that kid")
    void verifyToken_wrongSigningKey_returnsFalse() throws Exception {
        RSAKey publishedKey = generateSigningKey("kid-1");
        RSAKey actualSigningKey = generateSigningKey("kid-1"); // different key material, same kid
        JWKSet publicJwkSet = new JWKSet(publishedKey.toPublicJWK());
        stubJwksResponse(200, publicJwkSet.toString());

        String token = signedJwt(actualSigningKey, "kid-1", Instant.now().plusSeconds(300));

        assertThat(newService().verifyToken(token)).isFalse();
    }

    @Test
    @DisplayName("verifyToken returns false for an expired token")
    void verifyToken_expiredToken_returnsFalse() throws Exception {
        RSAKey signingKey = generateSigningKey("kid-1");
        JWKSet publicJwkSet = new JWKSet(signingKey.toPublicJWK());
        stubJwksResponse(200, publicJwkSet.toString());

        String token = signedJwt(signingKey, "kid-1", Instant.now().minusSeconds(60));

        assertThat(newService().verifyToken(token)).isFalse();
    }

    @Test
    @DisplayName("verifyToken returns false when no JWKS key matches the token's kid")
    void verifyToken_noMatchingKid_returnsFalse() throws Exception {
        RSAKey signingKey = generateSigningKey("kid-published");
        JWKSet publicJwkSet = new JWKSet(signingKey.toPublicJWK());
        stubJwksResponse(200, publicJwkSet.toString());

        String token = signedJwt(signingKey, "kid-unknown", Instant.now().plusSeconds(300));

        assertThat(newService().verifyToken(token)).isFalse();
    }

    @Test
    @DisplayName("verifyToken returns false for a malformed (non-JWT) token string")
    void verifyToken_malformedToken_returnsFalse() {
        assertThat(newService().verifyToken("this-is-not-a-jwt")).isFalse();
    }

    @Test
    @DisplayName("verifyToken returns false when the JWKS endpoint returns a non-200 response")
    void verifyToken_jwksFetchNon200_returnsFalse() throws Exception {
        stubJwksResponse(500, "{\"error\":\"server error\"}");

        RSAKey signingKey = generateSigningKey("kid-1");
        String token = signedJwt(signingKey, "kid-1", Instant.now().plusSeconds(300));

        assertThat(newService().verifyToken(token)).isFalse();
    }

    @Test
    @DisplayName("verifyToken returns false when the HTTP client throws while fetching the JWKS")
    void verifyToken_httpClientThrows_returnsFalse() throws Exception {
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("connection refused"));

        RSAKey signingKey = generateSigningKey("kid-1");
        String token = signedJwt(signingKey, "kid-1", Instant.now().plusSeconds(300));

        assertThat(newService().verifyToken(token)).isFalse();
    }
}
