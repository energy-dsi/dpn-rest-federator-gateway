// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026.
package org.dsi.dpn.federator.rest.framework.client.ocsp;

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
import uk.gov.dbt.ndtp.federator.common.service.idp.IdpTokenService;
import uk.gov.dbt.ndtp.federator.common.utils.PropertyUtil;
import uk.gov.dbt.ndtp.federator.common.utils.SSLUtils;

/**
 * FRAMEWORK — client side. Do not modify.
 *
 * Calls GET /api/v1/certificate/ocsp?clientId={producerClientId} on Management Node
 * before making any outbound REST call to the producer.
 *
 * This is the client-side equivalent of OcspServerInterceptor in the gRPC Federator,
 * but corrected — passes the actual producer clientId rather than empty string.
 *
 * Uses truststore-only SSL context (we verify Management Node TLS, no client cert
 * needed for this specific call).
 *
 * Properties read from common.configuration:
 *   management.node.base.url   — Management Node base URL
 *   idp.truststore.path        — JKS for verifying Management Node TLS cert
 *   idp.truststore.password
 */
@Slf4j
public class OcspClientVerificationServiceImpl implements OcspClientVerificationService {

    private static final String OCSP_PATH         = "/api/v1/certificate/ocsp?clientId=";
    private static final String MN_BASE_URL_PROP  = "management.node.base.url";

    // OTEL MDC keys
    private static final String MDC_CLIENT_ID = "dpn.certificate.client_id";
    private static final String MDC_TIMESTAMP = "dpn.certificate.verification_timestamp";
    private static final String MDC_STATUS    = "dpn.certificate.verification_status";

    private final String          managementNodeBaseUrl;
    private final HttpClient      httpClient;
    private final IdpTokenService idpTokenService;
    private final ObjectMapper    objectMapper;

    public OcspClientVerificationServiceImpl(Properties commonProps,
                                             IdpTokenService idpTokenService) {
        this.managementNodeBaseUrl = resolveManagementNodeBaseUrl(commonProps);
        this.idpTokenService       = idpTokenService;
        this.objectMapper          = new ObjectMapper();

        this.httpClient = buildHttpClient(commonProps);

        log.info("OcspClientVerificationServiceImpl initialised [mnBaseUrl={}]",
                managementNodeBaseUrl);
    }

    /**
     * Resolves the Management Node base URL, preferring the injected properties
     * and falling back to the static {@link PropertyUtil}.
     *
     * <p>{@code management.node.base.url} is declared in {@code server.properties} /
     * {@code client.properties}, not in any {@code common-configuration*.properties},
     * so in production the injected common properties do not carry it and the
     * PropertyUtil fallback is what supplies the value.
     *
     * <p>Reading the constructor parameter first restores this class's stated
     * contract — it accepts a {@code Properties} and should honour it — and means
     * a caller that already has the value, such as a unit test, does not have to
     * initialise the static PropertyUtil singleton just to construct the service.
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
    public OcspStatus verify(String producerClientId) {
        Instant timestamp = Instant.now();
        OcspStatus status = OcspStatus.NOT_FOUND;
        try {
            status = checkStatus(producerClientId);
        } catch (Exception e) {
            log.warn("Client OCSP check failed for producerClientId={}: {}",
                    producerClientId, e.getMessage());
        } finally {
            logOtel(producerClientId, timestamp, status);
        }
        return status;
    }

    private OcspStatus checkStatus(String producerClientId) throws Exception {
        String url   = managementNodeBaseUrl + OCSP_PATH + producerClientId;
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
            log.warn("OCSP endpoint returned HTTP {} for producerClientId={}",
                    response.statusCode(), producerClientId);
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

    private void logOtel(String clientId, Instant timestamp, OcspStatus status) {
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_TIMESTAMP, timestamp.toString());
            MDC.put(MDC_STATUS,    status.name());
            if (status == OcspStatus.ACTIVE) {
                log.info("Client OCSP verification: status={} producerClientId={} timestamp={}",
                        status, clientId, timestamp);
            } else {
                log.error("Outbound call blocked — OCSP: status={} producerClientId={} timestamp={}",
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
        javax.net.ssl.SSLContext sslCtx = uk.gov.dbt.ndtp.federator.common.utils.SSLUtils
                .createSSLContext(
                        props.getProperty("idp.keystore.path"),
                        props.getProperty("idp.keystore.password"),
                        props.getProperty("idp.truststore.path"),
                        props.getProperty("idp.truststore.password"));
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .sslContext(sslCtx)
                .build();
    }
}