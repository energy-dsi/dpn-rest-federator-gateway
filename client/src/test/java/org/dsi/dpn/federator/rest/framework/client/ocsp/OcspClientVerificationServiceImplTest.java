// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.ocsp;

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
class OcspClientVerificationServiceImplTest {

    @Mock IdpTokenService         idpTokenService;
    @Mock HttpClient              httpClient;
    @Mock HttpResponse<String>    httpResponse;

    OcspClientVerificationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        // Override buildHttpClient() to inject mock - no real JKS file needed
        service = new OcspClientVerificationServiceImpl(testProps(), idpTokenService) {
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

    @Test @DisplayName("Returns ACTIVE for active producer certificate")
    void returnsActive() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.ACTIVE);
    }

    @Test @DisplayName("Returns REVOKED - outbound call should be blocked")
    void returnsRevoked() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"REVOKED\"}");
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.REVOKED);
    }

    @Test @DisplayName("Returns NOT_FOUND on network failure")
    void returnsNotFound_onException() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new RuntimeException("timeout"));
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.NOT_FOUND);
    }

    @Test @DisplayName("Passes producer clientId in URL - not empty string")
    void passesProducerClientId() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        service.verify("elexon-prod-id-123");
        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString())
                .contains("clientId=elexon-prod-id-123");
    }

    @Test @DisplayName("Bearer token included in OCSP request")
    void bearerTokenIncluded() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        service.verify("prod-id");
        verify(httpClient).send(captor.capture(), any());
        assertThat(captor.getValue().headers().firstValue("Authorization"))
                .hasValue("Bearer test-token");
    }
}