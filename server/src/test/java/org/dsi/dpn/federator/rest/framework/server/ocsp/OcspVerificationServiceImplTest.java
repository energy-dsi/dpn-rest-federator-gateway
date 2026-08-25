// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.server.ocsp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.dsi.dpn.common.service.idp.IdpTokenService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OcspVerificationServiceImplTest {

    @Mock IdpTokenService         idpTokenService;
    @Mock HttpClient              httpClient;
    @Mock HttpResponse<String>    httpResponse;

    OcspVerificationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        // Override buildHttpClient() to inject mock — no real JKS file needed
        service = new OcspVerificationServiceImpl(testProps(), idpTokenService) {
            @Override
            protected java.net.http.HttpClient buildHttpClient(Properties p) {
                return httpClient;
            }
        };
        lenient().when(idpTokenService.fetchToken()).thenReturn("test-token");
        lenient().when(httpClient.send(any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
    }

    Properties testProps() {
        Properties p = new Properties();
        p.setProperty("management.node.base.url", "https://management-node.test");
        p.setProperty("idp.truststore.path", "test.jks");
        p.setProperty("idp.truststore.password", "changeit");
        return p;
    }

    @Test @DisplayName("Returns ACTIVE")
    void returnsActive() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");
        assertThat(service.verify("consumer-123")).isEqualTo(OcspStatus.ACTIVE);
    }

    @Test @DisplayName("Returns REVOKED")
    void returnsRevoked() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"REVOKED\"}");
        assertThat(service.verify("consumer-123")).isEqualTo(OcspStatus.REVOKED);
    }

    @Test @DisplayName("Returns EXPIRED")
    void returnsExpired() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"EXPIRED\"}");
        assertThat(service.verify("consumer-123")).isEqualTo(OcspStatus.EXPIRED);
    }

    @Test @DisplayName("Returns NOT_FOUND on unknown status")
    void returnsNotFound_unknownStatus() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"UNKNOWN\"}");
        assertThat(service.verify("consumer-123")).isEqualTo(OcspStatus.NOT_FOUND);
    }

    @Test @DisplayName("Returns NOT_FOUND on non-200 response")
    void returnsNotFound_non200() throws Exception {
        when(httpResponse.statusCode()).thenReturn(404);
        assertThat(service.verify("consumer-123")).isEqualTo(OcspStatus.NOT_FOUND);
    }

    @Test @DisplayName("Returns NOT_FOUND on network exception")
    void returnsNotFound_onException() throws Exception {
        lenient().when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new RuntimeException("network failure"));
        assertThat(service.verify("consumer-123")).isEqualTo(OcspStatus.NOT_FOUND);
    }

    @Test @DisplayName("Passes actual clientId in URL — fixes gRPC empty-string bug")
    void passesActualClientIdInUrl() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        service.verify("specific-consumer-id");
        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString())
                .contains("clientId=specific-consumer-id");
    }
}