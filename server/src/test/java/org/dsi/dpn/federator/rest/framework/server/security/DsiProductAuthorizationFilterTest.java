// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.dsi.dpn.common.model.dto.ConsumerDTO;
import org.dsi.dpn.common.model.dto.ProducerConfigDTO;
import org.dsi.dpn.common.model.dto.ProducerDTO;
import org.dsi.dpn.common.model.dto.ProductDTO;
import org.dsi.dpn.common.service.config.ProducerConfigService;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspStatus;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DsiProductAuthorizationFilterTest {

    @Mock ProducerConfigService   producerConfigService;
    @Mock OcspVerificationService ocspVerificationService;
    @Mock HttpServletRequest      request;
    @Mock HttpServletResponse     response;
    @Mock FilterChain             chain;
    @Mock SecurityContext         securityContext;
    @Mock JwtAuthenticationToken  jwtAuth;

    DsiProductAuthorizationFilter filter;
    StringWriter                  responseWriter;

    static final String CONSUMER_ID  = "fsp-consumer-123";
    static final String ALLOWED_PATH = "/api/v1/fmar/assets";
    /** DSM allowed-path configuration — a JSON array of request_type/request_path. */
    static final String TOPIC = """
            [{"request_type":"GET","request_path":"/api/v1/fmar/assets"},
             {"request_type":"POST","request_path":"/api/v1/fmar/assets"},
             {"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"}]
            """;

    @BeforeEach
    void setUp() throws Exception {
        filter = new DsiProductAuthorizationFilter(
                producerConfigService, ocspVerificationService, "test-server-client-id");
        responseWriter = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
        lenient().when(request.getMethod()).thenReturn("GET");
        SecurityContextHolder.setContext(securityContext);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    ProducerConfigDTO buildConfig(String consumerId, String topic) {
        ConsumerDTO consumer = new ConsumerDTO();
        consumer.setIdpClientId(consumerId);
        ProductDTO product = new ProductDTO();
        product.setType("rest");
        product.setTopic(topic);
        product.setConsumers(List.of(consumer));
        ProducerDTO producer = new ProducerDTO();
        producer.setProducts(List.of(product));
        ProducerConfigDTO config = new ProducerConfigDTO();
        config.setProducers(List.of(producer));
        return config;
    }

    void setupAuth(String consumerId) {
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);
        when(jwtAuth.isAuthenticated()).thenReturn(true);
        when(jwtAuth.getName()).thenReturn(consumerId);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test @DisplayName("All three stages pass — request proceeds to chain")
    void allStagesPass() throws Exception {
        setupAuth(CONSUMER_ID);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.ACTIVE);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    // ── Stage 1 failures ──────────────────────────────────────────────────────

    @Test @DisplayName("Stage 1 fails — consumer not in product list → 403")
    void stage1Fails_consumerNotInList() throws Exception {
        setupAuth("unknown-consumer");
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
        verify(ocspVerificationService, never()).verify(anyString());
    }

    @Test @DisplayName("isConsumerAuthorized() returns false for null config")
    void isConsumerAuthorized_nullConfig() {
        assertThat(filter.isConsumerAuthorized(null, CONSUMER_ID)).isFalse();
    }

    @Test @DisplayName("isConsumerAuthorized() returns false when no producers")
    void isConsumerAuthorized_emptyProducers() {
        ProducerConfigDTO config = new ProducerConfigDTO();
        config.setProducers(Collections.emptyList());
        assertThat(filter.isConsumerAuthorized(config, CONSUMER_ID)).isFalse();
    }

    @Test @DisplayName("isConsumerAuthorized() returns false for wrong product type")
    void isConsumerAuthorized_wrongProductType() {
        ConsumerDTO consumer = new ConsumerDTO();
        consumer.setIdpClientId(CONSUMER_ID);
        ProductDTO product = new ProductDTO();
        product.setType("topic"); // not REST
        product.setConsumers(List.of(consumer));
        ProducerDTO producer = new ProducerDTO();
        producer.setProducts(List.of(product));
        ProducerConfigDTO config = new ProducerConfigDTO();
        config.setProducers(List.of(producer));
        assertThat(filter.isConsumerAuthorized(config, CONSUMER_ID)).isFalse();
    }

    // ── Stage 2 failures ──────────────────────────────────────────────────────

    @Test @DisplayName("Stage 2 fails — OCSP REVOKED → 403")
    void stage2Fails_certRevoked() throws Exception {
        setupAuth(CONSUMER_ID);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.REVOKED);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
        verify(ocspVerificationService).verify(CONSUMER_ID);
    }

    @Test @DisplayName("Stage 2 fails — OCSP EXPIRED → 403")
    void stage2Fails_certExpired() throws Exception {
        setupAuth(CONSUMER_ID);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.EXPIRED);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test @DisplayName("Stage 2 fails — OCSP NOT_FOUND → 403")
    void stage2Fails_certNotFound() throws Exception {
        setupAuth(CONSUMER_ID);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.NOT_FOUND);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
    }

    // ── Stage 3 failures ──────────────────────────────────────────────────────

    @Test @DisplayName("Stage 3 fails — path not in topic → 403")
    void stage3Fails_pathNotAllowed() throws Exception {
        setupAuth(CONSUMER_ID);
        when(request.getRequestURI()).thenReturn("/api/v1/admin/internal");
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.ACTIVE);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test @DisplayName("Stage 3 fails — path allowed but method not granted → 403")
    void stage3Fails_methodNotAllowed() throws Exception {
        setupAuth(CONSUMER_ID);
        when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        // TOPIC grants GET and POST on this path, but not DELETE.
        when(request.getMethod()).thenReturn("DELETE");
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.ACTIVE);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
    }

    // ── Infrastructure ────────────────────────────────────────────────────────

    @Test @DisplayName("Open path bypasses all stages")
    void openPath_bypasses() throws Exception {
        lenient().when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(producerConfigService, never()).getProducerConfiguration();
        verify(ocspVerificationService, never()).verify(anyString());
    }

    @Test @DisplayName("No authentication — passes through")
    void noAuthentication_passesThrough() throws Exception {
        when(securityContext.getAuthentication()).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(ocspVerificationService, never()).verify(anyString());
    }

    @Test @DisplayName("ProducerConfigService throws — returns 403")
    void producerConfigService_throws() throws Exception {
        setupAuth(CONSUMER_ID);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenThrow(new RuntimeException("MN unavailable"));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
    }

    // ── Ant path matching ─────────────────────────────────────────────────────

    @Test @DisplayName("Ant wildcard /** matches sub-paths")
    void antWildcard_matchesSubPaths() throws Exception {
        setupAuth(CONSUMER_ID);
        when(request.getRequestURI()).thenReturn("/api/v1/fmar/assets/1000000000001");
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.ACTIVE);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test @DisplayName("isAuthorizedForPath — direct unit test of method+path logic")
    void isAuthorizedForPath_directTest() {
        ProducerConfigDTO config = buildConfig(CONSUMER_ID, TOPIC);
        assertThat(filter.isAuthorizedForPath(config, CONSUMER_ID,
                "GET", "/api/v1/fmar/assets")).isTrue();
        assertThat(filter.isAuthorizedForPath(config, CONSUMER_ID,
                "POST", "/api/v1/fmar/assets")).isTrue();
        assertThat(filter.isAuthorizedForPath(config, CONSUMER_ID,
                "GET", "/api/v1/fmar/assets/1000000000001")).isTrue();
        // {mpan} is a single-segment template, so a GET on the collection path
        // is granted by the first entry, not this one.
        assertThat(filter.isAuthorizedForPath(config, CONSUMER_ID,
                "GET", "/api/v1/admin/secret")).isFalse();
        assertThat(filter.isAuthorizedForPath(config, "wrong-consumer",
                "GET", "/api/v1/fmar/assets")).isFalse();
        // Method not granted for this path — DELETE is in no topic entry.
        assertThat(filter.isAuthorizedForPath(config, CONSUMER_ID,
                "DELETE", "/api/v1/fmar/assets")).isFalse();
    }

    @Test @DisplayName("pathMatchesProduct — JSON allowed-path entries")
    void pathMatchesProduct_jsonEntries() {
        String topic = """
                [{"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"},
                 {"request_type":"POST","request_path":"/api/v1/fmar/assets"}]
                """;

        assertThat(filter.pathMatchesProduct(
                topic, "GET", "/api/v1/fmar/assets/1000000000001")).isTrue();
        assertThat(filter.pathMatchesProduct(
                topic, "POST", "/api/v1/fmar/assets")).isTrue();
        // Right path, wrong method.
        assertThat(filter.pathMatchesProduct(
                topic, "POST", "/api/v1/fmar/assets/1000000000001")).isFalse();
        // Path not configured at all.
        assertThat(filter.pathMatchesProduct(
                topic, "GET", "/api/v1/admin/secret")).isFalse();
        // {mpan} spans a single segment only.
        assertThat(filter.pathMatchesProduct(
                topic, "GET", "/api/v1/fmar/assets/1000000000001/extra")).isFalse();
    }

    @Test @DisplayName("pathMatchesProduct — unusable configuration denies access")
    void pathMatchesProduct_failsClosed() {
        assertThat(filter.pathMatchesProduct("", "GET", "/api/v1/fmar/assets")).isFalse();
        assertThat(filter.pathMatchesProduct(null, "GET", "/api/v1/fmar/assets")).isFalse();
        assertThat(filter.pathMatchesProduct("[]", "GET", "/api/v1/fmar/assets")).isFalse();
        assertThat(filter.pathMatchesProduct(
                "not json at all", "GET", "/api/v1/fmar/assets")).isFalse();
    }

    @Test @DisplayName("OCSP called with actual consumerId — not empty string (fixes gRPC bug)")
    void ocspCalledWithActualConsumerId_notEmptyString() throws Exception {
        setupAuth(CONSUMER_ID);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.ACTIVE);

        filter.doFilterInternal(request, response, chain);

        verify(ocspVerificationService).verify(CONSUMER_ID);
        verify(ocspVerificationService, never()).verify("");
    }

    @Test @DisplayName("auth.isAuthenticated() false — passes through without checking")
    void notAuthenticated_passesThrough() throws Exception {
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);
        when(jwtAuth.isAuthenticated()).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(producerConfigService, never()).getProducerConfiguration();
    }

    @Test @DisplayName("blank consumerId from token → 401")
    void blankConsumerId_returns401() throws Exception {
        when(securityContext.getAuthentication()).thenReturn(jwtAuth);
        when(jwtAuth.isAuthenticated()).thenReturn(true);
        when(jwtAuth.getName()).thenReturn("  "); // blank
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test @DisplayName("extractConsumerId falls back to auth.getName() for non-JWT")
    void extractConsumerId_nonJwtAuth() throws Exception {
        // Use a plain Authentication (not JwtAuthenticationToken)
        org.springframework.security.core.Authentication plainAuth =
                mock(org.springframework.security.core.Authentication.class);
        when(plainAuth.isAuthenticated()).thenReturn(true);
        when(plainAuth.getName()).thenReturn(CONSUMER_ID);
        when(securityContext.getAuthentication()).thenReturn(plainAuth);
        lenient().when(request.getRequestURI()).thenReturn(ALLOWED_PATH);
        when(producerConfigService.getProducerConfiguration())
                .thenReturn(buildConfig(CONSUMER_ID, TOPIC));
        when(ocspVerificationService.verify(CONSUMER_ID)).thenReturn(OcspStatus.ACTIVE);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test @DisplayName("consumerInList — product with null consumers list returns false")
    void consumerInList_nullConsumersList() {
        ProductDTO product = new ProductDTO();
        product.setType("rest");
        product.setConsumers(null);
        ProducerDTO producer = new ProducerDTO();
        producer.setProducts(java.util.List.of(product));
        ProducerConfigDTO config = new ProducerConfigDTO();
        config.setProducers(java.util.List.of(producer));
        assertThat(filter.isConsumerAuthorized(config, CONSUMER_ID)).isFalse();
    }

    @Test @DisplayName("shouldNotFilter — swagger-ui path bypasses filter")
    void shouldNotFilter_swaggerPath() throws Exception {
        lenient().when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
        verify(producerConfigService, never()).getProducerConfiguration();
    }

    @Test @DisplayName("shouldNotFilter — v3 api docs path bypasses filter")
    void shouldNotFilter_apiDocsPath() throws Exception {
        lenient().when(request.getRequestURI()).thenReturn("/v3/api-docs");
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
        verify(producerConfigService, never()).getProducerConfiguration();
    }
}