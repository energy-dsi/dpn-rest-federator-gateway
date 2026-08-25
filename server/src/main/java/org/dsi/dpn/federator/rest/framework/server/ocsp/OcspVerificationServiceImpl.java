// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.federator.rest.framework.server.ocsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.dsi.dpn.common.service.idp.IdpTokenService;
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
    private static final String OCSP_PATH            = "/api/v1/certificate/ocsp?clientId=";

    // OTEL MDC keys — mirrors OtelCertificateVerificationLogger in gRPC Federator
    private static final String MDC_CLIENT_ID  = "dpn.certificate.client_id";
    private static final String MDC_TIMESTAMP  = "dpn.certificate.verification_timestamp";
    private static final String MDC_STATUS     = "dpn.certificate.verification_status";

    private final String          managementNodeBaseUrl;
    private final HttpClient      httpClient;
    private final IdpTokenService idpTokenService;
    private final ObjectMapper    objectMapper;

    public OcspVerificationServiceImpl(Properties commonProps,
                                       IdpTokenService idpTokenService) {
        this.managementNodeBaseUrl = commonProps.getProperty(MN_BASE_URL_PROP, "");
        this.idpTokenService = idpTokenService;
        this.objectMapper    = new ObjectMapper();

        this.httpClient = buildHttpClient(commonProps);

        log.info("OcspVerificationServiceImpl initialised [mnBaseUrl={}]",
                managementNodeBaseUrl);
    }

    @Override
    public OcspStatus verify(String clientId) {
        Instant timestamp = Instant.now();
        OcspStatus status = OcspStatus.NOT_FOUND;
        try {
            status = checkStatus(clientId);
        } catch (Exception e) {
            log.warn("OCSP check failed for clientId={}: {}", clientId, e.getMessage());
        } finally {
            logOtel(clientId, timestamp, status);
        }
        return status;
    }

    private OcspStatus checkStatus(String clientId) throws Exception {
        // Fix for gRPC bug: always pass the actual clientId, never empty string
        String url   = managementNodeBaseUrl + OCSP_PATH + clientId;
        String token = idpTokenService.fetchToken();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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

    /** OTEL-format structured log — mirrors OtelCertificateVerificationLogger. */
    private void logOtel(String clientId, Instant timestamp, OcspStatus status) {
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_TIMESTAMP, timestamp.toString());
            MDC.put(MDC_STATUS,    status.name());
            if (status == OcspStatus.ACTIVE) {
                log.info("OCSP verification: status={} clientId={} timestamp={}",
                        status, clientId, timestamp);
            } else {
                log.error("403 Forbidden — OCSP verification: status={} clientId={} timestamp={}",
                        status, clientId, timestamp);
            }
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_TIMESTAMP);
            MDC.remove(MDC_STATUS);
        }
    }

    /**
     * Protected for testing — allows injection of a mock HttpClient.
     * In production, returns a real HttpClient with mTLS truststore.
     */
    protected java.net.http.HttpClient buildHttpClient(java.util.Properties props) {
        javax.net.ssl.SSLContext sslCtx = org.dsi.dpn.common.utils.SSLUtils
                .createSSLContextWithTrustStore(
                        props.getProperty("idp.truststore.path"),
                        props.getProperty("idp.truststore.password"));
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslCtx)
                .build();
    }
}