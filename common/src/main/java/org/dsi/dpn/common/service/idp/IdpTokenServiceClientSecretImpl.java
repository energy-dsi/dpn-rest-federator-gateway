// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced,
// and maintained by the National Digital Twin Programme.
package org.dsi.dpn.common.service.idp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.dsi.dpn.common.utils.PropertyUtil;
import org.dsi.dpn.common.utils.RedisUtil;
import org.dsi.dpn.common.exception.FederatorTokenException;

/**
 * Implementation of IdpTokenService that fetches tokens from an Identity Provider (IDP)
 * using client secret authentication.
 * <p>
 * Caches fetched tokens in Redis, matching {@code IdpTokenServicePrivateJwtImpl}'s and
 * {@code IdpTokenServiceMtlsImpl}'s existing pattern - this is required because
 * {@code idp.auth.mode=client_secret} is the mode actually used in this deployment;
 * without caching here, {@code client_secret} mode would never exercise Redis at all,
 * unlike the other two auth modes. Always uses {@link RedisUtil#getInstance()} (the
 * ungated accessor), never {@code getKeycloakGatedInstance()} - caching a token is
 * exactly the scenario that accessor exists to avoid gating, since this same class is
 * also used to fetch the "redis.idp." token that {@code KeycloakSessionGuard} itself
 * relies on (see {@code IdpTokenServiceFactory.createRedisIdpTokenService()}); gating
 * that fetch would be circular.
 * </p>
 */
@Slf4j
public class IdpTokenServiceClientSecretImpl extends AbstractIdpTokenService {

    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    private final String idpTokenUrl;
    private final String idpTokenUrlPropertyName;
    private final String idpClientId;
    private final String idpClientSecret;

    /**
     * The property prefix this instance was built with (e.g. "idp." or "redis.idp.").
     * Included in the Redis cache key so the main IDP client and the dedicated
     * Redis-gating client - both built from this same class, with different prefixes -
     * never collide on the same cached token.
     */
    private final String propertyPrefix;

    /**
     * Existing constructor - unchanged behaviour, always reads the {@code idp.} prefix.
     * Delegates to {@link #IdpTokenServiceClientSecretImpl(Supplier, ObjectMapper, String)}.
     */
    public IdpTokenServiceClientSecretImpl(
            Supplier<java.net.http.HttpClient> httpClientSupplier, ObjectMapper objectMapper) {
        this(httpClientSupplier, objectMapper, "idp.");
    }

    /**
     * Prefix-aware constructor. Added so this same implementation can be reused for a
     * dedicated Redis-gating Keycloak client (prefix {@code "redis.idp."}), completely
     * separate from the main IDP client (prefix {@code "idp."}), matching the sibling
     * gRPC federator's identical pattern. Both prefixes read from the same
     * common-configuration.properties file, distinguished only by the prefix - no second
     * properties file is used.
     *
     * @param propertyPrefix the properties-file key prefix to read from, e.g. {@code "idp."}
     *                       or {@code "redis.idp."}. Must end in a trailing dot.
     */
    public IdpTokenServiceClientSecretImpl(
            Supplier<java.net.http.HttpClient> httpClientSupplier, ObjectMapper objectMapper, String propertyPrefix) {
        super(
                PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES).getProperty(propertyPrefix + "jwks.url"),
                httpClientSupplier,
                objectMapper);
        Properties properties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);
        this.propertyPrefix = propertyPrefix;
        this.idpTokenUrlPropertyName = propertyPrefix + "token.url";
        this.idpTokenUrl = properties.getProperty(idpTokenUrlPropertyName);
        requireHttps(idpTokenUrlPropertyName, idpTokenUrl);
        this.idpClientId = properties.getProperty(propertyPrefix + "client.id");

        // Falls back to a JVM system property when the file-based properties don't have
        // this value (deliberately the case in the deployed ConfigMap - see
        // rest-federator-server-config.yaml - since ConfigMaps are not encrypted, unlike
        // Secrets). PropertyUtil.getPropertiesFromFilePath() builds a plain
        // java.util.Properties with no defaults, so it never merges JVM system
        // properties on its own; this fallback is what actually makes the
        // -Dredis.idp.client.secret=... / -Didp.client.secret=... JVM flag (set via
        // JAVA_TOOL_OPTIONS in the Helm deployment template, sourced from a Kubernetes
        // Secret) reach this field.
        String fileValue = properties.getProperty(propertyPrefix + "client.secret");
        this.idpClientSecret = (fileValue != null && !fileValue.isBlank())
                ? fileValue
                : System.getProperty(propertyPrefix + "client.secret");
    }

    /**
     * Fetches an access token using client credentials.
     *
     * @return The access token as a String
     * @throws FederatorTokenException if there is an error fetching the token
     */
    @Override
    public String fetchToken() {
        return fetchTokenWithResilience();
    }

    /**
     * Fetches an access token for the specified management node ID using client credentials.
     *
     * @param managementNodeId The management node identifier (can be null for default)
     * @return The access token as a String
     * @throws FederatorTokenException if there is an error fetching the token
     */
    @Override
    public String fetchToken(String managementNodeId) {
        log.trace("Fetching token for management node {}", managementNodeId);
        return fetchTokenInternal();
    }

    private String fetchTokenInternal() {
        try {
            String cachedToken = getTokenFromCacheOrNull();
            if (cachedToken != null) {
                return cachedToken;
            }
            log.debug("No cached token in Redis for prefix '{}', requesting a new one", propertyPrefix);

            // Re-validated here (not just in the constructor) so the check is visible
            // in the same method that sends idpClientSecret - SAST tools that flag
            // CWE-319 based on per-method taint analysis do not otherwise see that
            // this value can only ever be sent over https://.
            requireHttps(idpTokenUrlPropertyName, idpTokenUrl);

            String body = GRANT_TYPE
                    + EQUALS_SIGN
                    + CLIENT_CREDENTIALS
                    + AMPERSAND
                    + CLIENT_ID
                    + EQUALS_SIGN
                    + idpClientId
                    + AMPERSAND
                    + CLIENT_SECRET
                    + EQUALS_SIGN
                    + idpClientSecret;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(idpTokenUrl))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_FORM_URLENCODED)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    httpClientSupplier.get().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new FederatorTokenException(
                        String.format("Failed to fetch token: HTTP %d - %s", response.statusCode(), response.body()));
            }
            Map<String, Object> json =
                    objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            var accessToken = (String) json.get(ACCESS_TOKEN);
            log.info("Access token fetched successfully, persisting to Redis (prefix '{}')", propertyPrefix);

            Object expiresInRaw = json.get("expires_in");
            if (expiresInRaw instanceof Number number) {
                persistTokenInCache(accessToken, number.longValue());
            } else {
                log.warn("Token response for prefix '{}' had no numeric 'expires_in' - "
                        + "not caching this token in Redis", propertyPrefix);
            }
            return accessToken;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FederatorTokenException("Thread interrupted while fetching token from IDP", e);
        } catch (Exception e) {
            throw new FederatorTokenException("Error fetching token from IDP", e);
        }
    }

    // -----------------------------------------------------------------------
    // Redis cache helpers
    // -----------------------------------------------------------------------

    /**
     * Always uses {@link RedisUtil#getInstance()} - the ungated accessor - never
     * {@code getKeycloakGatedInstance()}. See the class Javadoc for why: this class is
     * also used to fetch the "redis.idp." token that the Keycloak gate itself depends on,
     * so gating this specific read/write would be circular.
     */
    private String getTokenFromCacheOrNull() {
        String cachedToken = RedisUtil.getInstance().getValue(getRedisKey(), String.class, true);
        if (cachedToken != null) {
            log.debug("Using cached access token from Redis (prefix '{}')", propertyPrefix);
        }
        return cachedToken;
    }

    private void persistTokenInCache(String accessToken, long expiresIn) {
        RedisUtil.getInstance().setValue(getRedisKey(), accessToken, expiresIn);
    }

    /**
     * Qualified by propertyPrefix so the main "idp." client and the dedicated "redis.idp."
     * client - both built from this same class - never collide on the same cached token.
     */
    private String getRedisKey() {
        return "client_secret_" + propertyPrefix + "access_token";
    }
}
