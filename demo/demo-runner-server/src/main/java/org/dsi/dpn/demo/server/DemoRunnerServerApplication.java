// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.dsi.dpn.common.service.secret.VaultFileBasedSslBundleInitializer;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;

/**
 * Demo Runner Server - EXAMPLE backend service, deployed separately from the
 * REST Federator gateway.
 *
 * <p>Exposes a subset of the MHHS FMAR specification (asset query and
 * registration) plus Swagger UI at {@code /swagger-ui.html}. Authenticates
 * callers with a shared API key; it has no knowledge of the DPN's mTLS/JWT
 * trust chain, which the gateway handles before forwarding here.
 */
@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "Demo Runner Server - FMAR Assets",
        version = "0.5.0-draft",
        description = "Example backend implementing a subset of the MHHS FMAR "
                + "specification (GET /assets, POST /fsp/{fspId}/assets). "
                + "Illustrative only - data is held in memory."))
public class DemoRunnerServerApplication {
    public static void main(String[] args) throws Exception {
        OpenTelemetryConfig.initialize();

        // Sources this app's inbound HTTPS listener from Vault via a real,
        // file-based SSL bundle - see VaultFileBasedSslBundleInitializer's
        // own javadoc for why the file-based approach is used instead of the
        // in-memory SslBundleRegistrar this project used previously. Must run
        // before SpringApplication.run() so the properties it sets are already
        // present in the Environment when Spring resolves server.ssl.bundle.
        VaultFileBasedSslBundleInitializer.initialise("demo-runner-tls");

        SpringApplication.run(DemoRunnerServerApplication.class, args);
    }
}
