// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.server.proxy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackendProxyControllerTest {

    static final String BASE_URL = "http://demo-runner-server:8080";
    static final String API_KEY  = "s3cret-key";

    RestTemplate restTemplate;
    BackendProxyController controller;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        controller = new BackendProxyController(
                restTemplate, new BackendProperties(BASE_URL, API_KEY));
    }

    MockHttpServletRequest request(String method, String uri, String query) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (query != null) req.setQueryString(query);
        return req;
    }

    // ── Target URL ────────────────────────────────────────────────────────────

    @Test @DisplayName("buildTargetUrl() appends the path and query string")
    void buildTargetUrl_withQuery() {
        URI url = controller.buildTargetUrl(
                request("GET", "/assets", "importMpan=1000000000001&postcode=SW1A+1AA"));

        assertThat(url).isEqualTo(URI.create(
                BASE_URL + "/assets?importMpan=1000000000001&postcode=SW1A+1AA"));
    }

    @Test @DisplayName("buildTargetUrl() omits the '?' when there is no query string")
    void buildTargetUrl_withoutQuery() {
        assertThat(controller.buildTargetUrl(request("POST", "/fsp/abc/assets", null)))
                .isEqualTo(URI.create(BASE_URL + "/fsp/abc/assets"));
    }

    @Test @DisplayName("A trailing slash on the base URL does not produce a double slash")
    void baseUrl_trailingSlashNormalised() {
        BackendProxyController c = new BackendProxyController(
                restTemplate, new BackendProperties(BASE_URL + "/", API_KEY));

        assertThat(c.buildTargetUrl(request("GET", "/assets", null)))
                .isEqualTo(URI.create(BASE_URL + "/assets"));
    }

    // ── Header handling ───────────────────────────────────────────────────────

    @Test @DisplayName("Forwards caller headers but not Authorization or Host")
    void copyRequestHeaders_dropsHopByHopAndAuth() {
        MockHttpServletRequest req = request("GET", "/assets", null);
        req.addHeader("X-Sender-FMAR-Id", "fsp-001");
        req.addHeader("X-Sender-Role", "FSP");
        req.addHeader("Authorization", "Bearer consumer-token");
        req.addHeader("Host", "gateway:8443");
        req.addHeader("Connection", "keep-alive");

        HttpHeaders headers = controller.copyRequestHeaders(req);

        assertThat(headers.getFirst("X-Sender-FMAR-Id")).isEqualTo("fsp-001");
        assertThat(headers.getFirst("X-Sender-Role")).isEqualTo("FSP");
        // The consumer's DPN token is not passed to the internal backend.
        assertThat(headers.containsKey("Authorization")).isFalse();
        assertThat(headers.containsKey("Host")).isFalse();
        assertThat(headers.containsKey("Connection")).isFalse();
    }

    @Test @DisplayName("filterResponseHeaders() drops connection-scoped headers")
    void filterResponseHeaders_dropsHopByHop() {
        HttpHeaders source = new HttpHeaders();
        source.add("Content-Type", "application/json");
        source.add("Transfer-Encoding", "chunked");
        source.add("Connection", "close");

        HttpHeaders filtered = controller.filterResponseHeaders(source);

        assertThat(filtered.getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(filtered.containsKey("Transfer-Encoding")).isFalse();
        assertThat(filtered.containsKey("Connection")).isFalse();
    }

    @Test @DisplayName("filterResponseHeaders() tolerates a null source")
    void filterResponseHeaders_nullSource() {
        assertThat(controller.filterResponseHeaders(null)).isEmpty();
    }

    // ── Forwarding ────────────────────────────────────────────────────────────

    @Test @DisplayName("Attaches the API key and returns the backend's response")
    void forward_attachesApiKeyAndReturnsResponse() {
        byte[] backendBody = "{\"assetName\":\"Battery\"}".getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(backendBody));

        ResponseEntity<byte[]> resp = controller.forward(
                request("GET", "/assets", "importMpan=1000000000001"), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(backendBody);

        var captor = forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET),
                captor.capture(), eq(byte[].class));
        assertThat(captor.getValue().getHeaders()
                .getFirst(BackendProperties.API_KEY_HEADER)).isEqualTo(API_KEY);
    }

    @Test @DisplayName("Passes a backend 4xx through unchanged")
    void forward_passesThroughBackendError() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class),
                any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.CONFLICT, "Conflict", new HttpHeaders(),
                        "{\"code\":\"DUPLICATE_MPAN\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        ResponseEntity<byte[]> resp = controller.forward(
                request("POST", "/fsp/abc/assets", null), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new String(resp.getBody(), StandardCharsets.UTF_8))
                .contains("DUPLICATE_MPAN");
    }

    @Test @DisplayName("An unreachable backend yields 502")
    void forward_unreachableBackend() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class),
                any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        ResponseEntity<byte[]> resp = controller.forward(
                request("GET", "/assets", null), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test @DisplayName("An unconfigured backend URL yields 503")
    void forward_noBackendConfigured() {
        BackendProxyController c = new BackendProxyController(
                restTemplate, new BackendProperties("", API_KEY));

        ResponseEntity<byte[]> resp = c.forward(request("GET", "/assets", null), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test @DisplayName("Gateway-internal paths are not proxied")
    void forward_internalPathsNotProxied() {
        assertThat(controller.isInternalPath("/actuator/health")).isTrue();
        assertThat(controller.isInternalPath("/v3/api-docs")).isTrue();
        assertThat(controller.isInternalPath("/swagger-ui/index.html")).isTrue();
        assertThat(controller.isInternalPath("/assets")).isFalse();

        ResponseEntity<byte[]> resp = controller.forward(
                request("GET", "/actuator/health", null), null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test @DisplayName("hasApiKey() reports whether a key is configured")
    void backendProperties_hasApiKey() {
        assertThat(new BackendProperties(BASE_URL, API_KEY).hasApiKey()).isTrue();
        assertThat(new BackendProperties(BASE_URL, "").hasApiKey()).isFalse();
        assertThat(new BackendProperties(BASE_URL, null).hasApiKey()).isFalse();
    }
}
