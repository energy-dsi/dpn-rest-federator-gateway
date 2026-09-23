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
 * Unit tests for {@link IdpTokenServiceMtlsImpl}, mirroring the patterns already established in
 * {@code IdpTokenServiceClientSecretImplTest}: {@link PropertyUtil} and {@link RedisUtil} are
 * mocked via {@link org.mockito.Mockito#mockStatic} (both static-singleton dependencies this
 * class cannot be constructed or exercised without), and the HTTP call to the IDP token endpoint
 * is exercised via a mocked {@link HttpClient}, never a real network call.
 */
@SuppressWarnings("unchecked")
class IdpTokenServiceMtlsImplTest {

    private static final String TOKEN_URL = "https://idp.example.test/token";

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private MockedStatic<RedisUtil> redisUtilMock;
    private RedisUtil redisUtilInstance;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;

    @BeforeEach
    void setUp() {
        ResilienceSupport.clearForTests();

        Properties properties = new Properties();
        properties.setProperty("idp.token.url", TOKEN_URL);
        properties.setProperty("idp.client.id", "test-client-id");
        properties.setProperty("idp.client.secret", "test-client-secret");

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
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(properties);
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

    private IdpTokenServiceMtlsImpl newService() {
        return new IdpTokenServiceMtlsImpl(() -> httpClient, new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Constructor reads idp.token.url / idp.client.id / idp.client.secret and succeeds")
    void constructor_validConfig_succeeds() {
        assertThat(newService()).isNotNull();
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
    @DisplayName("Constructor does not throw when required properties are missing (only logs)")
    void constructor_missingProperties_doesNotThrow() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(new Properties());

        assertThat(newService()).isNotNull();
    }

    // ------------------------------------------------------------------
    // fetchToken() - cache hit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken() returns the cached value from Redis without making any HTTP call")
    void fetchToken_cacheHit_returnsCachedValueWithoutHttpCall() throws Exception {
        when(redisUtilInstance.getValue(eq("management_node_default_access_token"), eq(String.class), anyBoolean()))
                .thenReturn("cached-token-value");

        String token = newService().fetchToken();

        assertThat(token).isEqualTo("cached-token-value");
        org.mockito.Mockito.verify(httpClient, never()).send(any(), any());
    }

    // ------------------------------------------------------------------
    // fetchToken(managementNodeId) - cache key qualification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fetchToken(managementNodeId) uses a redis key qualified by the management node id")
    void fetchToken_withManagementNodeId_usesQualifiedRedisKey() throws Exception {
        when(redisUtilInstance.getValue(eq("management_node_node-a_access_token"), eq(String.class), anyBoolean()))
                .thenReturn("cached-for-node-a");

        String token = newService().fetchToken("node-a");

        assertThat(token).isEqualTo("cached-for-node-a");
    }

    @Test
    @DisplayName("fetchToken(managementNodeId) with a blank id falls back to the 'default' redis key")
    void fetchToken_blankManagementNodeId_usesDefaultRedisKey() throws Exception {
        when(redisUtilInstance.getValue(eq("management_node_default_access_token"), eq(String.class), anyBoolean()))
                .thenReturn("cached-default");

        String token = newService().fetchToken("");

        assertThat(token).isEqualTo("cached-default");
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
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        String token = newService().fetchToken();

        assertThat(token).isEqualTo("fresh-token");
        org.mockito.Mockito.verify(redisUtilInstance)
                .setValue(eq("management_node_default_access_token"), eq("fresh-token"), eq(300L));
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
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        IdpTokenServiceMtlsImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Failed to fetch token for management node");
    }

    @Test
    @DisplayName("fetchToken() wraps an HTTP transport failure in FederatorTokenException")
    void fetchToken_httpClientThrows_wrapsInFederatorTokenException() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.io.IOException("connection refused"));

        IdpTokenServiceMtlsImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token from IDP for management node");
    }

    @Test
    @DisplayName("fetchToken() re-interrupts the thread and wraps InterruptedException in FederatorTokenException")
    void fetchToken_interrupted_reInterruptsThreadAndWraps() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new InterruptedException("interrupted during send"));

        IdpTokenServiceMtlsImpl service = newService();

        try {
            assertThatThrownBy(service::fetchToken)
                    .isInstanceOf(FederatorTokenException.class)
                    .hasStackTraceContaining("Thread interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("fetchToken() wraps a response with no numeric expires_in in FederatorTokenException")
    void fetchToken_missingExpiresIn_throwsFederatorTokenException() throws Exception {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean())).thenReturn(null);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"access_token\":\"fresh-token\"}");
        when(httpClient.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);

        IdpTokenServiceMtlsImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token from IDP for management node");
    }

    @Test
    @DisplayName("fetchToken() wraps a Redis cache-read failure in FederatorTokenException")
    void fetchToken_redisReadFails_wrapsInFederatorTokenException() {
        when(redisUtilInstance.getValue(any(), eq(String.class), anyBoolean()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        IdpTokenServiceMtlsImpl service = newService();

        assertThatThrownBy(service::fetchToken)
                .isInstanceOf(FederatorTokenException.class)
                .hasStackTraceContaining("Error fetching token from IDP for management node");
    }
}
