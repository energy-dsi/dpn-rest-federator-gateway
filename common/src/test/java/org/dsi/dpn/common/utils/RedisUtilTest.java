// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import org.dsi.dpn.common.exception.FederatorSslException;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.SetParams;

/**
 * Unit tests for {@link RedisUtil}. Previously entirely untested.
 * <p>
 * <b>Testability notes:</b> {@code RedisUtil} package-scopes a constructor,
 * {@code RedisUtil(JedisPooled)}, which is used here (this test class is in the same package) to
 * exercise {@code setValue}/{@code getValue}/{@code getOffset}/{@code setOffset} against a
 * {@link org.mockito.Mockito#mock}-ed {@link JedisPooled} - a real Jedis instance is never
 * connected to a real Redis server. {@code getInstance()}/{@code getKeycloakGatedInstance()} and
 * the private static {@code buildRedisConnection} are exercised via {@link PropertyUtil} and
 * {@link KeycloakAuthConfig} static mocks plus reflection, since {@code RedisUtil} keeps its
 * singleton and AES-key cache in private static fields with no public test-reset hook; this test
 * resets them itself via reflection in {@code @AfterEach} so no state leaks between tests or into
 * other test classes running in the same JVM.
 * </p>
 */
class RedisUtilTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;

    @BeforeEach
    void setUp() throws Exception {
        resetRedisUtilStaticState();
        KeycloakSessionGuard.resetForTesting();
        KeycloakSessionGuard.configure(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (propertyUtilMock != null) {
            propertyUtilMock.close();
            propertyUtilMock = null;
        }
        resetRedisUtilStaticState();
        KeycloakSessionGuard.resetForTesting();
        KeycloakSessionGuard.configure(null);
    }

    private static void resetRedisUtilStaticState() throws Exception {
        setStaticField("instance", null);
        setStaticField("keycloakGuardConfigured", false);
        setStaticField("redisAesKeyValue", null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = RedisUtil.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static RedisUtil newRedisUtilWithMockJedis(JedisPooled jedisPooled) throws Exception {
        var constructor = RedisUtil.class.getDeclaredConstructor(JedisPooled.class);
        constructor.setAccessible(true);
        return constructor.newInstance(jedisPooled);
    }

    @SuppressWarnings("unchecked")
    private static JedisPooled buildRedisConnection(
            String host, int port, boolean tlsEnabled, String truststorePath, String truststorePassword)
            throws Exception {
        Method method = RedisUtil.class.getDeclaredMethod(
                "buildRedisConnection", String.class, int.class, boolean.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return (JedisPooled) method.invoke(null, host, port, tlsEnabled, truststorePath, truststorePassword);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // buildRedisConnection - TLS / truststore branches
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildRedisConnection with TLS disabled builds successfully with no truststore lookup at all")
    void buildRedisConnection_tlsDisabled_buildsSuccessfully() throws Exception {
        JedisPooled result = buildRedisConnection("localhost", 6379, false, "", "");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("buildRedisConnection with TLS enabled and a blank truststore path degrades gracefully "
            + "to the JVM default trust store")
    void buildRedisConnection_tlsEnabledBlankTruststore_degradesGracefully() throws Exception {
        JedisPooled result = buildRedisConnection("localhost", 6379, true, "", "");

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("buildRedisConnection with TLS enabled and a null truststore path also degrades gracefully")
    void buildRedisConnection_tlsEnabledNullTruststore_degradesGracefully() throws Exception {
        JedisPooled result = buildRedisConnection("localhost", 6379, true, null, null);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("buildRedisConnection with TLS enabled and a configured-but-broken truststore THROWS "
            + "(deliberately changed from the old silently-caught fallback - a security fail-closed fix)")
    void buildRedisConnection_tlsEnabledBrokenTruststore_throwsInsteadOfSilentlyFallingBack() {
        assertThatThrownBy(() -> buildRedisConnection(
                        "localhost", 6379, true, "/definitely/does/not/exist.jks", "wrong-password"))
                .isInstanceOf(FederatorSslException.class);
    }

    @Test
    @DisplayName("buildRedisConnection with TLS enabled and a real, valid truststore builds successfully")
    void buildRedisConnection_tlsEnabledValidTruststore_buildsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path truststore = tempDir.resolve("redis-truststore.jks");
        Process process = new ProcessBuilder(
                        "keytool",
                        "-genkeypair",
                        "-alias",
                        "redis-test-entry",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "365",
                        "-dname",
                        "CN=redis-util-test",
                        "-storetype",
                        "JKS",
                        "-keystore",
                        truststore.toString(),
                        "-storepass",
                        "changeit",
                        "-keypass",
                        "changeit")
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
        }

        JedisPooled result = buildRedisConnection("localhost", 6379, true, truststore.toString(), "changeit");

        assertThat(result).isNotNull();
    }

    // ------------------------------------------------------------------
    // getInstance() - singleton behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getInstance() returns the same singleton instance across multiple calls")
    void getInstance_returnsSameSingletonAcrossCalls() {
        Properties commonProperties = new Properties();
        commonProperties.setProperty(RedisUtil.REDIS_TLS_ENABLED, "false");

        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_AES_KEY, "")).thenReturn("");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_HOST, RedisUtil.LOCALHOST))
                .thenReturn("localhost");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue(RedisUtil.REDIS_PORT, RedisUtil.DEFAULT_PORT))
                .thenReturn(6379);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(commonProperties);
        propertyUtilMock
                .when(() -> PropertyUtil.createSecretProvider(any(Properties.class)))
                .thenReturn(new org.dsi.dpn.common.service.secret.NoopSecretProvider());
        propertyUtilMock
                .when(() -> PropertyUtil.overrideWithSecrets(any(Properties.class), any()))
                .thenAnswer(invocation -> null);

        RedisUtil first = RedisUtil.getInstance();
        RedisUtil second = RedisUtil.getInstance();

        assertThat(first).isSameAs(second);
        // PropertyUtil lookups only happen on the FIRST call - confirms genuine singleton
        // construction, not a fresh build on every call.
        propertyUtilMock.verify(
                () -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_HOST, RedisUtil.LOCALHOST), times(1));
    }

    // ------------------------------------------------------------------
    // getKeycloakGatedInstance() - delegation per KeycloakAuthConfig.isEnabled()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getKeycloakGatedInstance() delegates directly to getInstance() when Keycloak auth is disabled, "
            + "without configuring the guard or calling ensureAuthenticated()")
    void getKeycloakGatedInstance_keycloakDisabled_delegatesToGetInstanceDirectly() {
        Properties commonProperties = new Properties();
        commonProperties.setProperty(RedisUtil.REDIS_TLS_ENABLED, "false");

        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_AES_KEY, "")).thenReturn("");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_HOST, RedisUtil.LOCALHOST))
                .thenReturn("localhost");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue(RedisUtil.REDIS_PORT, RedisUtil.DEFAULT_PORT))
                .thenReturn(6379);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(commonProperties);
        propertyUtilMock
                .when(() -> PropertyUtil.createSecretProvider(any(Properties.class)))
                .thenReturn(new org.dsi.dpn.common.service.secret.NoopSecretProvider());
        propertyUtilMock
                .when(() -> PropertyUtil.overrideWithSecrets(any(Properties.class), any()))
                .thenAnswer(invocation -> null);

        try (MockedStatic<KeycloakAuthConfig> keycloakAuthConfigMock = mockStatic(KeycloakAuthConfig.class)) {
            keycloakAuthConfigMock.when(KeycloakAuthConfig::isEnabled).thenReturn(false);

            RedisUtil gated = RedisUtil.getKeycloakGatedInstance();
            RedisUtil ungated = RedisUtil.getInstance();

            assertThat(gated).isSameAs(ungated);
        }

        // The guard must never have been touched: no fetcher configured, so ensureAuthenticated()
        // would throw if it had somehow been invoked - it was not, confirmed by no exception here.
        assertThatThrownBy(KeycloakSessionGuard::ensureAuthenticated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configure(...) was never called");
    }

    @Test
    @DisplayName("getKeycloakGatedInstance() self-configures the guard and requires authentication "
            + "when Keycloak auth is enabled")
    void getKeycloakGatedInstance_keycloakEnabled_configuresGuardAndAuthenticates() {
        Properties commonProperties = new Properties();
        commonProperties.setProperty(RedisUtil.REDIS_TLS_ENABLED, "false");
        commonProperties.setProperty("redis.idp.token.url", "https://idp.example.test/token");
        commonProperties.setProperty("redis.idp.client.id", "redis-gate-client");
        commonProperties.setProperty("redis.idp.client.secret", "redis-gate-secret");

        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_AES_KEY, "")).thenReturn("");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_HOST, RedisUtil.LOCALHOST))
                .thenReturn("localhost");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue(RedisUtil.REDIS_PORT, RedisUtil.DEFAULT_PORT))
                .thenReturn(6379);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(commonProperties);
        propertyUtilMock
                .when(() -> PropertyUtil.createSecretProvider(any(Properties.class)))
                .thenReturn(new org.dsi.dpn.common.service.secret.NoopSecretProvider());
        propertyUtilMock
                .when(() -> PropertyUtil.overrideWithSecrets(any(Properties.class), any()))
                .thenAnswer(invocation -> null);

        try (MockedStatic<KeycloakAuthConfig> keycloakAuthConfigMock = mockStatic(KeycloakAuthConfig.class);
                MockedStatic<IdpTokenServiceFactory> idpFactoryMock = mockStatic(IdpTokenServiceFactory.class)) {
            keycloakAuthConfigMock.when(KeycloakAuthConfig::isEnabled).thenReturn(true);

            IdpTokenService fakeRedisIdpService = mock(IdpTokenService.class);
            when(fakeRedisIdpService.fetchToken()).thenReturn("fake-redis-gate-token");
            idpFactoryMock.when(IdpTokenServiceFactory::createRedisIdpTokenService).thenReturn(fakeRedisIdpService);

            RedisUtil gated = RedisUtil.getKeycloakGatedInstance();

            assertThat(gated).isNotNull();
            verify(fakeRedisIdpService, times(1)).fetchToken();

            // A second call must reuse the already-configured guard and the still-valid session -
            // no second fetchToken() call within the TTL window.
            RedisUtil.getKeycloakGatedInstance();
            verify(fakeRedisIdpService, times(1)).fetchToken();
        }
    }

    @Test
    @DisplayName("getKeycloakGatedInstance() propagates an IllegalStateException when Keycloak authentication fails")
    void getKeycloakGatedInstance_keycloakEnabledAuthFails_throwsIllegalStateException() {
        Properties commonProperties = new Properties();
        commonProperties.setProperty(RedisUtil.REDIS_TLS_ENABLED, "false");

        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock.when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_AES_KEY, "")).thenReturn("");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_HOST, RedisUtil.LOCALHOST))
                .thenReturn("localhost");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue(RedisUtil.REDIS_PORT, RedisUtil.DEFAULT_PORT))
                .thenReturn(6379);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertiesFromFilePath("common.configuration"))
                .thenReturn(commonProperties);
        propertyUtilMock
                .when(() -> PropertyUtil.createSecretProvider(any(Properties.class)))
                .thenReturn(new org.dsi.dpn.common.service.secret.NoopSecretProvider());
        propertyUtilMock
                .when(() -> PropertyUtil.overrideWithSecrets(any(Properties.class), any()))
                .thenAnswer(invocation -> null);

        try (MockedStatic<KeycloakAuthConfig> keycloakAuthConfigMock = mockStatic(KeycloakAuthConfig.class);
                MockedStatic<IdpTokenServiceFactory> idpFactoryMock = mockStatic(IdpTokenServiceFactory.class)) {
            keycloakAuthConfigMock.when(KeycloakAuthConfig::isEnabled).thenReturn(true);

            IdpTokenService fakeRedisIdpService = mock(IdpTokenService.class);
            when(fakeRedisIdpService.fetchToken()).thenThrow(new RuntimeException("Keycloak unreachable"));
            idpFactoryMock.when(IdpTokenServiceFactory::createRedisIdpTokenService).thenReturn(fakeRedisIdpService);

            assertThatThrownBy(RedisUtil::getKeycloakGatedInstance)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unable to authenticate with Keycloak");
        }
    }

    // ------------------------------------------------------------------
    // getPrefixedKey()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPrefixedKey defaults to no prefix when PropertyUtil is not initialised")
    void getPrefixedKey_propertyUtilNotInitialised_returnsKeyUnprefixed() {
        PropertyUtil.clear(); // ensure genuinely uninitialised - triggers the real catch branch

        assertThat(RedisUtil.getPrefixedKey("my-key")).isEqualTo("my-key");
    }

    @Test
    @DisplayName("getPrefixedKey prepends the configured prefix")
    void getPrefixedKey_prefixConfigured_prependsPrefix() {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_KEY_PREFIX, ""))
                .thenReturn("myapp");

        assertThat(RedisUtil.getPrefixedKey("my-key")).isEqualTo("myapp:my-key");
    }

    @Test
    @DisplayName("getPrefixedKey returns the key unprefixed when the configured prefix is blank")
    void getPrefixedKey_blankPrefixConfigured_returnsKeyUnprefixed() {
        propertyUtilMock = mockStatic(PropertyUtil.class);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(RedisUtil.REDIS_KEY_PREFIX, ""))
                .thenReturn("   ");

        assertThat(RedisUtil.getPrefixedKey("my-key")).isEqualTo("my-key");
    }

    // ------------------------------------------------------------------
    // setValue / getValue - encryption behaviour via the AES key
    // ------------------------------------------------------------------

    private static final String VALID_AES_KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[16]); // 16 zero bytes

    @Test
    @DisplayName("setValue without an AES key configured stores plain (unencrypted) JSON")
    void setValue_noAesKeyConfigured_storesPlainJson() throws Exception {
        setStaticField("redisAesKeyValue", "");
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.set(anyString(), anyString())).thenReturn("OK");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        boolean result = redisUtil.setValue("some-key", "hello-world");

        assertThat(result).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedisPooled).set(eq("some-key"), captor.capture());
        assertThat(captor.getValue()).isEqualTo("\"hello-world\"");
    }

    @Test
    @DisplayName("setValue with an AES key configured stores encrypted, non-plaintext data")
    void setValue_aesKeyConfigured_storesEncryptedData() throws Exception {
        setStaticField("redisAesKeyValue", VALID_AES_KEY_BASE64);
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.set(anyString(), anyString())).thenReturn("OK");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        boolean result = redisUtil.setValue("some-key", "hello-world");

        assertThat(result).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jedisPooled).set(eq("some-key"), captor.capture());
        String stored = captor.getValue();
        assertThat(stored).isNotEqualTo("\"hello-world\"");
        // Confirm it really is AES-GCM ciphertext decryptable back to the original JSON.
        assertThat(AesCryptoUtil.decrypt(stored, VALID_AES_KEY_BASE64)).isEqualTo("\"hello-world\"");
    }

    @Test
    @DisplayName("getValue with encrypted=true and an AES key configured decrypts the stored value")
    void getValue_encryptedWithAesKeyConfigured_decryptsCorrectly() throws Exception {
        setStaticField("redisAesKeyValue", VALID_AES_KEY_BASE64);
        String encryptedJson = AesCryptoUtil.encrypt("\"hello-world\"", VALID_AES_KEY_BASE64);
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get("some-key")).thenReturn(encryptedJson);
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        String result = redisUtil.getValue("some-key", String.class, true);

        assertThat(result).isEqualTo("hello-world");
    }

    @Test
    @DisplayName("getValue with encrypted=false never attempts decryption, even if an AES key is configured")
    void getValue_notEncrypted_readsRawJsonEvenWithAesKeyConfigured() throws Exception {
        setStaticField("redisAesKeyValue", VALID_AES_KEY_BASE64);
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get("some-key")).thenReturn("\"plain-value\"");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        String result = redisUtil.getValue("some-key", String.class, false);

        assertThat(result).isEqualTo("plain-value");
    }

    @Test
    @DisplayName("getValue returns null when the key is not found in Redis")
    void getValue_keyNotFound_returnsNull() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get(anyString())).thenReturn(null);
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        String result = redisUtil.getValue("missing-key", String.class, true);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("setValue with a positive TTL uses SetParams with expiry")
    void setValue_withPositiveTtl_usesSetParamsWithExpiry() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        boolean result = redisUtil.setValue("ttl-key", "value", 60L);

        assertThat(result).isTrue();
        verify(jedisPooled).set(eq("ttl-key"), anyString(), any(SetParams.class));
        verify(jedisPooled, never()).set(eq("ttl-key"), anyString());
    }

    @Test
    @DisplayName("setValue with a null or non-positive TTL uses the plain SET without expiry")
    void setValue_withNullOrNonPositiveTtl_usesPlainSet() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.set(anyString(), anyString())).thenReturn("OK");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        assertThat(redisUtil.setValue("no-ttl-key", "value", null)).isTrue();
        assertThat(redisUtil.setValue("zero-ttl-key", "value", 0L)).isTrue();
        assertThat(redisUtil.setValue("negative-ttl-key", "value", -5L)).isTrue();

        verify(jedisPooled, times(3)).set(anyString(), anyString());
        verify(jedisPooled, never()).set(anyString(), anyString(), any(SetParams.class));
    }

    @Test
    @DisplayName("setValue wraps a Jedis failure in JedisDataException")
    void setValue_jedisThrows_wrapsInJedisDataException() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.set(anyString(), anyString())).thenThrow(new RuntimeException("connection refused"));
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        assertThatThrownBy(() -> redisUtil.setValue("some-key", "value"))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("Failed to set value in Redis");
    }

    @Test
    @DisplayName("getValue wraps a Jedis failure in JedisDataException")
    void getValue_jedisThrows_wrapsInJedisDataException() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        assertThatThrownBy(() -> redisUtil.getValue("some-key", String.class, false))
                .isInstanceOf(JedisDataException.class)
                .hasMessageContaining("Failed to get value from Redis");
    }

    @Test
    @DisplayName("getValue wraps a decryption failure (e.g. wrong AES key) in JedisDataException")
    void getValue_decryptionFails_wrapsInJedisDataException() throws Exception {
        setStaticField("redisAesKeyValue", VALID_AES_KEY_BASE64);
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get(anyString())).thenReturn("not-valid-base64-ciphertext!!");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        assertThatThrownBy(() -> redisUtil.getValue("some-key", String.class, true))
                .isInstanceOf(JedisDataException.class);
    }

    // ------------------------------------------------------------------
    // getOffset / setOffset
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setOffset(clientName, topic, value) persists under the qualified 'topic:<key>:offset' Redis key")
    void setOffset_persistsUnderQualifiedKey() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.set(anyString(), anyString())).thenReturn("OK");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        String result = redisUtil.setOffset("client-a", "topic-x", 123L);

        assertThat(result).isEqualTo("OK");
        verify(jedisPooled).set(eq("topic:client-a-topic-x:offset"), eq("123"));
    }

    @Test
    @DisplayName("getOffset(clientName, topic) returns 0 when no offset has been stored yet")
    void getOffset_noStoredValue_returnsZero() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get(anyString())).thenReturn(null);
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        long offset = redisUtil.getOffset("client-a", "topic-x");

        assertThat(offset).isZero();
    }

    @Test
    @DisplayName("getOffset(clientName, topic) returns the previously stored value")
    void getOffset_storedValue_returnsParsedLong() throws Exception {
        JedisPooled jedisPooled = mock(JedisPooled.class);
        when(jedisPooled.get("topic:client-a-topic-x:offset")).thenReturn("456");
        RedisUtil redisUtil = newRedisUtilWithMockJedis(jedisPooled);

        long offset = redisUtil.getOffset("client-a", "topic-x");

        assertThat(offset).isEqualTo(456L);
    }
}
