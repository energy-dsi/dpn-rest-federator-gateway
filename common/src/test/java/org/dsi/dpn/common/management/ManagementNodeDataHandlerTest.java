// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLException;
import org.dsi.dpn.common.model.dto.ConsumerConfigDTO;
import org.dsi.dpn.common.model.dto.ProducerConfigDTO;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link ManagementNodeDataHandler} - previously almost entirely untested (2%
 * coverage).
 * <p>
 * {@link PropertyUtil} is a static-singleton dependency the handler's constructor reads directly
 * (base URL + request timeout), so it is mocked via {@link org.mockito.Mockito#mockStatic}
 * (Mockito 5's inline mocking, already transitively available via
 * {@code spring-boot-starter-test}). The HTTP call itself is exercised via a mocked
 * {@link HttpClient} supplied through the handler's {@code Supplier<HttpClient>} constructor
 * argument, never a real network call.
 * </p>
 */
@SuppressWarnings("unchecked")
class ManagementNodeDataHandlerTest {

    private static final String BASE_URL_PROP = "management.node.base.url";
    private static final String TIMEOUT_PROP = "management.node.request.timeout";

    private MockedStatic<PropertyUtil> propertyUtilMock;
    private HttpClient httpClient;
    private HttpResponse<String> httpResponse;
    private IdpTokenService tokenService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(BASE_URL_PROP, "https://localhost:8080"))
                .thenReturn("https://mgmt.example.test/");
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(TIMEOUT_PROP, "5")).thenReturn("5");

        httpClient = mock(HttpClient.class);
        httpResponse = mock(HttpResponse.class);
        tokenService = mock(IdpTokenService.class);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
    }

    private ManagementNodeDataHandler newHandler() {
        return new ManagementNodeDataHandler(() -> httpClient, objectMapper, tokenService);
    }

    private ArgumentCaptor<HttpRequest> captureRequest() throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor;
    }

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects a null httpClientSupplier")
    void constructor_nullSupplier_throwsNullPointerException() {
        assertThatThrownBy(() -> new ManagementNodeDataHandler(null, objectMapper, tokenService))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects a null ObjectMapper")
    void constructor_nullMapper_throwsNullPointerException() {
        assertThatThrownBy(() -> new ManagementNodeDataHandler(() -> httpClient, null, tokenService))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor rejects a null IdpTokenService")
    void constructor_nullTokenService_throwsNullPointerException() {
        assertThatThrownBy(() -> new ManagementNodeDataHandler(() -> httpClient, objectMapper, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("constructor trims a trailing slash from the configured base URL")
    void constructor_trimsTrailingSlashFromBaseUrl() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"clientId\":\"c1\"}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();
        handler.getProducerData(null);

        assertThat(captureRequest().getValue().uri().toString())
                .isEqualTo("https://mgmt.example.test/api/v1/configuration/producer");
    }

    @Test
    @DisplayName("constructor wraps a PropertyUtil failure resolving the base URL in IllegalStateException")
    void constructor_baseUrlPropertyLookupFails_throwsIllegalStateException() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(BASE_URL_PROP, "https://localhost:8080"))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(this::newHandler)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BASE_URL_PROP);
    }

    @Test
    @DisplayName("constructor throws IllegalStateException when the base URL is blank")
    void constructor_blankBaseUrl_throwsIllegalStateException() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(BASE_URL_PROP, "https://localhost:8080"))
                .thenReturn("   ");

        assertThatThrownBy(this::newHandler)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base URL cannot be empty");
    }

    @Test
    @DisplayName("constructor wraps an invalid timeout value in IllegalStateException")
    void constructor_invalidTimeout_throwsIllegalStateException() {
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(TIMEOUT_PROP, "5")).thenReturn("not-a-number");

        assertThatThrownBy(this::newHandler)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TIMEOUT_PROP);
    }

    // ------------------------------------------------------------------
    // getProducerData / getConsumerData - happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getProducerData trims the producer id, builds the endpoint, and parses the DTO")
    void getProducerData_success_returnsParsedDto() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok-123");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"clientId\":\"prod-1\"}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();
        ProducerConfigDTO dto = handler.getProducerData("  prod-1  ");

        assertThat(dto.getClientId()).isEqualTo("prod-1");
        HttpRequest request = captureRequest().getValue();
        assertThat(request.uri().toString()).isEqualTo("https://mgmt.example.test/api/v1/configuration/producer/prod-1");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer tok-123");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
    }

    @Test
    @DisplayName("getProducerData omits the id segment when producerId is null")
    void getProducerData_nullId_usesBasePathOnly() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();
        handler.getProducerData(null);

        assertThat(captureRequest().getValue().uri().toString())
                .isEqualTo("https://mgmt.example.test/api/v1/configuration/producer");
    }

    @Test
    @DisplayName("getProducerData omits the id segment when producerId is blank")
    void getProducerData_blankId_usesBasePathOnly() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();
        handler.getProducerData("   ");

        assertThat(captureRequest().getValue().uri().toString())
                .isEqualTo("https://mgmt.example.test/api/v1/configuration/producer");
    }

    @Test
    @DisplayName("getConsumerData builds the consumer endpoint and parses the DTO")
    void getConsumerData_success_returnsParsedDto() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"clientId\":\"cons-1\",\"name\":\"n1\"}");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();
        ConsumerConfigDTO dto = handler.getConsumerData("cons-1");

        assertThat(dto.getClientId()).isEqualTo("cons-1");
        assertThat(dto.getName()).isEqualTo("n1");
        assertThat(captureRequest().getValue().uri().toString())
                .isEqualTo("https://mgmt.example.test/api/v1/configuration/consumer/cons-1");
    }

    // ------------------------------------------------------------------
    // Failure paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a non-200 response throws ManagementNodeDataException carrying the status code")
    void executeRequest_non200_throwsManagementNodeDataException() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("an empty response body throws ManagementNodeDataException")
    void executeRequest_emptyBody_throwsManagementNodeDataException() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    @DisplayName("a null response body throws ManagementNodeDataException")
    void executeRequest_nullBody_throwsManagementNodeDataException() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(null);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("Empty response");
    }

    @Test
    @DisplayName("a ConnectException from the HTTP client is wrapped with a connection-failure message")
    void executeRequest_connectException_wrapsWithConnectionMessage() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new ConnectException("refused"));

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("Cannot connect");
    }

    @Test
    @DisplayName("an SSLException from the HTTP client is wrapped with an SSL/TLS-failure message")
    void executeRequest_sslException_wrapsWithSslMessage() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new SSLException("handshake failed"));

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("SSL/TLS failed");
    }

    @Test
    @DisplayName("a generic IOException from the HTTP client is wrapped as a response-processing failure")
    void executeRequest_ioException_wrapsAsResponseFailure() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("disk full"));

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("Failed to process response");
    }

    @Test
    @DisplayName(
            "an InterruptedException from the HTTP client re-interrupts the thread and wraps as ManagementNodeDataException")
    void executeRequest_interruptedException_reInterruptsAndWraps() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));

        ManagementNodeDataHandler handler = newHandler();
        try {
            assertThatThrownBy(() -> handler.getProducerData("p1"))
                    .isInstanceOf(ManagementNodeDataException.class)
                    .hasMessageContaining("Request interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear the interrupt flag so it cannot leak into a later test in the same JVM.
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a malformed JSON body is wrapped as a response-processing failure")
    void executeRequest_malformedJson_wrapsAsResponseFailure() throws Exception {
        when(tokenService.fetchToken()).thenReturn("tok");
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("not-json");
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(ManagementNodeDataException.class)
                .hasMessageContaining("Failed to process response");
    }

    @Test
    @DisplayName("an unchecked exception from the token service propagates unwrapped")
    void fetchValidToken_tokenServiceThrows_propagatesUnwrapped() {
        when(tokenService.fetchToken()).thenThrow(new RuntimeException("idp down"));

        ManagementNodeDataHandler handler = newHandler();

        assertThatThrownBy(() -> handler.getProducerData("p1"))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ManagementNodeDataException.class)
                .hasMessage("idp down");
    }
}
