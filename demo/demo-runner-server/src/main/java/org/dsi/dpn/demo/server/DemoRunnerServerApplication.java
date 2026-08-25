// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demo Runner Server — EXAMPLE backend service, deployed separately from the
 * REST Federator gateway.
 *
 * <p>Exposes a subset of the MHHS FMAR specification (asset query and
 * registration) plus Swagger UI at {@code /swagger-ui.html}. Authenticates
 * callers with a shared API key; it has no knowledge of the DPN's mTLS/JWT
 * trust chain, which the gateway handles before forwarding here.
 */
@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "Demo Runner Server — FMAR Assets",
        version = "0.5.0-draft",
        description = "Example backend implementing a subset of the MHHS FMAR "
                + "specification (GET /assets, POST /fsp/{fspId}/assets). "
                + "Illustrative only — data is held in memory."))
public class DemoRunnerServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoRunnerServerApplication.class, args);
    }
}
