// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Retains PropertyUtil initialisation for demo-runner-server. This app's inbound
 * HTTPS listener is sourced from Vault by {@link org.dsi.dpn.common.service.secret.VaultFileBasedSslBundleInitializer},
 * called from {@code DemoRunnerServerApplication.main()} before Spring starts -
 * not by any bean in this class. The in-memory {@code SslBundleRegistrar}
 * approach previously used here is confirmed (via a real TLS handshake test,
 * {@code openssl s_client}) not to work correctly under this project's current
 * Spring Boot version; see the disabled beans below for that history.
 *
 * <p>Server-only TLS: no {@code server.ssl.client-auth} is set, so this backend
 * doesn't require or validate a client certificate from the gateway - only the
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
        // first - regardless of when Spring actually invokes the bean method.
        if (!PropertyUtil.initializeProperties()) {
            throw new IllegalStateException(
                    "demo-runner-server: failed to initialise PropertyUtil. "
                            + "Set FEDERATOR_SERVER_PROPERTIES env var to the path of server.properties.");
        }
        log.info("PropertyUtil initialised for demo-runner-server");
    }

    // -- PERMANENTLY DISABLED - kept as a record, not a "test" ---------------
    // Confirmed via a real TLS handshake test (openssl s_client) that the
    // in-memory SslBundleRegistrar approach serves the wrong certificate under
    // this project's current Spring Boot version. VaultFileBasedSslBundleInitializer,
    // called from DemoRunnerServerApplication.main(), is the actual, working fix.
    // Having both the registrar AND file-based properties active for the same
    // bundle name would conflict - see the original application.properties
    // comment on exactly this. Kept here, commented, only in case that
    // connector-wiring defect is fixed in a future Spring Boot version and this
    // simpler approach can be restored.
    //
    // @Bean
    // public static SslBundleRegistrar vaultSslBundleRegistrar() {
    //     return new VaultSslBundleRegistrar("demo-runner-tls");
    // }
    //
    // @Bean
    // public static org.springframework.beans.factory.config.BeanFactoryPostProcessor tomcatSslOrderingFix() {
    //     ...
    // }
    //
    // @Bean
    // public org.springframework.boot.web.server.WebServerFactoryCustomizer<
    //         org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory> vaultSslProgrammaticCustomizer() {
    //     ...
    // }
}
