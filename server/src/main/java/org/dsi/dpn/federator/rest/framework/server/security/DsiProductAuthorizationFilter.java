// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.dsi.dpn.common.model.dto.ProductDTO;
import org.dsi.dpn.common.model.dto.ProducerConfigDTO;
import org.dsi.dpn.common.service.config.ProducerConfigService;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspStatus;
import org.dsi.dpn.federator.rest.framework.server.ocsp.OcspVerificationService;

/**
 * FRAMEWORK — do not modify.
 *
 * Three-stage authorization filter. Runs after Spring OAuth2 JWT validation.
 * Mirrors ConsumerVerificationServerInterceptor and OcspServerInterceptor from
 * the gRPC Federator, combined into a single Spring Security filter.
 *
 * Stage 1 — Consumer verification (same as ConsumerVerificationServerInterceptor):
 *   Extracts consumerId (azp claim) from JWT. Checks consumer appears in at least
 *   one type="rest" product's consumer list. Returns 403 if not found.
 *
 * Stage 2 — OCSP certificate revocation check (same intent as OcspServerInterceptor):
 *   Calls OcspVerificationService.verify(consumerId) — hits Management Node
 *   GET /api/v1/certificate/ocsp?clientId={consumerId}.
 *   Returns 403 if status is not ACTIVE.
 *   Fixes the gRPC bug where OcspServerInterceptor passed empty string instead
 *   of the actual consumer clientId.
 *
 * Stage 3 — Product method+path authorization (REST-specific):
 *   Checks the request's HTTP method and URI match an entry in the product's
 *   allowed-path configuration, which DSM supplies in the topic field as a JSON
 *   array of {request_type, request_path} objects:
 *     [{"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"},
 *      {"request_type":"POST","request_path":"/api/v1/fmar/assets"}]
 *   Returns 403 if no entry matches. See {@link AllowedPath}.
 *
 * All three stages must pass for the same product entry.
 *
 * Open paths (actuator, Swagger) bypass all stages.
 */
@Component
@Slf4j
public class DsiProductAuthorizationFilter extends OncePerRequestFilter {

    static final String PRODUCT_TYPE_REST  = "rest";
    private static final String IDP_CLIENT_ID_PROP  = "idp.client.id";
    private static final String COMMON_CONFIG_PROP  = "common.configuration";

    private static final String[] OPEN_PREFIXES = {
            "/actuator", "/v3/api-docs", "/swagger-ui", "/.well-known"
    };

    private final ProducerConfigService  producerConfigService;
    private final OcspVerificationService ocspVerificationService;
    private final AntPathMatcher         antMatcher;
    private final String                 serverClientId;

    @Autowired
    public DsiProductAuthorizationFilter(
            final ProducerConfigService producerConfigService,
            final OcspVerificationService ocspVerificationService) {
        this(producerConfigService, ocspVerificationService, resolveServerClientId());
    }

    /** Package-visible constructor for testing — avoids PropertyUtil dependency. */
    DsiProductAuthorizationFilter(
            final ProducerConfigService producerConfigService,
            final OcspVerificationService ocspVerificationService,
            final String serverClientId) {
        this.producerConfigService   = producerConfigService;
        this.ocspVerificationService = ocspVerificationService;
        this.antMatcher              = new AntPathMatcher();
        this.serverClientId          = serverClientId;
        log.info("DsiProductAuthorizationFilter initialised [serverClientId={}]",
                serverClientId);
    }

    private static String resolveServerClientId() {
        try {
            return PropertyUtil
                    .getPropertiesFromFilePath(COMMON_CONFIG_PROP)
                    .getProperty(IDP_CLIENT_ID_PROP, "");
        } catch (Exception e) {
            log.warn("Could not read serverClientId: {}", e.getMessage());
            return "";
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : OPEN_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String consumerId = extractConsumerId(auth);
        if (StringUtils.isBlank(consumerId)) {
            log.warn("Missing client ID in token for path={}", request.getRequestURI());
            sendError(response, HttpStatus.UNAUTHORIZED, "Missing client ID in token");
            return;
        }

        // Stage 1 — Consumer verification
        ProducerConfigDTO config;
        try {
            config = producerConfigService.getProducerConfiguration();
        } catch (Exception e) {
            log.error("Failed to fetch producer config: {}", e.getMessage());
            sendError(response, HttpStatus.FORBIDDEN, "Unable to verify authorization");
            return;
        }

        if (!isConsumerAuthorized(config, consumerId)) {
            log.warn("Stage 1 failed — consumer not in product list: consumerId={}",
                    consumerId);
            sendError(response, HttpStatus.FORBIDDEN,
                    "Consumer not authorized for this product or path");
            return;
        }

        // Stage 2 — OCSP certificate revocation check
        // Fixes gRPC bug: passes actual consumerId, not empty string
        OcspStatus ocspStatus = ocspVerificationService.verify(consumerId);
        if (ocspStatus != OcspStatus.ACTIVE) {
            log.warn("Stage 2 failed — OCSP status={} consumerId={}", ocspStatus, consumerId);
            sendError(response, HttpStatus.FORBIDDEN,
                    "Certificate revocation check failed: status=" + ocspStatus);
            return;
        }

        // Stage 3 — Product method+path authorization
        if (!isAuthorizedForPath(config, consumerId,
                request.getMethod(), request.getRequestURI())) {
            log.warn("Stage 3 failed — method+path not allowed: consumerId={} {} {}",
                    consumerId, request.getMethod(), request.getRequestURI());
            sendError(response, HttpStatus.FORBIDDEN,
                    "Consumer not authorized for this product or path");
            return;
        }

        log.debug("All stages passed: consumerId={} {} {}",
                consumerId, request.getMethod(), request.getRequestURI());
        chain.doFilter(request, response);
    }

    /**
     * Stage 1: checks consumer appears in at least one type="rest" product's consumer list.
     */
    boolean isConsumerAuthorized(ProducerConfigDTO config, String consumerId) {
        if (config == null || config.getProducers() == null) return false;
        return config.getProducers().stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getProducts() != null)
                .flatMap(p -> p.getProducts().stream())
                .filter(Objects::nonNull)
                .filter(prod -> PRODUCT_TYPE_REST.equalsIgnoreCase(prod.getType()))
                .anyMatch(prod -> consumerInList(prod, consumerId));
    }

    /**
     * Stages 1+3 combined: consumer authorized AND method+path matches.
     */
    boolean isAuthorizedForPath(ProducerConfigDTO config,
                                String consumerId,
                                String requestMethod,
                                String requestPath) {
        if (config == null || config.getProducers() == null) return false;
        return config.getProducers().stream()
                .filter(Objects::nonNull)
                .filter(p -> p.getProducts() != null)
                .flatMap(p -> p.getProducts().stream())
                .filter(Objects::nonNull)
                .filter(prod -> PRODUCT_TYPE_REST.equalsIgnoreCase(prod.getType()))
                .filter(prod -> consumerInList(prod, consumerId))
                .anyMatch(prod -> pathMatchesProduct(prod.getTopic(), requestMethod, requestPath));
    }

    private boolean consumerInList(ProductDTO product, String consumerId) {
        if (product.getConsumers() == null) return false;
        return product.getConsumers().stream()
                .filter(Objects::nonNull)
                .filter(c -> c.getIdpClientId() != null)
                .anyMatch(c -> consumerId.equalsIgnoreCase(c.getIdpClientId()));
    }

    /**
     * Matches the request's method+path against the product's allowed-path
     * configuration, supplied by DSM in the topic field as a JSON array:
     * <pre>
     *   [{"request_type":"GET","request_path":"/api/v1/fmar/assets/{mpan}"},
     *    {"request_type":"POST","request_path":"/api/v1/fmar/assets"}]
     * </pre>
     *
     * <p>An unparseable or empty configuration matches nothing, so a
     * misconfigured product denies access rather than granting it.
     *
     * @see AllowedPath#parse(String)
     */
    boolean pathMatchesProduct(String topic, String requestMethod, String requestPath) {
        List<AllowedPath> allowed = AllowedPath.parse(topic);
        if (allowed.isEmpty()) {
            log.warn("Product has no usable allowed-path configuration — denying {} {}",
                    requestMethod, requestPath);
            return false;
        }
        return allowed.stream()
                .anyMatch(entry -> entry.matches(antMatcher, requestMethod, requestPath));
    }

    private String extractConsumerId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken token) return token.getName();
        return auth.getName();
    }

    private void sendError(HttpServletResponse response, HttpStatus status,
                           String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"" + status.name() + "\",\"message\":\"" + message + "\"}");
    }
}