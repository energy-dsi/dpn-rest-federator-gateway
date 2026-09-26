// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.utils;

import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs a single, consolidated, human-readable summary of this application's configured security
 * posture for Redis and OTLP telemetry export - intended to be pointed at directly (e.g. by an
 * architect/reviewer skimming a pod's log) as one-stop evidence of what's enabled, rather than
 * having to piece the picture together from scattered lines logged by {@code RedisUtil},
 * {@code OtelBearerTokenSupport}, {@code OtlpTruststoreSupport}, etc. at various points during
 * startup.
 *
 * <p>Purely descriptive of CONFIGURATION (what's turned on and pointed where) - it does not
 * itself prove any of it actually works. For that, see the runtime confirmations already logged
 * elsewhere: {@code RedisUtil}'s "Redis connection verified" PING result, and
 * {@code OtlpExportSuccessLogger}'s "OTLP ... export ... succeeded" line on first successful
 * export.
 *
 * <p>Call {@link #logSummary(String)} once, after {@code PropertyUtil.initializeProperties()}
 * has run, from each application's startup path (e.g. a {@code @PostConstruct} bean or the tail
 * of a {@code @Configuration} class's bean wiring).
 */
public final class SecurityPostureLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("SecurityPostureLogger");
    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";

    private SecurityPostureLogger() {}

    public static void logSummary(String componentName) {
        Properties commonProperties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);

        String redisHost = PropertyUtil.getPropertyValue(RedisUtil.REDIS_HOST, RedisUtil.LOCALHOST);
        String redisPort = String.valueOf(
                PropertyUtil.getPropertyIntValue(RedisUtil.REDIS_PORT, RedisUtil.DEFAULT_PORT));
        boolean redisTlsEnabled =
                Boolean.parseBoolean(commonProperties.getProperty(RedisUtil.REDIS_TLS_ENABLED, RedisUtil.TRUE));
        String redisTruststorePath = commonProperties.getProperty(RedisUtil.REDIS_TRUSTSTORE_PATH, "");
        boolean keycloakGatingEnabled = KeycloakAuthConfig.isEnabled();
        String redisIdpClientId = commonProperties.getProperty("redis.idp.client.id", "");
        String redisIdpTokenUrl = commonProperties.getProperty("redis.idp.token.url", "");

        String otelEndpoint = envOrBlank("OTEL_EXPORTER_OTLP_ENDPOINT");
        String otelProtocol = envOrBlank("OTEL_EXPORTER_OTLP_PROTOCOL");
        boolean otelHttpsTrustEnabled = !envOrBlank("OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH").isBlank();
        String otelTruststorePath = envOrBlank("OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH");
        boolean otelOauthEnabled = Boolean.parseBoolean(envOrBlank("OTEL_EXPORTER_OTLP_OAUTH_ENABLED"));

        LOGGER.info("==================== Security Posture Summary [{}] ====================", componentName);
        LOGGER.info("Redis:");
        LOGGER.info("  Host:Port          : {}:{}", redisHost, redisPort);
        LOGGER.info("  TLS enabled        : {}", redisTlsEnabled);
        if (redisTlsEnabled) {
            LOGGER.info("  TLS truststore     : {}", redisTruststorePath);
        }
        LOGGER.info("  Keycloak gating    : {}", keycloakGatingEnabled);
        if (keycloakGatingEnabled) {
            LOGGER.info("  Keycloak client id : {}", redisIdpClientId);
            LOGGER.info("  Keycloak token url : {}", redisIdpTokenUrl);
        }
        LOGGER.info("OTLP Telemetry Export:");
        LOGGER.info("  Endpoint           : {}", otelEndpoint.isBlank() ? "(not configured - noop)" : otelEndpoint);
        LOGGER.info("  Protocol           : {}", otelProtocol.isBlank() ? "(default)" : otelProtocol);
        LOGGER.info("  HTTPS trust        : {}", otelHttpsTrustEnabled);
        if (otelHttpsTrustEnabled) {
            LOGGER.info("  HTTPS truststore   : {}", otelTruststorePath);
        }
        LOGGER.info("  OAuth2 Bearer auth : {}", otelOauthEnabled);
        LOGGER.info(
                "See 'Redis connection verified' and 'OTLP ... export ... succeeded' log lines "
                        + "for RUNTIME confirmation that the above is actually working, not just configured.");
        LOGGER.info("=========================================================================");
    }

    private static String envOrBlank(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
