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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.utils.PropertyUtil;

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

    @Test @DisplayName("Returns EXPIRED when the endpoint reports an expired certificate")
    void returnsExpired() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"EXPIRED\"}");
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.EXPIRED);
    }

    @Test @DisplayName("Unrecognised status text maps to NOT_FOUND (default switch case)")
    void unknownStatusTextMapsToNotFound() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"status\":\"SOMETHING_ELSE\"}");
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.NOT_FOUND);
    }

    @Test @DisplayName("Non-200 HTTP response from the OCSP endpoint maps to NOT_FOUND")
    void returnsNotFound_onNon200Status() throws Exception {
        when(httpResponse.statusCode()).thenReturn(404);
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.NOT_FOUND);
        // Body is never read once the status check fails.
        verify(httpResponse, never()).body();
    }

    @Test @DisplayName("A response body with no 'status' field maps to NOT_FOUND (json.has() false branch)")
    void returnsNotFound_whenStatusFieldAbsent() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{}");
        assertThat(service.verify("elexon-prod-id")).isEqualTo(OcspStatus.NOT_FOUND);
    }

    @Test @DisplayName("resolveManagementNodeBaseUrl falls back to PropertyUtil when commonProps "
            + "itself is null (constructor parameter, not just missing the key)")
    void resolveManagementNodeBaseUrl_fallsBackToPropertyUtil_whenCommonPropsNull() throws Exception {
        try (MockedStatic<PropertyUtil> propertyUtil = mockStatic(PropertyUtil.class)) {
            propertyUtil.when(() -> PropertyUtil.getPropertyValue("management.node.base.url", ""))
                    .thenReturn("https://null-props-fallback.test");

            OcspClientVerificationServiceImpl fallbackService =
                    new OcspClientVerificationServiceImpl(null, idpTokenService) {
                        @Override
                        protected HttpClient buildHttpClient(Properties p) {
                            return httpClient;
                        }
                    };

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            fallbackService.verify("elexon-prod-id");
            verify(httpClient).send(captor.capture(), any());

            assertThat(captor.getValue().uri().toString()).startsWith("https://null-props-fallback.test");
        }
    }

    @Test @DisplayName("resolveManagementNodeBaseUrl falls back to PropertyUtil when "
            + "management.node.base.url is present but blank")
    void resolveManagementNodeBaseUrl_fallsBackToPropertyUtil_whenValueBlank() throws Exception {
        Properties propsWithBlankBaseUrl = new Properties();
        propsWithBlankBaseUrl.setProperty("management.node.base.url", "   ");

        try (MockedStatic<PropertyUtil> propertyUtil = mockStatic(PropertyUtil.class)) {
            propertyUtil.when(() -> PropertyUtil.getPropertyValue("management.node.base.url", ""))
                    .thenReturn("https://blank-value-fallback.test");

            OcspClientVerificationServiceImpl fallbackService =
                    new OcspClientVerificationServiceImpl(propsWithBlankBaseUrl, idpTokenService) {
                        @Override
                        protected HttpClient buildHttpClient(Properties p) {
                            return httpClient;
                        }
                    };

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            fallbackService.verify("elexon-prod-id");
            verify(httpClient).send(captor.capture(), any());

            assertThat(captor.getValue().uri().toString()).startsWith("https://blank-value-fallback.test");
        }
    }

    @Test @DisplayName("resolveManagementNodeBaseUrl falls back to PropertyUtil when the injected "
            + "Properties has no management.node.base.url entry")
    void resolveManagementNodeBaseUrl_fallsBackToPropertyUtil() throws Exception {
        Properties propsWithoutBaseUrl = new Properties();
        propsWithoutBaseUrl.setProperty("idp.truststore.path", "test.jks");
        propsWithoutBaseUrl.setProperty("idp.truststore.password", "changeit");

        try (MockedStatic<PropertyUtil> propertyUtil = mockStatic(PropertyUtil.class)) {
            propertyUtil.when(() -> PropertyUtil.getPropertyValue("management.node.base.url", ""))
                    .thenReturn("https://fallback-mn.test");

            OcspClientVerificationServiceImpl fallbackService =
                    new OcspClientVerificationServiceImpl(propsWithoutBaseUrl, idpTokenService) {
                        @Override
                        protected HttpClient buildHttpClient(Properties p) {
                            return httpClient;
                        }
                    };

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("{\"status\":\"ACTIVE\"}");

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            fallbackService.verify("elexon-prod-id");
            verify(httpClient).send(captor.capture(), any());

            assertThat(captor.getValue().uri().toString()).startsWith("https://fallback-mn.test");
        }
    }
}