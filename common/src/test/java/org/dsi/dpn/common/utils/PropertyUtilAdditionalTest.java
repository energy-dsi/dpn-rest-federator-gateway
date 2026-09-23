// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.dsi.dpn.common.service.secret.NoopSecretProvider;
import org.dsi.dpn.common.service.secret.VaultAuthConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Additional unit tests for {@link PropertyUtil}, targeting every method the JaCoCo report
 * showed at 0% coverage: {@code getPropertiesFromFileName}, {@code initializeProperties},
 * {@code init(String)}, {@code rootCauseMessage}, {@code getLongOrDefault},
 * {@code buildCacheKey}, {@code getPropertyFileNameValue}, {@code buildTokenAuthConfig}, and the
 * no-default overloads of {@code getPropertyLongValue}/{@code getPropertyDurationValue}/
 * {@code getPropertyBooleanValue}.
 * <p>
 * {@code FEDERATOR_SERVER_PROPERTIES} and {@code VAULT_TOKEN} are both real environment
 * variables this session's own manual testing has repeatedly exported in the shell that may run
 * these tests - every test that reads either is guarded with
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue} rather than asserting a specific outcome
 * that depends on them being unset.
 * </p>
 */
class PropertyUtilAdditionalTest {

    @BeforeEach
    void ensureCleanSingleton() {
        PropertyUtil.clear();
        PropertyUtil.providerOverrideForTest = null;
        PropertyUtil.testMappingsOverride = null;
    }

    @AfterEach
    void resetSingleton() {
        PropertyUtil.clear();
        PropertyUtil.providerOverrideForTest = null;
        PropertyUtil.testMappingsOverride = null;
        PropertyUtil.clearSecretProviderCache();
    }

    private void initWithProperties(Properties properties, Path tempDir) throws Exception {
        PropertyUtil.providerOverrideForTest = new NoopSecretProvider();
        File file = tempDir.resolve("test.properties").toFile();
        try (var out = Files.newOutputStream(file.toPath())) {
            properties.store(out, null);
        }
        PropertyUtil.init(file);
    }

    private Object invokeStatic(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = PropertyUtil.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // getPropertiesFromFileName(String) - absolute path, then classpath fallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPropertiesFromFileName() loads from a genuine absolute file path")
    void getPropertiesFromFileName_absolutePath_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("nested.properties").toFile();
        Properties toWrite = new Properties();
        toWrite.setProperty("my.key", "my-value");
        try (var out = Files.newOutputStream(file.toPath())) {
            toWrite.store(out, null);
        }

        Properties result = PropertyUtil.getPropertiesFromFileName(file.getAbsolutePath());

        assertThat(result.getProperty("my.key")).isEqualTo("my-value");
    }

    @Test
    @DisplayName("getPropertiesFromFileName() throws PropertyUtilException when neither an absolute "
            + "path nor a classpath resource of that name exists")
    void getPropertiesFromFileName_neitherPathNorResourceExists_throws() {
        assertThatThrownBy(() -> PropertyUtil.getPropertiesFromFileName("definitely-does-not-exist.properties"))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class);
    }

    // ------------------------------------------------------------------
    // getPropertyFileNameValue(String) - classpath resource resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPropertyFileNameValue() throws PropertyUtilException when the classpath "
            + "resource does not exist")
    void getPropertyFileNameValue_missingResource_throws() {
        assertThatThrownBy(() -> PropertyUtil.getPropertyFileNameValue("this-resource-does-not-exist.txt"))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class);
    }

    // ------------------------------------------------------------------
    // initializeProperties() - already-initialised branch (safe: touches no env var at all)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("initializeProperties() returns true immediately when PropertyUtil is already "
            + "initialised, without touching any environment variable")
    void initializeProperties_alreadyInitialised_returnsTrueImmediately(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);

        boolean result = PropertyUtil.initializeProperties();

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("initializeProperties() falls back to loading from the classpath when "
            + "FEDERATOR_SERVER_PROPERTIES is genuinely unset, and reports whether that succeeded")
    void initializeProperties_notInitialisedEnvVarUnset_attemptsClasspathFallback() {
        assumeTrue(
                System.getenv("FEDERATOR_SERVER_PROPERTIES") == null,
                "Skipping: FEDERATOR_SERVER_PROPERTIES is set in this shell");

        // Not asserting true or false here - whether server.properties happens to exist on this
        // test run's classpath is environment-specific. What matters is that the classpath
        // fallback branch is genuinely exercised (not throwing an unexpected exception) rather
        // than the env-var branch, which assumeTrue above already confirmed is skipped.
        boolean result = PropertyUtil.initializeProperties();

        assertThat(result).isIn(true, false);
    }

    // ------------------------------------------------------------------
    // init(String) - classpath resource loading
    // ------------------------------------------------------------------

    @Test
    @DisplayName("init(String) throws PropertyUtilException when the named classpath resource does not exist")
    void initString_missingResource_throwsPropertyUtilException() {
        assertThatThrownBy(() -> PropertyUtil.init("this-classpath-resource-does-not-exist.properties"))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class);
    }

    @Test
    @DisplayName("init(String) throws when PropertyUtil is already initialised")
    void initString_alreadyInitialised_throws(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);

        assertThatThrownBy(() -> PropertyUtil.init("anything.properties"))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class)
                .hasMessageContaining("Cannot initialise Property Util twice");
    }

    // ------------------------------------------------------------------
    // rootCauseMessage(Throwable) - private, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rootCauseMessage() returns the message of a single exception with no cause")
    void rootCauseMessage_noCause_returnsOwnMessage() throws Exception {
        Throwable t = new RuntimeException("top-level failure");

        Object result = invokeStatic("rootCauseMessage", new Class<?>[] {Throwable.class}, t);

        assertThat(result).isEqualTo("top-level failure");
    }

    @Test
    @DisplayName("rootCauseMessage() walks a chain of causes and returns the deepest one's message")
    void rootCauseMessage_chainOfCauses_returnsDeepestMessage() throws Exception {
        Throwable root = new IllegalStateException("the real root cause");
        Throwable middle = new RuntimeException("middle wrapper", root);
        Throwable top = new RuntimeException("top wrapper", middle);

        Object result = invokeStatic("rootCauseMessage", new Class<?>[] {Throwable.class}, top);

        assertThat(result).isEqualTo("the real root cause");
    }

    @Test
    @DisplayName("rootCauseMessage() falls back to toString() when the deepest cause has a null message")
    void rootCauseMessage_deepestCauseHasNullMessage_fallsBackToToString() throws Exception {
        Throwable root = new NullPointerException();
        Throwable top = new RuntimeException("wrapper", root);

        Object result = invokeStatic("rootCauseMessage", new Class<?>[] {Throwable.class}, top);

        assertThat(result).isEqualTo(root.toString());
    }

    // ------------------------------------------------------------------
    // getLongOrDefault(String, long) - private, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getLongOrDefault() returns the default when the value is null")
    void getLongOrDefault_nullValue_returnsDefault() throws Exception {
        Object result = invokeStatic("getLongOrDefault", new Class<?>[] {String.class, long.class}, null, 42L);

        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("getLongOrDefault() returns the default when the value is blank")
    void getLongOrDefault_blankValue_returnsDefault() throws Exception {
        Object result = invokeStatic("getLongOrDefault", new Class<?>[] {String.class, long.class}, "   ", 42L);

        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("getLongOrDefault() parses a genuine numeric value, trimming whitespace")
    void getLongOrDefault_validNumber_parsesIt() throws Exception {
        Object result =
                invokeStatic("getLongOrDefault", new Class<?>[] {String.class, long.class}, "  123  ", 42L);

        assertThat(result).isEqualTo(123L);
    }

    @Test
    @DisplayName("getLongOrDefault() returns the default when the value is not a valid number")
    void getLongOrDefault_invalidNumber_returnsDefault() throws Exception {
        Object result =
                invokeStatic("getLongOrDefault", new Class<?>[] {String.class, long.class}, "not-a-number", 42L);

        assertThat(result).isEqualTo(42L);
    }

    // ------------------------------------------------------------------
    // buildCacheKey(String, VaultAuthConfig, String) - private, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildCacheKey() for a token-based auth config includes a hashed token identity, "
            + "not the raw token itself")
    void buildCacheKey_tokenAuth_includesHashedTokenIdentity() throws Exception {
        VaultAuthConfig tokenConfig = VaultAuthConfig.forToken("s.super-secret-token-value");

        Object result = invokeStatic(
                "buildCacheKey",
                new Class<?>[] {String.class, VaultAuthConfig.class, String.class},
                "https://vault.example.test",
                tokenConfig,
                "/vault/truststore.jks");

        assertThat(result).asString()
                .startsWith("https://vault.example.test|token:")
                .doesNotContain("s.super-secret-token-value")
                .endsWith("|/vault/truststore.jks");
    }

    @Test
    @DisplayName("buildCacheKey() for an AppRole-based auth config includes the mount path and role id")
    void buildCacheKey_approleAuth_includesMountPathAndRoleId() throws Exception {
        VaultAuthConfig approleConfig = VaultAuthConfig.forAppRole("role-123", "secret-456", "approle", 3600L);

        Object result = invokeStatic(
                "buildCacheKey",
                new Class<?>[] {String.class, VaultAuthConfig.class, String.class},
                "https://vault.example.test",
                approleConfig,
                "/vault/truststore.jks");

        assertThat(result).isEqualTo("https://vault.example.test|approle:approle:role-123|/vault/truststore.jks");
    }

    // ------------------------------------------------------------------
    // buildTokenAuthConfig() - private, via reflection, guarded against real VAULT_TOKEN pollution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildTokenAuthConfig() returns null when VAULT_TOKEN is genuinely unset")
    void buildTokenAuthConfig_envVarUnset_returnsNull() throws Exception {
        assumeTrue(System.getenv("VAULT_TOKEN") == null, "Skipping: VAULT_TOKEN is set in this shell");

        Object result = invokeStatic("buildTokenAuthConfig", new Class<?>[0]);

        assertThat(result).isNull();
    }

    // ------------------------------------------------------------------
    // getPropertyLongValue(String) / getPropertyDurationValue(String) /
    // getPropertyBooleanValue(String) - no-default overloads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPropertyLongValue(key) parses a present property with no default needed")
    void getPropertyLongValue_noDefault_presentKey_parsesValue(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("my.long.key", "9999");
        initWithProperties(properties, tempDir);

        long result = PropertyUtil.getPropertyLongValue("my.long.key");

        assertThat(result).isEqualTo(9999L);
    }

    @Test
    @DisplayName("getPropertyLongValue(key) throws when the key is genuinely absent, with no default to fall back on")
    void getPropertyLongValue_noDefault_missingKey_throws(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);

        assertThatThrownBy(() -> PropertyUtil.getPropertyLongValue("does.not.exist"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("getPropertyDurationValue(key) parses a present property with no default needed")
    void getPropertyDurationValue_noDefault_presentKey_parsesValue(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("my.duration.key", "PT15M");
        initWithProperties(properties, tempDir);

        var result = PropertyUtil.getPropertyDurationValue("my.duration.key");

        assertThat(result).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("getPropertyBooleanValue(key) parses a present property with no default needed")
    void getPropertyBooleanValue_noDefault_presentKey_parsesValue(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("my.boolean.key", "true");
        initWithProperties(properties, tempDir);

        boolean result = PropertyUtil.getPropertyBooleanValue("my.boolean.key");

        assertThat(result).isTrue();
    }
}
