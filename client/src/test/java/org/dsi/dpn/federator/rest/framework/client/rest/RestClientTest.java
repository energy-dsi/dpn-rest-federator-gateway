// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.rest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspClientVerificationService;
import org.dsi.dpn.federator.rest.framework.client.ocsp.OcspStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RestClient.
 * Uses package-visible constructor to inject mocks — no Management Node,
 * no PropertyUtil, no SSL required.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RestClientTest {

    @Mock IdpTokenService              idpTokenService;
    @Mock OcspClientVerificationService ocspService;
    @Mock WebClient                    webClient;
    @Mock WebClient.RequestHeadersUriSpec  getSpec;
    @Mock WebClient.RequestHeadersSpec     headersSpec;
    @Mock WebClient.RequestBodyUriSpec     postSpec;
    @Mock WebClient.RequestBodySpec        bodySpec;
    @Mock WebClient.ResponseSpec           responseSpec;

    static final String ORG           = "Elexon";
    static final String PRODUCER_ID   = "elexon-prod-id";
    static final String PRODUCT_NAME  = "FMAR Asset Registration";
    static final String BASE_URL      = "https://elexon-dpn.neso.gov.uk:8443";
    static final String ALLOWED_PATH  = "/api/v1/fmar/assets";
    static final ProductKey KEY       = new ProductKey(ORG, PRODUCT_NAME);
    static final List<AllowedPath> PATHS = List.of(
            new AllowedPath("GET",  "/api/v1/fmar/assets"),
            new AllowedPath("POST", "/api/v1/fmar/assets"),
            new AllowedPath("GET",  "/api/v1/fmar/assets/{mpan}"));

    RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.ProductRegistration reg = new RestClient.ProductRegistration(
                ORG, PRODUCER_ID, PRODUCT_NAME, BASE_URL, PATHS, webClient);

        client = new RestClient(
                Map.of(KEY, reg),
                idpTokenService, ocspService,
                Duration.ofSeconds(5));

        when(idpTokenService.fetchToken()).thenReturn("test-jwt-token");
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.ACTIVE);

        // Wire GET chain
        when(webClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString())).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("mock-response"));

        // Wire POST chain (PUT/PATCH reuse the same body-spec chain)
        when(webClient.post()).thenReturn(postSpec);
        when(webClient.put()).thenReturn(postSpec);
        when(webClient.patch()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);

        // DELETE reuses the body-less (GET) chain
        when(webClient.delete()).thenReturn(getSpec);
    }

    // ── getRegistration / getAllRegistrations ─────────────────────────────────

    @Test @DisplayName("getRegistration() returns registration for known producerId")
    void getRegistration_found() {
        Optional<RestClient.ProductRegistration> reg = client.getRegistration(KEY);
        assertThat(reg).isPresent();
        assertThat(reg.get().producerId()).isEqualTo(PRODUCER_ID);
        assertThat(reg.get().baseUrl()).isEqualTo(BASE_URL);
    }

    @Test @DisplayName("getRegistration() returns empty for unknown producerId")
    void getRegistration_notFound() {
        assertThat(client.getRegistration(new ProductKey(ORG, "unknown-id"))).isEmpty();
    }

    @Test @DisplayName("getAllRegistrations() returns all registered products")
    void getAllRegistrations() {
        Map<ProductKey, RestClient.ProductRegistration> all = client.getAllRegistrations();
        assertThat(all).hasSize(1);
        assertThat(all).containsKey(KEY);
    }

    @Test @DisplayName("ProductRegistration.isAllowed() checks method+path against allowed paths")
    void productRegistration_isAllowed() {
        RestClient.ProductRegistration reg = client.getRegistration(KEY).orElseThrow();
        // Allowed by PATHS (query string ignored, {mpan} template matches one segment):
        assertThat(reg.isAllowed("GET", "/api/v1/fmar/assets?importMpan=1")).isTrue();
        assertThat(reg.isAllowed("POST", "/api/v1/fmar/assets")).isTrue();
        assertThat(reg.isAllowed("GET", "/api/v1/fmar/assets/1000000000001")).isTrue();
        // Not allowed:
        assertThat(reg.isAllowed("GET", "/api/v1/fmar/assets/getClientID/extra")).isFalse();
        assertThat(reg.isAllowed("DELETE", "/api/v1/fmar/assets")).isFalse();
        assertThat(reg.isAllowed("GET", null)).isFalse();
    }

    // ── Path validation ───────────────────────────────────────────────────────

    @Test @DisplayName("get() throws IllegalArgumentException for unknown producerId")
    void get_unknownProducer() {
        assertThatThrownBy(() -> client.get(new ProductKey(ORG, "unknown-product"), "/api/v1/fmar/assets"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No REST product registered for");
    }

    @Test @DisplayName("get() passes the path straight through — no client-side path policing")
    void get_pathPassedThrough() {
        // The client does not validate paths against allowedPaths; the gateway is
        // the authority. Any path the caller supplies is sent as-is.
        String response = client.get(KEY, "/api/v1/fmar/assets/1000000000001");
        assertThat(response).isEqualTo("mock-response");
    }

    @Test @DisplayName("get() sends the request even for a path outside allowedPaths (server enforces)")
    void get_unlistedPathStillSent() {
        String response = client.get(KEY, "/api/v1/admin/secret");
        assertThat(response).isEqualTo("mock-response");
    }

    // ── OCSP check ────────────────────────────────────────────────────────────

    @Test @DisplayName("get() blocked when OCSP returns REVOKED")
    void get_ocspRevoked() {
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.REVOKED);
        assertThatThrownBy(() -> client.get(KEY, ALLOWED_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=REVOKED");
    }

    @Test @DisplayName("get() blocked when OCSP returns EXPIRED")
    void get_ocspExpired() {
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.EXPIRED);
        assertThatThrownBy(() -> client.get(KEY, ALLOWED_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=EXPIRED");
    }

    @Test @DisplayName("get() blocked when OCSP returns NOT_FOUND")
    void get_ocspNotFound() {
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.NOT_FOUND);
        assertThatThrownBy(() -> client.get(KEY, ALLOWED_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=NOT_FOUND");
    }

    // ── Successful GET / POST ─────────────────────────────────────────────────

    @Test @DisplayName("get() returns response body string on success")
    void get_success() {
        String result = client.get(KEY, "/api/v1/fmar/assets/1000000000001");
        assertThat(result).isEqualTo("mock-response");
        verify(idpTokenService).fetchToken();
        verify(ocspService).verify(PRODUCER_ID);
    }

    @Test @DisplayName("get() returns null when response body is empty")
    void get_nullBody() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.empty());
        String result = client.get(KEY, "/api/v1/fmar/assets/1000000000001");
        assertThat(result).isNull();
    }

    @Test @DisplayName("post() returns response body on success")
    void post_success() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("created"));
        String result = client.post(KEY, ALLOWED_PATH, "{\"importMpans\":[\"1000000000001\"]}");
        assertThat(result).isEqualTo("created");
        verify(idpTokenService).fetchToken();
    }

    @Test @DisplayName("put() sends a body and returns the response")
    void put_success() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("replaced"));
        String result = client.put(KEY, ALLOWED_PATH, "{\"x\":1}");
        assertThat(result).isEqualTo("replaced");
        verify(webClient).put();
        verify(idpTokenService).fetchToken();
        verify(ocspService).verify(PRODUCER_ID);
    }

    @Test @DisplayName("patch() sends a body and returns the response")
    void patch_success() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("patched"));
        String result = client.patch(KEY, ALLOWED_PATH, "{\"x\":1}");
        assertThat(result).isEqualTo("patched");
        verify(webClient).patch();
    }

    @Test @DisplayName("delete() sends no body and returns the response")
    void delete_success() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("deleted"));
        String result = client.delete(KEY, ALLOWED_PATH);
        assertThat(result).isEqualTo("deleted");
        verify(webClient).delete();
        verify(ocspService).verify(PRODUCER_ID);
    }

    @Test @DisplayName("put()/patch()/delete() also require a subscribed product")
    void bodyVerbs_unknownProduct() {
        ProductKey unknown = new ProductKey(ORG, "unknown-product");
        assertThatThrownBy(() -> client.put(unknown, ALLOWED_PATH, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No REST product registered for");
        assertThatThrownBy(() -> client.delete(unknown, ALLOWED_PATH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No REST product registered for");
    }

    @Test @DisplayName("post() uses empty JSON when payload is null")
    void post_nullPayload() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("ok"));
        String result = client.post(KEY, ALLOWED_PATH, null);
        assertThat(result).isEqualTo("ok");
        verify(bodySpec).bodyValue("{}");
    }

    @Test @DisplayName("get() throws RuntimeException on WebClientResponseException")
    void get_httpError() {
        when(responseSpec.bodyToMono(Object.class))
                .thenThrow(WebClientResponseException.create(
                        500, "Internal Server Error", null, null, null));
        assertThatThrownBy(() -> client.get(KEY, "/api/v1/fmar/assets/1000000000001"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("GET failed: HTTP");
    }

    @Test @DisplayName("post() throws RuntimeException on WebClientResponseException")
    void post_httpError() {
        when(responseSpec.bodyToMono(Object.class))
                .thenThrow(WebClientResponseException.create(
                        503, "Service Unavailable", null, null, null));
        assertThatThrownBy(() -> client.post(KEY, ALLOWED_PATH, "{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("POST failed: HTTP");
    }

    // ── ProductRegistration record ────────────────────────────────────────────

    @Test @DisplayName("ProductRegistration record accessors work correctly")
    void productRegistration_accessors() {
        RestClient.ProductRegistration reg = new RestClient.ProductRegistration(
                "My Org", "prod-id", "My Product", "https://host:8443",
                List.of(new AllowedPath("GET", "/api/v1/data")), webClient);
        assertThat(reg.organisation()).isEqualTo("My Org");
        assertThat(reg.producerId()).isEqualTo("prod-id");
        assertThat(reg.productName()).isEqualTo("My Product");
        assertThat(reg.baseUrl()).isEqualTo("https://host:8443");
        assertThat(reg.allowedPaths()).containsExactly(new AllowedPath("GET", "/api/v1/data"));
        assertThat(reg.webClient()).isEqualTo(webClient);
    }
}