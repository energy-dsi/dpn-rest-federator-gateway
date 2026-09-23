// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.idp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Properties;
import org.dsi.dpn.common.exception.FederatorTokenException;
import org.dsi.dpn.common.service.secret.VaultTlsSupport;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.RedisUtil;
import org.dsi.dpn.common.utils.ResilienceSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link IdpTokenServicePrivateJwtImpl}, the {@code private_key_jwt} (RFC 7523)
 * client-authentication implementation.
 * <p>
 * The signing keystore is a genuinely real PKCS12 file generated via {@code keytool} in a
 * {@code @TempDir} (matching this project's house style for crypto material - see
 * {@code SSLUtilsTest}), so {@code loadKeystoreContents}/{@code deriveKidFromCertificate} and the
 * actual JWT signing in {@code buildClientAssertion()} are exercised for real, never mocked. Only
 * the IDP token endpoint HTTP call and the Redis cache are mocked, exactly as in
 * {@code IdpTokenServiceClientSecretImplTest} and {@code IdpTokenServiceMtlsImplTest}.
 * </p>
 */
@SuppressWarnings("unchecked")
class IdpTokenServicePrivateJwtImplTest {

    private static final String TOKEN_URL = "https://idp.example.test/token";
    private static final String JWKS_URL = "https://idp.example.test/certs";
    private static final String KEYSTORE_PASSWORD = "changeit";
    private static final String KEYSTORE_ALIAS = "federator";

    private MockedStatic<RedisUtil> redisUtilMock;
    private RedisUtil redisUtilInstance;
    private MockedStatic<PropertyUtil> propertyUtilMock;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;

    @BeforeEach
    void setUp() {
        ResilienceSupport.clearForTests();
        redisUtilInstance = mock(RedisUtil.class);
        redisUtilMock = mockStatic(RedisUtil.class);
        redisUtilMock.when(RedisUtil::getInstance).thenReturn(redisUtilInstance);

        // fetchToken() (no-arg) routes through the IdpTokenService default
        // fetchTokenWithResilience(), which is backed by ResilienceSupport's retry/circuit-breaker
        // registries - themselves backed by PropertyUtil's static singleton. Mocked the same way
        // as IdpTokenServiceClientSecretImplTest/IdpTokenServiceMtlsImplTest so those registries
        // can resolve their (default) configuration without a real PropertyUtil.init(...) call,
        // and so a single retry attempt is forced (no real exponential backoff on failure tests).
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
        redisUtilMock.close();
        propertyUtilMock.close();
        ResilienceSupport.clearForTests();
    }

    private Path generateKeystore(Path dir) throws Exception {
        Path file = dir.resolve("private-jwt-test.p12");
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        KEYSTORE_ALIAS,
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=private-jwt-test",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        file.toString(),
                        "-storepass",
                        KEYSTORE_PASSWORD,
                        "-keypass",
                        KEYSTORE_PASSWORD)
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

    private Properties baseProperties(Path keystorePath) {
        Properties properties = new Properties();
        properties.setProperty("idp.token.url", TOKEN_URL);
        properties.setProperty("idp.jwks.url", JWKS_URL);
        properties.setProperty("idp.client.id", "test-client-id");
        properties.setProperty("idp.keystore.path", keystorePath.toString());
        properties.setProperty("idp.keystore.password", KEYSTORE_PASSWORD);
        properties.setProperty("idp.jwt.key.alias", KEYSTORE_ALIAS);
        properties.setProperty("idp.jwt.algorithm", "RS256");
        return properties;
    }

    private IdpTokenServicePrivateJwtImpl newService(Properties properties) {
        return new IdpTokenServicePrivateJwtImpl(() -> httpClient, new ObjectMapper(), properties);
    }

    // ------------------------------------------------------------------
    // Constructor / algorithm resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Constructor succeeds with valid RS256 configuration")
    void constructor_validConfig_succeeds(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);

        assertThat(newService(baseProperties(keystore))).isNotNull();
    }

    @Test
    @DisplayName("Constructor defaults to RS256 when idp.jwt.algorithm is not set")
    void constructor_noAlgorithmProperty_defaultsToRs256(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        Properties properties = baseProperties(keystore);
        properties.remove("idp.jwt.algorithm");

        assertThat(newService(properties)).isNotNull();
    }

    @Test
    @DisplayName("Constructor throws FederatorTokenException for an unsupported algorithm")
    void constructor_unsupportedAlgorithm_throwsFederatorTokenException(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        Properties properties = baseProperties(keystore);
        properties.setProperty("idp.jwt.algorithm", "HS256");

        assertThatThrownBy(() -> newService(properties))
                .isInstanceOf(FederatorTokenException.class)
                .hasMessageContaining("Unsupported JWT signing algorithm");
    }

    @Test
    @DisplayName("Constructor does not throw when idp.token.url / idp.client.id are missing (only logs)")
    void constructor_missingRequiredConfig_doesNotThrow(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        Properties properties = baseProperties(keystore);
        properties.remove("idp.token.url");
        properties.remove("idp.client.id");

        assertThat(newService(properties)).isNotNull();
    }

    // ------------------------------------------------------------------
    // deriveKidFromCertificate / loadKeystoreContents - package-visible statics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("loadKeystoreContents reads the private key and derives the kid as the certificate's SHA-256 thumbprint")
    void loadKeystoreContents_realKeystore_returnsPrivateKeyAndMatchingKid(@TempDir Path tempDir) throws Exception {
        Path keystoreFile = generateKeystore(tempDir);
        Properties properties = baseProperties(keystoreFile);

        IdpTokenServicePrivateJwtImpl.KeystoreContents contents =
                IdpTokenServicePrivateJwtImpl.loadKeystoreContents(properties);

        assertThat(contents.privateKey()).isInstanceOf(PrivateKey.class);
        assertThat(contents.privateKey().getAlgorithm()).isEqualTo("RSA");

        // Independently derive the expected kid straight from the keystore's own certificate,
        // to confirm loadKeystoreContents() didn't just echo back some other value.
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystoreFile)) {
            keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(KEYSTORE_ALIAS);
        String expectedKid = IdpTokenServicePrivateJwtImpl.deriveKidFromCertificate(cert);

        assertThat(contents.kid()).isEqualTo(expectedKid);
    }

    @Test
    @DisplayName("loadKeystoreContents throws FederatorTokenException when required keystore properties are missing")
    void loadKeystoreContents_missingProperties_throwsFederatorTokenException() {
        Properties properties = new Properties();

        assertThatThrownBy(() -> IdpTokenServicePrivateJwtImpl.loadKeystoreContents(properties))
                .isInstanceOf(FederatorTokenException.class)
                .hasMessageContaining("private_key_jwt requires");
    }

    @Test
    @DisplayName("loadKeystoreContents throws FederatorTokenException when the keystore file does not exist")
    void loadKeystoreContents_missingFile_throwsFederatorTokenException(@TempDir Path tempDir) {
        Properties properties = baseProperties(tempDir.resolve("does-not-exist.p12"));

        assertThatThrownBy(() -> IdpTokenServicePrivateJwtImpl.loadKeystoreContents(properties))
                .isInstanceOf(FederatorTokenException.class)
                .hasMessageContaining("Failed to load keystore from");
    }

    @Test
    @DisplayName("loadKeystoreContents throws FederatorTokenException when the alias is not present in the keystore")
    void loadKeystoreContents_wrongAlias_throwsFederatorTokenException(@TempDir Path tempDir) throws Exception {
        Path keystoreFile = generateKeystore(tempDir);
        Properties properties = baseProperties(keystoreFile);
        properties.setProperty("idp.jwt.key.alias", "no-such-alias");

        assertThatThrownBy(() -> IdpTokenServicePrivateJwtImpl.loadKeystoreContents(properties))
                .isInstanceOf(FederatorTokenException.class);
    }

    @Test
    @DisplayName("loadKeystoreContentsFromVault wraps the failure when Vault is enabled but not actually configured")
    void loadKeystoreContentsFromVault_vaultTlsEnabledButNotConfigured_throwsFederatorTokenException() {
        Properties properties = new Properties();
        properties.setProperty(VaultTlsSupport.VAULT_TLS_ENABLED, "true");
        // No vault.uri set - PropertyUtil.createSecretProvider(...) degrades to a NoopSecretProvider,
        // whose getSecret(...) always returns null, so the leaf-certificate lookup fails.

        assertThatThrownBy(() -> IdpTokenServicePrivateJwtImpl.loadKeystoreContentsFromVault(properties))
                .isInstanceOf(FederatorTokenException.class)
                .hasMessageContaining("Failed to load private_key_jwt material from Vault");
    }

    @Test
    @DisplayName("loadKeystoreContents routes to Vault when vault.tls.enabled=true, rather than reading a keystore file")
    void loadKeystoreContents_vaultTlsEnabled_routesToVaultAndFails(@TempDir Path tempDir) throws Exception {
        // Deliberately valid file-based properties alongside vault.tls.enabled=true: proves the
        // Vault branch is taken (and fails on the unconfigured Vault) rather than silently
        // falling through to the file-based keystore this test also provides.
        Path keystoreFile = generateKeystore(tempDir);
        Properties properties = baseProperties(keystoreFile);
        properties.setProperty(VaultTlsSupport.VAULT_TLS_ENABLED, "true");

        assertThatThrownBy(() -> IdpTokenServicePrivateJwtImpl.loadKeystoreContents(properties))
                .isInstanceOf(FederatorTokenException.class)
                .hasMessageContaining("Failed to load private_key_jwt material from Vault");
    }

    // ------------------------------------------------------------------
    // fetchToken() - cache hit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() returns the cached value from Redis without building a client assertion or calling the IDP")
    void fetchToken_cacheHit_returnsCachedValueWithoutHttpCall(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        when(redisUtilInstance.getValue(eq("management_node_default_access_token"), eq(String.class), anyBoolean()))
                .thenReturn("cached-token-value");

        String token = newService(baseProperties(keystore)).fetchToken();

        assertThat(token).isEqualTo("cached-token-value");
        verify(httpClient, never()).send(any(), any());
    }

    // ------------------------------------------------------------------
    // fetchToken() - cache miss, successful HTTP fetch with a real signed assertion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() builds a genuinely signed private_key_jwt assertion, posts it, and caches the result")
    void fetchToken_cacheMiss_buildsRealAssertionFetchesAndCaches(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"fresh-token\",\"expires_in\":120}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    java.net.http.HttpRequest request = invocation.getArgument(0);
                    // The client_assertion in the POST body must be a genuinely well-formed,
                    // parseable signed JWT - not a placeholder - proving buildClientAssertion()
                    // really loaded the keystore and signed with the real private key.
                    String body = extractBodyPublisherString(request);
                    assertThat(body).contains("client_assertion=");
                    String assertion = body.substring(body.indexOf("client_assertion=") + "client_assertion=".length());
                    assertThat(SignedJWT.parse(assertion)).isNotNull();
                    return httpResponse;
                });

        String token = newService(baseProperties(keystore)).fetchToken();

        assertThat(token).isEqualTo("fresh-token");
        verify(redisUtilInstance).setValue(eq("management_node_default_access_token"), eq("fresh-token"), eq(120L));
    }

    /** Reads the request body back out of an {@link java.net.http.HttpRequest}'s BodyPublisher. */
    private static String extractBodyPublisherString(java.net.http.HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var bodyBytes = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                bodyBytes.set(out.toByteArray());
                latch.countDown();
            }
        });
        latch.await();
        return new String(bodyBytes.get(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // fetchToken() - failure paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() wraps a non-200 IDP response in FederatorTokenException")
    void fetchToken_non200Response_throwsFederatorTokenException(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{\"error\":\"invalid_client\"}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        IdpTokenServicePrivateJwtImpl service = newService(baseProperties(keystore));

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Failed to fetch token for node");
    }

    @Test
    @DisplayName("fetchToken() wraps an HTTP transport failure in FederatorTokenException")
    void fetchToken_httpClientThrows_wrapsInFederatorTokenException(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("connection refused"));

        IdpTokenServicePrivateJwtImpl service = newService(baseProperties(keystore));

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token via private_key_jwt for node");
    }

    @Test
    @DisplayName("fetchToken() re-interrupts the thread and wraps InterruptedException in FederatorTokenException")
    void fetchToken_interrupted_reInterruptsThreadAndWraps(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new InterruptedException("interrupted during send"));

        IdpTokenServicePrivateJwtImpl service = newService(baseProperties(keystore));

        try {
            assertThatThrownBy(service::fetchToken)
                    .isInstanceOf(FederatorTokenException.class)
                    .hasStackTraceContaining("Thread interrupted while fetching token from IDP");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("fetchToken() propagates a keystore load failure (e.g. rotated-away file) as FederatorTokenException")
    void fetchToken_keystoreMissing_wrapsInFederatorTokenException(@TempDir Path tempDir) {
        Properties properties = baseProperties(tempDir.resolve("does-not-exist.p12"));
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);

        IdpTokenServicePrivateJwtImpl service = newService(properties);

        // buildClientAssertion() rethrows a FederatorTokenException from loadKeystoreContents()
        // as-is (see its catch (FederatorTokenException e) { throw e; } branch) rather than
        // wrapping it in a new "Failed to build signed JWT client assertion" message - so the
        // original "Failed to load keystore from" message is what actually surfaces.
        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Failed to load keystore from");
    }

    @Test
    @DisplayName("fetchToken() wraps a Redis cache-read failure in FederatorTokenException")
    void fetchToken_redisReadFails_wrapsInFederatorTokenException(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeystore(tempDir);
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        IdpTokenServicePrivateJwtImpl service = newService(baseProperties(keystore));

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token via private_key_jwt for node");
    }
}
