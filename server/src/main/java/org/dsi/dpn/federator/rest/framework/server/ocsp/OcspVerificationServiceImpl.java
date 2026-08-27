// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.federator.rest.framework.server.ocsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;
import org.dsi.dpn.common.telemetry.OtelVerificationLogger;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.SSLUtils;

/**
 * FRAMEWORK — server side. Do not modify.
 *
 * Calls GET /api/v1/certificate/ocsp?clientId={clientId} on the Management Node.
 * Uses the same mTLS HttpClient pattern as OcspCertificateVerificationServiceImpl
 * in the gRPC Federator. Uses a truststore-only SSL context (no client key needed
 * for this outbound call — Management Node accepts the server's existing cert).
 *
 * The gRPC OcspServerInterceptor passed an empty string as clientId — this
 * implementation fixes that by always passing the actual consumer clientId
 * extracted from the JWT azp claim by DsiProductAuthorizationFilter.
 *
 * Emits OTEL-format structured log for every verification attempt.
 *
 * Properties read from common.configuration (already loaded by server):
 *   management.node.base.url   — Management Node base URL
 *   idp.truststore.path        — JKS for verifying Management Node TLS cert
 *   idp.truststore.password
 */
@Slf4j
public class OcspVerificationServiceImpl implements OcspVerificationService {

    private static final String MN_BASE_URL_PROP     = "management.node.base.url";
    private static final String TRUSTSTORE_PATH_PROP = "idp.truststore.path";
    private static final String TRUSTSTORE_PASS_PROP = "idp.truststore.password";
    private static final String OCSP_PATH            = "/api/v1/certificate/ocsp";

    private static final Tracer TRACER =
            OpenTelemetryConfig.get().getTracer("org.dsi.dpn.federator.rest.framework.server.ocsp");

    private final String          managementNodeBaseUrl;
    private final HttpClient      httpClient;
    private final IdpTokenService idpTokenService;
    private final ObjectMapper    objectMapper;

    public OcspVerificationServiceImpl(Properties commonProps,
                                       IdpTokenService idpTokenService) {
        this.managementNodeBaseUrl = resolveManagementNodeBaseUrl(commonProps);
        this.idpTokenService = idpTokenService;
        this.objectMapper    = new ObjectMapper();

        this.httpClient = buildHttpClient(commonProps);

        log.info("OcspVerificationServiceImpl initialised [mnBaseUrl={}]",
                managementNodeBaseUrl);
    }

    /**
     * Resolves the Management Node base URL, preferring the injected properties
     * and falling back to the static {@link PropertyUtil}.
     *
     * <p>{@code management.node.base.url} is declared in {@code server.properties},
     * not in any {@code common-configuration*.properties}, so in production the
     * injected common properties do not carry it and the PropertyUtil fallback is
     * what supplies the value — mirrors OcspClientVerificationServiceImpl's
     * resolution on the client side.
     */
    private static String resolveManagementNodeBaseUrl(Properties commonProps) {
        if (commonProps != null) {
            String fromProps = commonProps.getProperty(MN_BASE_URL_PROP);
            if (fromProps != null && !fromProps.isBlank()) {
                return fromProps;
            }
        }
        return PropertyUtil.getPropertyValue(MN_BASE_URL_PROP, "");
    }

    @Override
    public OcspStatus verify(String clientId) {
        Span span = TRACER.spanBuilder("OcspVerificationServiceImpl.verify")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("dpn.certificate.client_id", clientId)
                .startSpan();
        Instant timestamp = Instant.now();
        OcspStatus status = OcspStatus.NOT_FOUND;
        try (Scope scope = span.makeCurrent()) {
            status = checkStatus(clientId);
            span.setAttribute("dpn.certificate.verification_status", status.name());
        } catch (Exception e) {
            log.warn("OCSP check failed for clientId={}: {}", clientId, e.getMessage());
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
        } finally {
            OtelVerificationLogger.log(log, clientId, timestamp, status == OcspStatus.ACTIVE, status.name());
            span.end();
        }
        return status;
    }

    private OcspStatus checkStatus(String clientId) throws Exception {
        // Fix for gRPC bug: always pass the actual clientId, never empty string.
        // Built via UriComponentsBuilder (not string concatenation) so clientId —
        // JWT-derived, but still request-influenced data — can only ever populate
        // the clientId query parameter's value, never redefine the destination
        // host or smuggle extra query parameters via '&'/'='.
        URI url = org.springframework.web.util.UriComponentsBuilder
                .fromHttpUrl(managementNodeBaseUrl + OCSP_PATH)
                .queryParam("clientId", clientId)
                .build()
                .toUri();
        String token = idpTokenService.fetchToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("OCSP endpoint returned HTTP {} for clientId={}",
                    response.statusCode(), clientId);
            return OcspStatus.NOT_FOUND;
        }

        JsonNode json   = objectMapper.readTree(response.body());
        String   status = json.has("status") ? json.get("status").asText() : "";
        return switch (status) {
            case "ACTIVE"   -> OcspStatus.ACTIVE;
            case "REVOKED"  -> OcspStatus.REVOKED;
            case "EXPIRED"  -> OcspStatus.EXPIRED;
            default         -> OcspStatus.NOT_FOUND;
        };
    }

    /**
     * Protected for testing — allows injection of a mock HttpClient.
     * In production, returns a real HttpClient with mTLS truststore.
     */
    protected java.net.http.HttpClient buildHttpClient(java.util.Properties props) {
        try {
            javax.net.ssl.SSLContext sslCtx;
            // Same switch HttpClientFactoryUtils.createHttpClientWithMtls() uses:
            // when vault.tls.enabled=true there is no truststore file on disk —
            // the cert manager's material lives only in Vault.
            if (org.dsi.dpn.common.service.secret.VaultTlsSupport.isVaultTlsEnabled()) {
                sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
                sslCtx.init(null, org.dsi.dpn.common.service.secret.VaultTlsSupport.trustManagers(), null);
            } else {
                sslCtx = org.dsi.dpn.common.utils.SSLUtils
                        .createSSLContextWithTrustStore(
                                props.getProperty("idp.truststore.path"),
                                props.getProperty("idp.truststore.password"));
            }
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslCtx)
                    .build();
        } catch (Exception e) {
            throw new org.dsi.dpn.common.exception.FederatorSslException("Failed to build OCSP HttpClient", e);
        }
    }
}