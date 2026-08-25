// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;

/**
 * FRAMEWORK — generic authorising reverse proxy.
 *
 * <p>The REST Federator gateway carries no business knowledge. Every request that
 * reaches this controller has already passed the full security chain:
 * <ol>
 *   <li>mTLS termination (Spring Boot SSL bundle, client-auth=need)</li>
 *   <li>JWT signature validation against the DSM Identity Provider's JWKS</li>
 *   <li>Consumer verification — caller is registered against a REST product</li>
 *   <li>OCSP — caller's certificate is not revoked</li>
 *   <li>Method+path authorisation — {@code METHOD:path} matched against the
 *       product's DSM topic configuration</li>
 * </ol>
 *
 * <p>This controller then forwards the request — method, path, query string,
 * headers and body — to the configured backend and returns the backend's status,
 * headers and body verbatim. It never inspects or reshapes the payload, so the
 * gateway works unchanged for any data product.
 *
 * <p>The gateway→backend hop is authenticated with a shared secret
 * ({@link BackendProperties#API_KEY_HEADER}), since the backend does not
 * participate in the DPN's mTLS/JWT trust chain.
 */
@RestController
@Slf4j
public class BackendProxyController {

    /**
     * Headers that must not be copied to the outbound request. Hop-by-hop headers are
     * connection-scoped (RFC 7230 §6.1); Authorization is the consumer's DPN token and
     * is deliberately not forwarded to the internal backend, which uses the API key
     * instead; Host/Content-Length are recomputed by the outbound client.
     */
    private static final Set<String> EXCLUDED_REQUEST_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "authorization", "host", "content-length");

    /** Response headers recomputed by this server rather than passed through. */
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "content-length");

    /**
     * This gateway's own endpoints. They are served by their own handlers, but the
     * catch-all mapping below is checked against them defensively so an internal
     * path can never be forwarded to the backend.
     */
    private static final String[] INTERNAL_PREFIXES = {
            "/actuator", "/v3/api-docs", "/swagger-ui"
    };

    private final RestTemplate restTemplate;
    private final BackendProperties backendProperties;

    public BackendProxyController(RestTemplate restTemplate,
                                  BackendProperties backendProperties) {
        this.restTemplate = restTemplate;
        this.backendProperties = backendProperties;
        log.info("BackendProxyController initialised [backendBaseUrl={} apiKeyConfigured={}]",
                backendProperties.baseUrl(), backendProperties.hasApiKey());
    }

    /**
     * Catch-all forwarder. Mapped to GET and POST only — the methods the gateway
     * currently federates. Springdoc/actuator paths are excluded so Swagger and
     * health endpoints keep working.
     */
    @RequestMapping(
            value = "/**",
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          @RequestBody(required = false) byte[] body) {

        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        if (isInternalPath(request.getRequestURI())) {
            log.debug("Not proxying gateway-internal path {}", request.getRequestURI());
            return ResponseEntity.notFound().build();
        }

        String targetUrl = buildTargetUrl(request);

        if (backendProperties.baseUrl() == null || backendProperties.baseUrl().isBlank()) {
            log.error("backend.base-url is not configured — cannot forward {} {}",
                    method, request.getRequestURI());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        HttpHeaders outboundHeaders = copyRequestHeaders(request);
        if (backendProperties.hasApiKey()) {
            outboundHeaders.set(BackendProperties.API_KEY_HEADER, backendProperties.apiKey());
        } else {
            log.warn("backend.api-key is not configured — forwarding {} {} without an API key",
                    method, request.getRequestURI());
        }

        log.info("Proxying {} {} -> {}", method, request.getRequestURI(), targetUrl);

        try {
            ResponseEntity<byte[]> backendResponse = restTemplate.exchange(
                    targetUrl, method, new HttpEntity<>(body, outboundHeaders), byte[].class);

            log.info("Backend responded {} for {} {}",
                    backendResponse.getStatusCode(), method, request.getRequestURI());

            return ResponseEntity.status(backendResponse.getStatusCode())
                    .headers(filterResponseHeaders(backendResponse.getHeaders()))
                    .body(backendResponse.getBody());

        } catch (HttpStatusCodeException e) {
            // Backend returned 4xx/5xx — pass it through unchanged so the consumer
            // sees the backend's real status and error body.
            log.warn("Backend returned {} for {} {}",
                    e.getStatusCode(), method, request.getRequestURI());
            return ResponseEntity.status(e.getStatusCode())
                    .headers(filterResponseHeaders(e.getResponseHeaders()))
                    .body(e.getResponseBodyAsByteArray());

        } catch (ResourceAccessException e) {
            log.error("Backend unreachable at {}: {}", targetUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    boolean isInternalPath(String path) {
        for (String prefix : INTERNAL_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Backend base URL + original path + original query string. */
    String buildTargetUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder(backendProperties.baseUrl())
                .append(request.getRequestURI());
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            url.append('?').append(query);
        }
        return url.toString();
    }

    HttpHeaders copyRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return headers;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (EXCLUDED_REQUEST_HEADERS.contains(name.toLowerCase())) continue;
            headers.addAll(name, Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    HttpHeaders filterResponseHeaders(HttpHeaders source) {
        HttpHeaders headers = new HttpHeaders();
        if (source == null) return headers;
        source.forEach((name, values) -> {
            if (EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase())) return;
            headers.addAll(name, List.copyOf(values));
        });
        return headers;
    }
}
