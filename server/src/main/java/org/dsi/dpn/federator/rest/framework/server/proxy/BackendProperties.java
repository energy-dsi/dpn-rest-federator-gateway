// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FRAMEWORK — configuration for the internal backend the gateway forwards to.
 *
 * <p>The gateway itself holds no business logic: once a request has passed JWT
 * validation, consumer verification, OCSP and method+path authorisation, it is
 * forwarded verbatim to this backend (the participant's own data-source API).
 *
 * <p>Bound from {@code backend.*} in application.properties:
 * <pre>
 *   backend.base-url=${BACKEND_BASE_URL:http://dpn-demo-runner-server:8080}
 *   backend.api-key=${BACKEND_API_KEY:}
 * </pre>
 *
 * <p>{@code apiKey} is the shared secret the backend requires; it is sent on every
 * forwarded request as the {@code X-Backend-Api-Key} header. Supply it from a
 * Kubernetes Secret via the BACKEND_API_KEY environment variable — never commit it.
 */
@ConfigurationProperties(prefix = "backend")
public record BackendProperties(String baseUrl, String apiKey) {

    /** Header carrying the shared secret to the backend. */
    public static final String API_KEY_HEADER = "X-Backend-Api-Key";

    public BackendProperties {
        if (baseUrl != null && baseUrl.endsWith("/")) {
            // Normalise so path concatenation never produces a double slash.
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
