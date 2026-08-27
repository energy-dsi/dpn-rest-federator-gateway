// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.ssl.SslBundleRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.dsi.dpn.common.service.secret.VaultSslBundleRegistrar;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Sources this demo backend's own inbound HTTPS listener from Vault — the same
 * certificate the gateway uses, via the same {@link VaultSslBundleRegistrar} the
 * gateway's own config wires in. Demonstrates that a second Spring Boot service can
 * reuse the DPN's shared Vault identity, exactly as the gRPC federator's real
 * components each did when calling Vault over HTTPS.
 *
 * <p>Server-only TLS: no {@code server.ssl.client-auth} is set, so this backend
 * doesn't require or validate a client certificate from the gateway — only the
 * shared API key ({@code ApiKeyAuthFilter}) authenticates that hop.
 */
@Configuration
@Slf4j
public class DemoRunnerServerConfig {

    static {
        // Same pattern as RestFederatorServerConfig: PropertyUtil must be
        // initialised before vaultSslBundleRegistrar() runs (SSL bundle
        // registration happens very early in Spring Boot's startup, before most
        // other @Configuration classes are fully processed). Declaring this
        // static block in the SAME class as the @Bean method below guarantees,
        // via ordinary Java class-initialisation ordering, that it always runs
        // first — regardless of when Spring actually invokes the bean method.
        if (!PropertyUtil.initializeProperties()) {
            throw new IllegalStateException(
                    "demo-runner-server: failed to initialise PropertyUtil. "
                            + "Set FEDERATOR_SERVER_PROPERTIES env var to the path of server.properties.");
        }
        log.info("PropertyUtil initialised for demo-runner-server");
    }

    @Bean
    public SslBundleRegistrar vaultSslBundleRegistrar() {
        return new VaultSslBundleRegistrar("demo-runner-tls");
    }
}
