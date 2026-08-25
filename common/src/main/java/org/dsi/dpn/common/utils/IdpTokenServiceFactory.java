// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted and maintained
// for the DSI REST Federator.
package org.dsi.dpn.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.Properties;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.dsi.dpn.common.service.idp.IdpTokenServiceClientSecretImpl;
import org.dsi.dpn.common.service.idp.IdpTokenServiceMtlsImpl;
import org.dsi.dpn.common.service.idp.IdpTokenServicePrivateJwtImpl;
import org.dsi.dpn.common.service.secret.SecretProvider;

/**
 * Builds the {@link IdpTokenService} matching the configured client-authentication
 * mode.
 *
 * <p>Trimmed while vendoring: the original also carried gRPC channel-credential
 * and file-checksum helpers, which the REST stack does not use. Dropping them
 * removes the gRPC dependency entirely.
 */
public class IdpTokenServiceFactory {
    public static final String COMMON_CONFIG_PROPERTIES = "common.configuration";

    /**
     * Controls which client-authentication strategy is used when talking to Keycloak.
     * Accepted values (case-insensitive):
     * <ul>
     *   <li>{@code private_key_jwt} — RFC 7523 signed JWT assertion (recommended)</li>
     *   <li>{@code mtls}            — mutual-TLS certificate + client secret (legacy)</li>
     *   <li>{@code client_secret}   — plain client_secret, no mTLS (default fallback)</li>
     * </ul>
     */
    private static final String IDP_AUTH_MODE_PROPERTY = "idp.auth.mode";

    /** Kept for backward compatibility; ignored when {@code idp.auth.mode} is set. */
    private static final String IDP_MTLS_ENABLED_PROPERTY = "idp.mtls.enabled";

    private static final Logger LOGGER = LoggerFactory.getLogger("IdpTokenServiceFactory");

    private IdpTokenServiceFactory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static IdpTokenService createIdpTokenService() {

        Properties properties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);

        ObjectMapper mapper = ObjectMapperUtil.getInstance();

        SecretProvider secretProvider = PropertyUtil.createSecretProvider(properties);
        PropertyUtil.overrideWithSecrets(properties, secretProvider);

        // Prefer the explicit idp.auth.mode property; fall back to the legacy
        // idp.mtls.enabled boolean for backward compatibility.
        String authMode =
                properties.getProperty(IDP_AUTH_MODE_PROPERTY, "").trim().toLowerCase();
        if (authMode.isEmpty()) {
            boolean mtlsLegacy = Boolean.parseBoolean(properties.getProperty(IDP_MTLS_ENABLED_PROPERTY, "false"));
            authMode = mtlsLegacy ? "mtls" : "client_secret";
        }

        LOGGER.info("IDP authentication mode: '{}'", authMode);

        return switch (authMode) {
            case "private_key_jwt" -> {
                // Supplier defers HttpClient creation to each fetchToken() call so that
                // rotated mTLS certificates are always picked up without a process restart.
                Supplier<HttpClient> clientSupplier = () -> HttpClientFactoryUtils.createHttpClientWithMtls(properties);
                yield new IdpTokenServicePrivateJwtImpl(clientSupplier, mapper, properties);
            }
            case "mtls" -> {
                LOGGER.warn("===========Idp mTLS enabled (legacy mode)============");
                Supplier<HttpClient> clientSupplier = () -> HttpClientFactoryUtils.createHttpClientWithMtls(properties);
                yield new IdpTokenServiceMtlsImpl(clientSupplier, mapper);
            }
            default -> {
                // "client_secret" or any unrecognised value
                if (!"client_secret".equals(authMode)) {
                    LOGGER.warn("Unknown idp.auth.mode '{}', defaulting to client_secret", authMode);
                }
                Supplier<HttpClient> clientSupplier = () -> HttpClientFactoryUtils.createHttpClient(properties);
                yield new IdpTokenServiceClientSecretImpl(clientSupplier, mapper);
            }
        };
    }
}
