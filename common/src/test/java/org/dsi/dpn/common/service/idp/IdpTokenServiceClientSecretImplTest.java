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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Properties;
import org.dsi.dpn.common.exception.FederatorTokenException;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.RedisUtil;
import org.dsi.dpn.common.utils.ResilienceSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link IdpTokenServiceClientSecretImpl} - the token-caching implementation
 * this session added Redis caching and prefix support to. Previously entirely untested
 * ({@code org.dsi.dpn.common.service.idp} package coverage: 0%).
 * <p>
 * {@link PropertyUtil} and {@link RedisUtil} are both static-singleton dependencies this class
 * cannot be constructed or exercised without, so both are mocked via
 * {@link org.mockito.Mockito#mockStatic} (Mockito 5's inline mocking, already transitively
 * available via {@code spring-boot-starter-test} - no new test dependency needed). The actual
 * HTTP call to the IDP token endpoint is exercised via a mocked {@link HttpClient}, never a real
 * network call.
 * </p>
 */
@SuppressWarnings("unchecked")
class IdpTokenServiceClientSecretImplTest {

    private static final String TOKEN_URL = "https://idp.example.test/token";

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private MockedStatic<RedisUtil> redisUtilMock;
    private RedisUtil redisUtilInstance;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;

    @BeforeEach
    void setUp() {
        // ResilienceSupport caches its retry/circuit-breaker registries in a static
        // AtomicReference, built once and reused thereafter - reset before each test so one
        // test's resolved config can never leak into another via that cache.
        ResilienceSupport.clearForTests();

        Properties properties = new Properties();
        properties.setProperty("idp.token.url", TOKEN_URL);
        properties.setProperty("idp.client.id", "test-client-id");
        properties.setProperty("idp.client.secret", "test-client-secret");

        // Any unstubbed PropertyUtil.getProperty*Value(key, defaultValue) call - used
        // internally by ResilienceSupport for retry/circuit-breaker config, for keys this
        // test never configures - returns the CALLER's own defaultValue argument, parsed to
        // the correct type. This is exactly what the real method does when a property is
        // genuinely absent, without needing PropertyUtil's own singleton (which requires
        // explicit initialisation this test never performs) to be touched at all.
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
        propertyUtilMock.when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);
        // Forces exactly one attempt, no real retries - without this, every failure-path test
        // below genuinely retries with real exponential backoff (5s, 10s, 20s, 40s... up to a
        // 15-minute cap), since RuntimeException matches the default retryOn class and every
        // mocked failure in this test IS a RuntimeException (or a subclass).
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(1);

        redisUtilInstance = mock(RedisUtil.class);
        redisUtilMock = mockStatic(RedisUtil.class);
        redisUtilMock.when(RedisUtil::getInstance).thenReturn(redisUtilInstance);

        httpClient = mock(HttpClient.class);
        httpResponse = mock(HttpResponse.class);
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
        redisUtilMock.close();
        ResilienceSupport.clearForTests();
    }

    private IdpTokenServiceClientSecretImpl newService() {
        return new IdpTokenServiceClientSecretImpl(() -> httpClient, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // Constructor / configuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Constructor reads idp.token.url / idp.client.id / idp.client.secret from common-configuration.properties")
    void constructor_readsPropertiesWithIdpPrefix() {
        // Constructing must not throw when idp.token.url is a valid https:// URL.
        IdpTokenServiceClientSecretImpl service = newService();

        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("Constructor rejects a plaintext http:// token URL (CWE-319 guard)")
    void constructor_httpTokenUrl_throwsIllegalStateException() {
        Properties properties = new Properties();
        properties.setProperty("idp.token.url", "http://idp.example.test/token");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);

        assertThatThrownBy(this::newService)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use https://");
    }

    @Test
    @DisplayName("Constructor with the prefix-aware overload reads redis.idp. properties instead of idp.")
    void constructor_customPrefix_readsCorrespondingProperties() {
        Properties properties = new Properties();
        properties.setProperty("redis.idp.token.url", TOKEN_URL);
        properties.setProperty("redis.idp.client.id", "redis-gate-client");
        properties.setProperty("redis.idp.client.secret", "redis-gate-secret");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);

        IdpTokenServiceClientSecretImpl service =
                new IdpTokenServiceClientSecretImpl(() -> httpClient, new ObjectMapper(), "redis.idp.");

        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("Constructor falls back to a JVM system property when the file has no client.secret "
            + "(the JAVA_TOOL_OPTIONS -D flag delivery path)")
    void constructor_clientSecretFallsBackToSystemProperty() {
        Properties properties = new Properties();
        properties.setProperty("idp.token.url", TOKEN_URL);
        properties.setProperty("idp.client.id", "test-client-id");
        // Deliberately no idp.client.secret in the file - matches the real deployed ConfigMap.
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);

        System.setProperty("idp.client.secret", "from-system-property");
        try {
            IdpTokenServiceClientSecretImpl service = newService();
            assertThat(service).isNotNull();
        } finally {
            System.clearProperty("idp.client.secret");
        }
    }

    // ------------------------------------------------------------------
    // fetchToken() - cache hit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() returns the cached value from Redis without making any HTTP call")
    void fetchToken_cacheHit_returnsCachedValueWithoutHttpCall() throws Exception {
        when(redisUtilInstance.getValue(eq("client_secret_idp.access_token"), eq(String.class), anyBoolean()))
                .thenReturn("cached-token-value");

        IdpTokenServiceClientSecretImpl service = newService();
        String token = service.fetchToken();

        assertThat(token).isEqualTo("cached-token-value");
        verify(httpClient, never()).send(any(), any());
        verify(redisUtilInstance, never()).setValue(any(), any(), any(Long.class));
    }

    // ------------------------------------------------------------------
    // fetchToken() - cache miss, successful HTTP fetch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() fetches from the IDP and caches the result when Redis has no cached value")
    void fetchToken_cacheMiss_fetchesFromIdpAndCaches() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"fresh-token\",\"expires_in\":300}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(httpResponse);

        IdpTokenServiceClientSecretImpl service = newService();
        String token = service.fetchToken();

        assertThat(token).isEqualTo("fresh-token");
        // expires_in (300) minus the 30s safety buffer - see TOKEN_EXPIRY_SAFETY_BUFFER_SECONDS's
        // own comment for why we never cache for the full raw expires_in.
        verify(redisUtilInstance).setValue(eq("client_secret_idp.access_token"), eq("fresh-token"), eq(270L));
    }

    @Test
    @DisplayName("fetchToken() does not cache the token when the IDP response has no numeric expires_in")
    void fetchToken_noExpiresIn_doesNotCache() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"fresh-token\"}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(httpResponse);

        IdpTokenServiceClientSecretImpl service = newService();
        String token = service.fetchToken();

        assertThat(token).isEqualTo("fresh-token");
        verify(redisUtilInstance, never()).setValue(any(), any(), any(Long.class));
    }

    @Test
    @DisplayName("fetchToken() does not cache the token when expires_in is too short to survive the safety buffer")
    void fetchToken_expiresInTooShortForBuffer_doesNotCache() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(200);
        // 30s expires_in minus the 30s safety buffer would be <= 0 - must not cache in that case.
        when(httpResponse.body()).thenReturn("{\"access_token\":\"fresh-token\",\"expires_in\":30}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(httpResponse);

        IdpTokenServiceClientSecretImpl service = newService();
        String token = service.fetchToken();

        assertThat(token).isEqualTo("fresh-token");
        verify(redisUtilInstance, never()).setValue(any(), any(), any(Long.class));
    }

    @Test
    @DisplayName("fetchToken(managementNodeId) delegates to the same internal fetch as fetchToken()")
    void fetchTokenWithManagementNodeId_delegatesToSameLogic() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn("cached-token");

        IdpTokenServiceClientSecretImpl service = newService();
        String token = service.fetchToken("some-management-node-id");

        assertThat(token).isEqualTo("cached-token");
    }

    // ------------------------------------------------------------------
    // fetchToken() - failure paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() wraps a non-200 IDP response in FederatorTokenException")
    void fetchToken_non200Response_throwsFederatorTokenException() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{\"error\":\"invalid_client\"}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(httpResponse);

        IdpTokenServiceClientSecretImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token from IDP");
    }

    @Test
    @DisplayName("fetchToken() wraps an HTTP transport failure in FederatorTokenException")
    void fetchToken_httpClientThrows_wrapsInFederatorTokenException() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new java.io.IOException("connection refused"));

        IdpTokenServiceClientSecretImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token from IDP");
    }

    @Test
    @DisplayName("fetchToken() re-interrupts the thread and wraps InterruptedException in FederatorTokenException")
    void fetchToken_interrupted_reInterruptsThreadAndWraps() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new InterruptedException("interrupted during send"));

        IdpTokenServiceClientSecretImpl service = newService();

        try {
            assertThatThrownBy(service::fetchToken)
                    .isInstanceOf(FederatorTokenException.class)
                    .hasStackTraceContaining("Thread interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear the interrupt flag so it cannot leak into a later test in the same JVM.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("fetchToken() wraps a Redis cache-read failure in FederatorTokenException")
    void fetchToken_redisReadFails_wrapsInFederatorTokenException() {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        IdpTokenServiceClientSecretImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token from IDP");
    }

    // ------------------------------------------------------------------
    // Cache-key qualification by prefix (documented in the class Javadoc)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The Redis cache key is qualified by the configured prefix, so 'idp.' and 'redis.idp.' "
            + "clients never collide on the same cached token")
    void cacheKey_qualifiedByPrefix_idpAndRedisIdpDoNotCollide() throws Exception {
        Properties redisIdpProperties = new Properties();
        redisIdpProperties.setProperty("redis.idp.token.url", TOKEN_URL);
        redisIdpProperties.setProperty("redis.idp.client.id", "redis-gate-client");
        redisIdpProperties.setProperty("redis.idp.client.secret", "redis-gate-secret");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(redisIdpProperties);
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"redis-idp-token\",\"expires_in\":60}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(httpResponse);

        IdpTokenServiceClientSecretImpl redisIdpService =
                new IdpTokenServiceClientSecretImpl(() -> httpClient, new ObjectMapper(), "redis.idp.");
        redisIdpService.fetchToken();

        verify(redisUtilInstance)
                .getValue(eq("client_secret_redis.idp.access_token"), eq(String.class), anyBoolean());
        // expires_in (60) minus the 30s safety buffer - see TOKEN_EXPIRY_SAFETY_BUFFER_SECONDS's
        // own comment for why we never cache for the full raw expires_in.
        verify(redisUtilInstance)
                .setValue(eq("client_secret_redis.idp.access_token"), eq("redis-idp-token"), eq(30L));
        // Confirms the "idp." prefix's own key is never touched by the "redis.idp." client.
        verify(redisUtilInstance, times(0))
                .getValue(eq("client_secret_idp.access_token"), eq(String.class), anyBoolean());
    }
}
