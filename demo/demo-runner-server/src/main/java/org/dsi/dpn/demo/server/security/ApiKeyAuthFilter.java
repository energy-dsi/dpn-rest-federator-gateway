// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the caller with a shared API key.
 *
 * <p>This service sits behind the REST Federator gateway and does not participate
 * in the DPN's mTLS/JWT trust chain. The gateway holds the same secret and sends it
 * on every forwarded request as {@value #API_KEY_HEADER}; requests without a
 * matching key are rejected with 401.
 *
 * <p>Health and API-documentation endpoints are exempt so probes and Swagger UI
 * keep working.
 */
@Component
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Backend-Api-Key";

    private static final String[] OPEN_PREFIXES = {
            "/actuator", "/v3/api-docs", "/swagger-ui"
    };

    private final String expectedApiKey;

    public ApiKeyAuthFilter(@Value("${backend.api-key:}") String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            log.warn("backend.api-key is not set - API key authentication is DISABLED. "
                    + "Set BACKEND_API_KEY to require callers to authenticate.");
        } else {
            log.info("ApiKeyAuthFilter initialised - requests must carry {}", API_KEY_HEADER);
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

        // No key configured - run open (local development only; a warning is
        // logged at startup so this cannot pass unnoticed in a deployment).
        if (expectedApiKey == null || expectedApiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null || !constantTimeEquals(provided, expectedApiKey)) {
            log.warn("Rejected {} {} - missing or invalid {}",
                    request.getMethod(), request.getRequestURI(), API_KEY_HEADER);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"code\":\"UNAUTHORIZED\","
                            + "\"message\":\"Missing or invalid API key.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /** Avoids leaking key content through comparison timing. */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
