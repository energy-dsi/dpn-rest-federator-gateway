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
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
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

    static final String PRODUCER_ID   = "elexon-prod-id";
    static final String PRODUCT_NAME  = "FMAR Asset Registration";
    static final String BASE_URL      = "https://elexon-dpn.neso.gov.uk:8443";
    static final String ALLOWED_PATH  = "/api/v1/fmar/assets";
    static final List<AllowedPath> PATHS = List.of(
            new AllowedPath("GET",  "/api/v1/fmar/assets"),
            new AllowedPath("POST", "/api/v1/fmar/assets"),
            new AllowedPath("GET",  "/api/v1/fmar/assets/{mpan}"));

    RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.ProductRegistration reg = new RestClient.ProductRegistration(
                PRODUCER_ID, PRODUCT_NAME, BASE_URL, PATHS, webClient);

        client = new RestClient(
                Map.of(PRODUCT_NAME, reg),
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

        // Wire POST chain
        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
    }

    // ── getRegistration / getAllRegistrations ─────────────────────────────────

    @Test @DisplayName("getRegistration() returns registration for known producerId")
    void getRegistration_found() {
        Optional<RestClient.ProductRegistration> reg = client.getRegistration(PRODUCT_NAME);
        assertThat(reg).isPresent();
        assertThat(reg.get().producerId()).isEqualTo(PRODUCER_ID);
        assertThat(reg.get().baseUrl()).isEqualTo(BASE_URL);
    }

    @Test @DisplayName("getRegistration() returns empty for unknown producerId")
    void getRegistration_notFound() {
        assertThat(client.getRegistration("unknown-id")).isEmpty();
    }

    @Test @DisplayName("getAllRegistrations() returns all registered products")
    void getAllRegistrations() {
        Map<String, RestClient.ProductRegistration> all = client.getAllRegistrations();
        assertThat(all).hasSize(1);
        assertThat(all).containsKey(PRODUCT_NAME);
    }

    // ── Path validation ───────────────────────────────────────────────────────

    @Test @DisplayName("get() throws IllegalArgumentException for unknown producerId")
    void get_unknownProducer() {
        assertThatThrownBy(() -> client.get("unknown-product", "/api/v1/fmar/assets"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No REST product registered for productName=unknown-prod");
    }

    @Test @DisplayName("get() throws IllegalArgumentException for path not in allowedPaths")
    void get_pathNotAllowed() {
        assertThatThrownBy(() -> client.get(PRODUCT_NAME, "/api/v1/admin/secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowed paths");
    }

    @Test @DisplayName("post() throws IllegalArgumentException for path not in allowedPaths")
    void post_pathNotAllowed() {
        assertThatThrownBy(() -> client.post(PRODUCT_NAME, "/api/v1/admin/secret", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowed paths");
    }

    @Test @DisplayName("Path template {mpan} matches a single sub-path segment")
    void get_pathTemplateMatchesSegment() {
        String response = client.get(PRODUCT_NAME, "/api/v1/fmar/assets/1000000000001");
        assertThat(response).isEqualTo("mock-response");
    }

    @Test @DisplayName("get() rejects a method not granted for the path")
    void get_methodNotAllowed() {
        // PATHS grants POST on /api/v1/fmar/assets but GET only on the collection
        // and the {mpan} template — a GET two segments deep is not permitted.
        assertThatThrownBy(() -> client.get(PRODUCT_NAME, "/api/v1/fmar/assets/1/2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the allowed paths");
    }

    @Test @DisplayName("Query strings are ignored when validating the path")
    void get_queryStringStripped() {
        String response = client.get(PRODUCT_NAME,
                "/api/v1/fmar/assets?importMpan=1000000000001&postcode=SW1A+1AA");
        assertThat(response).isEqualTo("mock-response");
    }

    // ── OCSP check ────────────────────────────────────────────────────────────

    @Test @DisplayName("get() blocked when OCSP returns REVOKED")
    void get_ocspRevoked() {
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.REVOKED);
        assertThatThrownBy(() -> client.get(PRODUCT_NAME, ALLOWED_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=REVOKED");
    }

    @Test @DisplayName("get() blocked when OCSP returns EXPIRED")
    void get_ocspExpired() {
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.EXPIRED);
        assertThatThrownBy(() -> client.get(PRODUCT_NAME, ALLOWED_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=EXPIRED");
    }

    @Test @DisplayName("get() blocked when OCSP returns NOT_FOUND")
    void get_ocspNotFound() {
        when(ocspService.verify(PRODUCER_ID)).thenReturn(OcspStatus.NOT_FOUND);
        assertThatThrownBy(() -> client.get(PRODUCT_NAME, ALLOWED_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OCSP status=NOT_FOUND");
    }

    // ── Successful GET / POST ─────────────────────────────────────────────────

    @Test @DisplayName("get() returns response body string on success")
    void get_success() {
        String result = client.get(PRODUCT_NAME, "/api/v1/fmar/assets/1000000000001");
        assertThat(result).isEqualTo("mock-response");
        verify(idpTokenService).fetchToken();
        verify(ocspService).verify(PRODUCER_ID);
    }

    @Test @DisplayName("get() returns null when response body is empty")
    void get_nullBody() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.empty());
        String result = client.get(PRODUCT_NAME, "/api/v1/fmar/assets/1000000000001");
        assertThat(result).isNull();
    }

    @Test @DisplayName("post() returns response body on success")
    void post_success() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("created"));
        String result = client.post(PRODUCT_NAME, ALLOWED_PATH, "{\"importMpans\":[\"1000000000001\"]}");
        assertThat(result).isEqualTo("created");
        verify(idpTokenService).fetchToken();
    }

    @Test @DisplayName("post() uses empty JSON when payload is null")
    void post_nullPayload() {
        when(responseSpec.bodyToMono(Object.class)).thenReturn(Mono.just("ok"));
        String result = client.post(PRODUCT_NAME, ALLOWED_PATH, null);
        assertThat(result).isEqualTo("ok");
        verify(bodySpec).bodyValue("{}");
    }

    @Test @DisplayName("get() throws RuntimeException on WebClientResponseException")
    void get_httpError() {
        when(responseSpec.bodyToMono(Object.class))
                .thenThrow(WebClientResponseException.create(
                        500, "Internal Server Error", null, null, null));
        assertThatThrownBy(() -> client.get(PRODUCT_NAME, "/api/v1/fmar/assets/1000000000001"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("GET failed: HTTP");
    }

    @Test @DisplayName("post() throws RuntimeException on WebClientResponseException")
    void post_httpError() {
        when(responseSpec.bodyToMono(Object.class))
                .thenThrow(WebClientResponseException.create(
                        503, "Service Unavailable", null, null, null));
        assertThatThrownBy(() -> client.post(PRODUCT_NAME, ALLOWED_PATH, "{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("POST failed: HTTP");
    }

    // ── ProductRegistration record ────────────────────────────────────────────

    @Test @DisplayName("ProductRegistration record accessors work correctly")
    void productRegistration_accessors() {
        RestClient.ProductRegistration reg = new RestClient.ProductRegistration(
                "prod-id", "My Product", "https://host:8443",
                List.of(new AllowedPath("GET", "/api/v1/data")), webClient);
        assertThat(reg.producerId()).isEqualTo("prod-id");
        assertThat(reg.productName()).isEqualTo("My Product");
        assertThat(reg.baseUrl()).isEqualTo("https://host:8443");
        assertThat(reg.allowedPaths()).containsExactly(new AllowedPath("GET", "/api/v1/data"));
        assertThat(reg.webClient()).isEqualTo(webClient);
    }
}