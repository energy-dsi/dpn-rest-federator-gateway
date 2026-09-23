// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.common.utils;

import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * Master switch controlling ONLY whether Redis access is gated behind a valid Keycloak session
 * (see {@code KeycloakSessionGuard}), via {@code RedisUtil.getKeycloakGatedInstance()}.
 * <p>
 * Ported from {@code dpn-federator-gateway}'s {@code KeycloakAuthConfig} (same class name, same
 * behaviour), so the REST Federator Gateway's switch behaves identically to the gRPC Federator
 * Gateway's.
 * </p>
 * <p>
 * <strong>Redis TLS is always implemented, in both switch states.</strong> Redis wire-level
 * TLS (see {@code RedisUtil.getInstance()}) is controlled solely by {@code redis.tls.enabled}
 * and is completely independent of this flag — turning this switch off never disables TLS. (The
 * source project's {@code common-configuration.properties} comment block describes an older,
 * different behaviour where this flag also forced TLS off; that comment does not match either
 * project's actual {@code RedisUtil}/{@code KeycloakAuthConfig} code and should not be relied
 * on — this Javadoc describes the current, real behaviour.)
 * </p>
 * <p>
 * Reads {@code keycloak.auth.enabled} from common-configuration.properties. Defaults to TRUE
 * (secure by default) if the property is absent or blank, and ALSO defaults to TRUE if the
 * properties file itself cannot be read at all - this is a deliberate fail-secure choice: a
 * configuration problem must never silently disable authentication.
 * </p>
 * <p>
 * Intended for local development testing only (e.g. to isolate whether a failure is caused by
 * Keycloak enforcement itself, versus something unrelated). This should never be set to
 * {@code false} outside that context.
 * </p>
 */
@Slf4j
public final class KeycloakAuthConfig {

    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    private static final String KEYCLOAK_AUTH_ENABLED = "keycloak.auth.enabled";
    private static final boolean DEFAULT_ENABLED = true;

    private KeycloakAuthConfig() {}

    /**
     * @return {@code true} if Keycloak authentication should be enforced (the default, and the
     *         fail-secure outcome of any error), {@code false} only if explicitly and
     *         successfully configured otherwise.
     */
    public static boolean isEnabled() {
        try {
            Properties properties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
            String value = properties.getProperty(KEYCLOAK_AUTH_ENABLED);
            if (value == null || value.isBlank()) {
                return DEFAULT_ENABLED;
            }
            boolean enabled = Boolean.parseBoolean(value.trim());
            if (!enabled) {
                log.warn(
                        "keycloak.auth.enabled=false - Redis access will NOT require a Keycloak session. "
                                + "Redis TLS is unaffected and remains governed solely by redis.tls.enabled. "
                                + "This should never be set to false outside local development testing.");
            }
            return enabled;
        } catch (Exception e) {
            log.warn(
                    "Unable to read keycloak.auth.enabled - failing secure, treating as enabled (true). Cause: {}",
                    e.getMessage());
            return DEFAULT_ENABLED;
        }
    }
}
