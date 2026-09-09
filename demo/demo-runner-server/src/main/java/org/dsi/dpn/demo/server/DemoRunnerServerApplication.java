// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;

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
        SpringApplication.run(DemoRunnerServerApplication.class, args);
    }
}
