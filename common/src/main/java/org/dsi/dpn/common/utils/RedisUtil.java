// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin
// Programme.

/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package org.dsi.dpn.common.utils;

import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.service.secret.SecretProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

/**
 * Utility class to interact with Redis.
 * <p>
 * This class is a singleton and should be used to interact with Redis.
 * It is used to set and get offsets for a given client and topic.
 * The class is thread-safe.
 * </p>
 * <p>
 * Ported from the sibling gRPC federator ({@code dpn-federator-gateway}) - same TLS
 * approach (truststore file + explicit hostname verification), same Keycloak-gating
 * concept via {@link #getKeycloakGatedInstance()}.
 * </p>
 * <p>
 * NOTE: Redis-native username/password (ACL) authentication is intentionally NOT used here,
 * matching the gRPC federator. Access control for gated call sites is enforced at the
 * application layer, by {@link #getKeycloakGatedInstance()} requiring a valid Keycloak
 * session ({@code KeycloakSessionGuard}) before returning a connection.
 * </p>
 * <p>
 * <b>Self-configuring guard - deliberate difference from the gRPC federator:</b> the gRPC
 * federator requires an explicit {@code KeycloakSessionGuard.configure(...)} call at each
 * application's startup (in {@code FederatorClient}/{@code ProducerConsumerConfigServiceFactory}).
 * In this project that equivalent wiring point ({@code RestFederatorServerConfig}) is marked
 * "FRAMEWORK - do not modify", so {@link #getKeycloakGatedInstance()} instead configures the
 * guard itself, lazily, on first call - see {@link #ensureKeycloakGuardConfigured()}. This
 * works identically for both the REST Federator Server and Client without needing any
 * change to a protected file, or separate wiring in each role.
 * </p>
 * <p>
 * <b>Currently unused by any call site in this project:</b> as of this port, Redis in this
 * codebase is only ever accessed by the IDP token cache itself
 * ({@code IdpTokenServiceMtlsImpl}, {@code IdpTokenServicePrivateJwtImpl}), which must use
 * {@link #getInstance()} (ungated) rather than {@link #getKeycloakGatedInstance()} - gating
 * the token cache's own Redis reads/writes would be circular, exactly as documented on
 * {@code KeycloakSessionGuard}. {@link #getKeycloakGatedInstance()} has no current caller;
 * it is provided so any future non-token-cache Redis consumer (mirroring the gRPC
 * federator's {@code GRPCTopicClient}/{@code GRPCFileClient} pattern) has a ready-made,
 * consistent gate to use from day one.
 * </p>
 */
public class RedisUtil {

    public static final String REDIS_HOST = "redis.host";
    public static final String REDIS_PORT = "redis.port";
    public static final String REDIS_KEY_PREFIX = "redis.prefix";
    public static final String REDIS_TLS_ENABLED = "redis.tls.enabled";
    public static final String REDIS_TRUSTSTORE_PATH = "redis.truststore.path";
    public static final String REDIS_TRUSTSTORE_PASSWORD = "redis.truststore.password";
    public static final String REDIS_AES_KEY = "redis.aes.key";
    public static final String LOCALHOST = "localhost";
    public static final String DEFAULT_PORT = "6379";

    /**
     * Same key used elsewhere (e.g. {@code IdpTokenServiceFactory}) to look up the path to
     * the shared common-configuration.properties file from the main properties file.
     * redis.tls.enabled / redis.truststore.path / redis.truststore.password /
     * keycloak.auth.enabled / redis.idp.* live there since they are identical for both
     * REST Federator Server and Client - see common-configuration.properties.
     */
    private static final String COMMON_CONFIG_PROPERTIES = "common.configuration";
    public static final String TRUE = "true";
    public static final Logger LOGGER = LoggerFactory.getLogger("RedisUtil");
    private static RedisUtil instance;
    private static volatile boolean keycloakGuardConfigured = false;
    private static String redisAesKeyValue;
    private final JedisPooled jedisPooled;

    /**
     * Constructor with existing Jedis pooled instance.
     *
     * @param jedisPooled Jedis pooled instance.
     */
    RedisUtil(JedisPooled jedisPooled) {
        this.jedisPooled = jedisPooled;
    }

    /**
     * Gets the singleton instance of RedisUtil. This is the UNGATED accessor - it does not
     * require a valid Keycloak session. Must be used by the IDP token cache
     * ({@code IdpTokenServiceMtlsImpl}, {@code IdpTokenServicePrivateJwtImpl}) to avoid a
     * circular dependency (checking the cache to avoid a Keycloak call would otherwise
     * itself require a Keycloak session). Any future non-token-cache Redis consumer should
     * use {@link #getKeycloakGatedInstance()} instead.
     *
     * @return the singleton instance of RedisUtil.
     */
    public static synchronized RedisUtil getInstance() {
        if (instance == null) {
            redisAesKeyValue = PropertyUtil.getPropertyValue(REDIS_AES_KEY, "");
            String host = PropertyUtil.getPropertyValue(REDIS_HOST, LOCALHOST);
            LOGGER.info("Using Redis Host - '{}'", host);
            int port = PropertyUtil.getPropertyIntValue(REDIS_PORT, DEFAULT_PORT);
            LOGGER.info("Using Redis on Port - '{}'", port);

            // redis.tls.enabled / redis.truststore.path / redis.truststore.password are
            // identical for REST Federator Server and Client, so they live once in
            // common-configuration.properties rather than being duplicated in each role's
            // main properties file.
            Properties commonProperties = PropertyUtil.getPropertiesFromFilePath(COMMON_CONFIG_PROPERTIES);

            // Applies the Vault-backed override for redis.truststore.password (see
            // PropertyUtil.VaultMappings.REDIS_TRUSTSTORE_PASSWORD), matching the identical
            // pattern IdpTokenServiceFactory already uses for its own properties. Without this
            // call, the literal value in common-configuration.properties would be used as-is in
            // every environment, with no real secret-injection path at all - unlike every other
            // keystore/truststore password in this codebase.
            SecretProvider secretProvider = PropertyUtil.createSecretProvider(commonProperties);
            PropertyUtil.overrideWithSecrets(commonProperties, secretProvider);

            boolean isTLSEnabled = Boolean.parseBoolean(commonProperties.getProperty(REDIS_TLS_ENABLED, TRUE));

            // Redis TLS is controlled SOLELY by redis.tls.enabled and is completely
            // independent of keycloak.auth.enabled - Redis TLS is always implemented
            // regardless of the switch's value. Only getKeycloakGatedInstance()'s
            // authentication step (the Keycloak session check) is affected by that switch,
            // not TLS. See KeycloakAuthConfig's Javadoc for the full, current scope of that
            // flag.
            LOGGER.info("Using TLS with Redis - '{}'", isTLSEnabled);
            LOGGER.info("Redis-native username/password authentication is disabled; "
                    + "access control for gated call sites is enforced by the Keycloak gate "
                    + "at the application layer.");

            String truststorePath = isTLSEnabled ? commonProperties.getProperty(REDIS_TRUSTSTORE_PATH, "") : "";
            String truststorePassword =
                    isTLSEnabled ? commonProperties.getProperty(REDIS_TRUSTSTORE_PASSWORD, "") : "";

            instance = new RedisUtil(buildRedisConnection(host, port, isTLSEnabled, truststorePath, truststorePassword));
        }
        return instance;
    }

    /**
     * Like {@link #getInstance()}, but first ensures a valid Keycloak session exists via
     * {@code KeycloakSessionGuard.ensureAuthenticated()}, unless Keycloak authentication is
     * disabled entirely via {@code KeycloakAuthConfig.isEnabled() == false} (local
     * development testing only). Use this for any Redis access that is NOT the IDP token
     * cache itself (see {@link #getInstance()}).
     * <p>
     * Self-configures {@code KeycloakSessionGuard} on first call - see
     * {@link #ensureKeycloakGuardConfigured()}.
     * </p>
     *
     * @return the same singleton {@link RedisUtil} instance as {@link #getInstance()}.
     * @throws IllegalStateException if Keycloak authentication is enabled but fails.
     */
    public static RedisUtil getKeycloakGatedInstance() {
        if (!KeycloakAuthConfig.isEnabled()) {
            return getInstance();
        }
        ensureKeycloakGuardConfigured();
        KeycloakSessionGuard.ensureAuthenticated();
        return getInstance();
    }

    /**
     * Configures {@code KeycloakSessionGuard} with a dedicated Redis-gating IDP token
     * service ({@code IdpTokenServiceFactory.createRedisIdpTokenService()}), the first time
     * {@link #getKeycloakGatedInstance()} is called with Keycloak authentication enabled.
     * <p>
     * This lazy, self-configuring approach exists specifically because this project's
     * equivalent of the gRPC federator's explicit startup wiring
     * ({@code RestFederatorServerConfig}) is marked "FRAMEWORK - do not modify" - see the
     * class Javadoc. Idempotent and thread-safe; only the first caller does the actual
     * configuration work.
     * </p>
     */
    private static void ensureKeycloakGuardConfigured() {
        if (keycloakGuardConfigured) {
            return;
        }
        synchronized (RedisUtil.class) {
            if (keycloakGuardConfigured) {
                return;
            }
            var redisIdpTokenService = IdpTokenServiceFactory.createRedisIdpTokenService();
            KeycloakSessionGuard.configure(redisIdpTokenService::fetchToken);
            keycloakGuardConfigured = true;
            LOGGER.info("KeycloakSessionGuard configured for Redis gating (self-configured on first use)");
        }
    }

    /**
     * Builds a JedisPooled connection over TLS, trusting a custom truststore if one is
     * configured. No username/password (ACL) is ever set - see the class Javadoc.
     * <p>
     * <b>Backward compatibility, and a deliberate exception to it:</b> if {@code isTLSEnabled}
     * is false, this is unchanged from before this feature existed - no TLS, no truststore
     * lookup at all. If {@code isTLSEnabled} is true but {@code truststorePath} is blank (no
     * custom truststore configured at all), this silently uses the JVM's default trust store -
     * nobody asked for a specific trust anchor, so there is nothing to fail. But if
     * {@code truststorePath} IS configured and loading it fails for any reason (missing file,
     * wrong password, corrupt keystore), this now fails closed - the exception propagates
     * rather than being caught. Silently falling back when someone explicitly configured a
     * specific trust anchor and it is broken would mean the operator believes Redis is
     * verified against their intended CA when it is actually trusting any public CA instead -
     * a silent security downgrade that could go unnoticed far longer than an outright startup
     * failure would. (An earlier version of this method caught this case too and fell back
     * silently; that was changed deliberately for the reason above - only the
     * genuinely-unconfigured case still degrades gracefully.) Hostname verification (see
     * below) is applied unconditionally whenever TLS is enabled, in both of the above cases,
     * so a truststore failing to load never silently drops that check too.
     * </p>
     *
     * @param host                redis host address.
     * @param port                redis port number.
     * @param isTLSEnabled        whether TLS is enabled.
     * @param truststorePath      path to a truststore file (JKS or PKCS12, auto-detected), or
     *                            blank to use the JVM default trust store.
     * @param truststorePassword  password for the truststore.
     * @throws FederatorSslException if truststorePath is configured but cannot be loaded.
     */
    private static JedisPooled buildRedisConnection(
            String host, int port, boolean isTLSEnabled, String truststorePath, String truststorePassword) {

        DefaultJedisClientConfig.Builder jedisClientConfigBuilder =
                DefaultJedisClientConfig.builder().ssl(isTLSEnabled);

        if (isTLSEnabled) {
            // Hostname verification is applied unconditionally, whenever TLS is enabled -
            // deliberately NOT nested inside the "did the custom truststore load" branch
            // below. Verifying that the presented certificate's SAN matches the hostname
            // being connected to is orthogonal to WHICH trust anchors validate the
            // certificate chain: it is exactly as meaningful (and exactly as necessary)
            // whether that chain is validated against the custom truststore or against the
            // JVM's default trust store in the fallback case just below. Previously this was
            // set only on the success path, meaning a misconfigured truststore silently
            // dropped hostname verification too, not just the custom trust anchors - fixed
            // here so a truststore load failure degrades ONLY the trust-anchor choice, never
            // silently drops this check as well.
            //
            // A bare SSLSocketFactory only validates the certificate CHAIN - it does not
            // automatically validate that the certificate's SAN matches the hostname being
            // connected to unless explicitly told to. "HTTPS" is the standard JDK algorithm
            // name for hostname verification generically, not specific to the HTTP protocol.
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
            jedisClientConfigBuilder.sslParameters(sslParameters);
            LOGGER.info("Hostname verification explicitly enabled for Redis TLS connection");
        }

        if (isTLSEnabled && truststorePath != null && !truststorePath.isBlank()) {
            // truststorePath is explicitly configured here - unlike the blank-path case (which
            // silently uses the JVM default trust store, no truststore having been requested at
            // all), this call is now allowed to throw and propagate. Silently falling back when
            // someone explicitly configured a specific trust anchor and it failed to load (wrong
            // password, missing file, corrupt keystore) would mean the operator believes Redis is
            // verified against their intended CA when it is actually trusting any public CA
            // instead - a silent security downgrade that could go unnoticed far longer than an
            // outright startup failure would. The blank-path case above this if is unaffected and
            // still degrades gracefully, matching this project's original backward-compatibility
            // requirement; only an explicitly-configured-but-broken truststore now fails closed.
            LOGGER.info("Configuring Redis TLS using truststore '{}'", truststorePath);
            SSLContext sslContext = SSLUtils.createSSLContextWithTrustStore(truststorePath, truststorePassword);
            jedisClientConfigBuilder.sslSocketFactory(sslContext.getSocketFactory());
        }

        return new JedisPooled(new HostAndPort(host, port), jedisClientConfigBuilder.build());
    }

    private static String qualifyOffset(String key) {
        key = getPrefixedKey(key);
        return "topic:" + key + ":offset";
    }

    /**
     * Checks if an AES key for encrypting/decrypting values in Redis has been set.
     * @return true if an AES key has been set, otherwise false
     */
    private static boolean redisAesKeyValueIsSet() {
        return redisAesKeyValue != null && !redisAesKeyValue.isBlank();
    }

    private long getOffset(String key) {
        key = qualifyOffset(key);
        LOGGER.debug("Retrieving offset from redis {}", key);
        String offset = getValue(key, String.class, redisAesKeyValueIsSet());
        return (offset != null ? Long.parseLong(offset) : 0L);
    }

    public long getOffset(String clientName, String topic) {
        return getOffset(clientName + "-" + topic);
    }

    private String setOffset(String key, long value) {
        LOGGER.debug("Persisting offset in redis {} = {}", key, value);
        setValue(qualifyOffset(key), value);
        return "OK";
    }

    public String setOffset(String clientName, String topic, long value) {
        return setOffset(clientName + "-" + topic, value);
    }

    /**
     * Stores a value in Redis at the given key without encryption.
     * No TTL.
     *
     * @param key   the Redis key
     * @param value the object to store
     * @param <T>   type of the value
     * @return true if Redis SET returned "OK"
     */
    public <T> boolean setValue(String key, T value) {
        return setValue(key, value, null);
    }

    /**
     * Stores a value in Redis at the given key with a TTL.
     * No encryption.
     *
     * @param key         the Redis key
     * @param value       the object to store
     * @param ttlSeconds  time-to-live in seconds; if null or &le; 0 then no TTL
     * @param <T>         type of the value
     * @return true if Redis SET returned "OK"
     */
    public <T> boolean setValue(String key, T value, Long ttlSeconds) {
        try {
            key = getPrefixedKey(key);
            boolean encrypt = redisAesKeyValueIsSet();
            String json = ObjectMapperUtil.getInstance().writeValueAsString(value);
            String toWrite = encrypt ? AesCryptoUtil.encrypt(json, redisAesKeyValue) : json;

            if (ttlSeconds != null && ttlSeconds > 0) {
                LOGGER.debug("Persisting key in redis {} (encrypted={}, ttlSeconds={})", key, encrypt, ttlSeconds);
                return "OK"
                        .equals(jedisPooled.set(
                                key, toWrite, SetParams.setParams().ex(ttlSeconds)));
            } else {
                LOGGER.debug("Persisting key in redis {} (encrypted={}, no TTL)", key, encrypt);
                return "OK".equals(jedisPooled.set(key, toWrite));
            }
        } catch (Exception e) {
            throw new JedisDataException("Failed to set value in Redis for key " + key, e);
        }
    }

    /**
     * Retrieves a value from Redis at the given key without decryption.
     *
     * @param key  the Redis key
     * @param type the expected class type
     * @param <T>  type of the value
     * @param encrypted  whether the value will have been encrypted
     * @return the deserialised object, or null if the key is not found
     */
    public <T> T getValue(String key, Class<T> type, boolean encrypted) {
        try {
            key = getPrefixedKey(key);
            String stored = jedisPooled.get(key);
            if (stored == null) return null;
            String json =
                    encrypted && redisAesKeyValueIsSet() ? AesCryptoUtil.decrypt(stored, redisAesKeyValue) : stored;
            return ObjectMapperUtil.getInstance().readValue(json, type);
        } catch (Exception e) {
            throw new JedisDataException("Failed to get value from Redis for key " + key, e);
        }
    }

    public static String getPrefixedKey(String key) {
        String prefix;
        try {
            prefix = PropertyUtil.getPropertyValue(REDIS_KEY_PREFIX, "");
        } catch (PropertyUtil.PropertyUtilException e) {
            // PropertyUtil not initialised in some tests/contexts; default to no prefix
            prefix = "";
        }
        if (prefix == null || prefix.isBlank()) {
            return key;
        }
        return prefix + ":" + key;
    }
}
