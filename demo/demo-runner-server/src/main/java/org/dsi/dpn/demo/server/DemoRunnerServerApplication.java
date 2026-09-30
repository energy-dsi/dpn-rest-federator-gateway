// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Demo Runner Server - EXAMPLE backend service, deployed separately from the
 * REST Federator gateway.
 *
 * <p>Exposes a subset of the MHHS FMAR specification (asset query and
 * registration) plus Swagger UI at {@code /swagger-ui.html}. Authenticates
 * callers with a shared API key; it has no knowledge of the DPN's mTLS/JWT
 * trust chain, which the gateway handles before forwarding here.
 *
 * <p><b>TLS:</b> this backend stands in for a participant's own data service and
 * does NOT talk to the DPN Vault. Its inbound HTTPS listener terminates with the
 * {@code keystore.jks} from the mounted {@code dpn-tls} platform secret (a plain
 * {@code spring.ssl.bundle.jks.demo-runner-tls.*} bundle in application.properties).
 * The gateway trusts that certificate with the same DPN truststore it uses to
 * trust Vault's HTTPS calls — so, from the gateway's point of view, calling this
 * backend works exactly like calling the Vault service.
 */
@Slf4j
@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "Demo Runner Server - FMAR Assets",
        version = "0.5.0-draft",
        description = "Example backend implementing a subset of the MHHS FMAR "
                + "specification (GET /assets, POST /fsp/{fspId}/assets). "
                + "Illustrative only - data is held in memory."))
public class DemoRunnerServerApplication {
    public static void main(String[] args) {
        OpenTelemetryConfig.initialize();
        // Loads FEDERATOR_SERVER_PROPERTIES (server.properties/common-configuration.properties,
        // rendered by demo-runner-server-config.yaml) so redis.idp.* is available for
        // KeycloakSessionGuard/IdpTokenServiceFactory.createRedisIdpTokenService() - used for
        // Redis-gating and, when otel.oauth2.enabled, OTLP export auth (OtelBearerTokenSupport).
        // Logged rather than fatal: unlike the gateway, this app still serves its own API
        // without it - only Redis-gating and OTLP OAuth are affected.
        if (!PropertyUtil.initializeProperties()) {
            log.warn("Failed to initialise PropertyUtil from FEDERATOR_SERVER_PROPERTIES - "
                    + "Redis-gating and OTLP OAuth2 export will not work");
        }
        SpringApplication.run(DemoRunnerServerApplication.class, args);
    }
}
