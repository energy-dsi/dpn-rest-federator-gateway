// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * Publishes the federator's common-configuration values into the Spring
 * Environment before any bean is created.
 *
 * <p>The gateway's TLS and JWT settings live in the federator's
 * {@code common.configuration} file — the same file the gRPC Federator stack
 * reads — so keystore paths, passwords and the JWKS URL are defined in exactly
 * one place. {@code application.properties} then references them as
 * placeholders:
 *
 * <pre>
 *   spring.ssl.bundle.jks.federator-tls.keystore.location=${idp.keystore.path}
 *   spring.security.oauth2.resourceserver.jwt.jwk-set-uri=${idp.jwks.url}
 * </pre>
 *
 * <p>Those placeholders are resolved while the Environment is being prepared,
 * long before {@code @Configuration} classes run — which is why this is an
 * {@link EnvironmentPostProcessor} registered in {@code META-INF/spring.factories}
 * rather than a bean. Rotate a password in the federator file and both the HTTPS
 * connector and the federator's own mTLS clients follow.
 *
 * <p>Values already present in the Environment (an OS environment variable, a
 * {@code -D} system property, a command-line argument) take precedence: this
 * source is appended last, so it fills gaps rather than overriding deliberate
 * overrides.
 *
 * <p>A missing or unreadable configuration file is not fatal here. Failing at
 * this point yields an opaque startup error; letting startup continue means the
 * unresolved placeholder is reported against the property that actually needs
 * it. {@link RestFederatorServerConfig} separately fails fast if PropertyUtil
 * cannot be initialised at all.
 */
public class FederatorCommonConfigEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "federatorCommonConfiguration";
    private static final String COMMON_CONFIG = "common.configuration";

    /**
     * Properties copied into the Environment. Deliberately an explicit list: the
     * federator configuration also holds credentials this application has no use
     * for, and publishing the whole file would widen their exposure.
     */
    private static final String[] PUBLISHED_PROPERTIES = {
            "idp.keystore.path",
            "idp.keystore.password",
            "idp.truststore.path",
            "idp.truststore.password",
            "idp.jwks.url",
            "idp.client.id"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        Properties commonProps = loadCommonConfiguration();
        if (commonProps == null) return;

        Map<String, Object> published = new HashMap<>();
        for (String key : PUBLISHED_PROPERTIES) {
            String value = commonProps.getProperty(key);
            if (value != null && !value.isBlank()) {
                published.put(key, value);
            }
        }

        if (published.isEmpty()) {
            log("No federator common-configuration properties found to publish");
            return;
        }

        // addLast: anything already in the Environment wins.
        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, published));

        log("Published " + published.size()
                + " federator common-configuration properties into the Environment");
    }

    private Properties loadCommonConfiguration() {
        try {
            // Populates PropertyUtil from the server properties file named by
            // FEDERATOR_SERVER_PROPERTIES, which in turn names the
            // common.configuration file.
            PropertyUtil.initializeProperties();
            return PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG);
        } catch (Exception e) {
            log("Could not read federator common configuration ("
                    + e.getMessage() + "). Placeholders such as ${idp.keystore.path} "
                    + "must be supplied another way, e.g. as environment variables.");
            return null;
        }
    }

    /**
     * Writes to stdout rather than SLF4J: EnvironmentPostProcessors run before
     * the logging system is initialised, so a logger here would either be
     * silently dropped or force premature logging initialisation.
     */
    private void log(String message) {
        System.out.println("[FederatorCommonConfig] " + message);
    }

    @Override
    public int getOrder() {
        // After Boot's own config-data processing so application.properties has
        // been loaded, but before anything resolves the placeholders.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
