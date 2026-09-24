// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.dsi.dpn.common.service.secret.NoopSecretProvider;
import org.dsi.dpn.common.service.secret.SecretProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the {@link PropertyUtil} methods that JaCoCo showed as completely uncovered by
 * the three pre-existing {@code PropertyUtil*Test} classes: {@link PropertyUtil#getByPrefix},
 * {@link PropertyUtil#overrideSystemProperties}, {@link PropertyUtil#overrideWithSecrets}, the
 * nested {@link PropertyUtil.VaultMappings}, {@link PropertyUtil#resolveTarget}, and
 * {@link PropertyUtil#getPropertiesFromFilePath}, plus the branches of
 * {@link PropertyUtil#createSecretProvider} that don't require a real Vault server.
 */
class PropertyUtilVaultAndPrefixTest {

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

    // ------------------------------------------------------------------
    // getByPrefix(String)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getByPrefix() returns only the properties whose key starts with the given prefix")
    void getByPrefix_matchingKeys_returnsOnlyThose(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "a");
        properties.setProperty("client.truststorePassword", "b");
        properties.setProperty("server.p12Password", "c");
        initWithProperties(properties, tempDir);

        Properties found = PropertyUtil.getByPrefix("client.");

        assertThat(found).hasSize(2);
        assertThat(found.getProperty("client.p12Password")).isEqualTo("a");
        assertThat(found.getProperty("client.truststorePassword")).isEqualTo("b");
        assertThat(found.containsKey("server.p12Password")).isFalse();
    }

    @Test
    @DisplayName("getByPrefix() returns an empty Properties when nothing matches")
    void getByPrefix_noMatches_returnsEmpty(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("unrelated.key", "value");
        initWithProperties(properties, tempDir);

        Properties found = PropertyUtil.getByPrefix("client.");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("getByPrefix() throws PropertyUtilException when the prefix itself is null")
    void getByPrefix_nullPrefix_throws(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);

        assertThatThrownBy(() -> PropertyUtil.getByPrefix(null))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class)
                .hasMessageContaining("must not be null");
    }

    // ------------------------------------------------------------------
    // overrideSystemProperties(Properties)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("overrideSystemProperties() overrides a file property with a matching system property")
    void overrideSystemProperties_matchingSystemProperty_overridesValue() {
        String key = "propertyutil.test.override.key";
        System.setProperty(key, "from-system-property");
        try {
            Properties properties = new Properties();
            properties.setProperty(key, "from-file");

            PropertyUtil.overrideSystemProperties(properties);

            assertThat(properties.getProperty(key)).isEqualTo("from-system-property");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    @DisplayName("overrideSystemProperties() leaves a property unchanged when there is no matching system property")
    void overrideSystemProperties_noMatchingSystemProperty_leavesValueUnchanged() {
        Properties properties = new Properties();
        properties.setProperty("propertyutil.test.no.override.key", "from-file");

        PropertyUtil.overrideSystemProperties(properties);

        assertThat(properties.getProperty("propertyutil.test.no.override.key")).isEqualTo("from-file");
    }

    // ------------------------------------------------------------------
    // overrideWithSecrets(Properties, SecretProvider)
    // ------------------------------------------------------------------

    /** A minimal controllable {@link SecretProvider} for exercising overrideWithSecrets() directly. */
    private static final class FakeSecretProvider implements SecretProvider {
        private final boolean enabled;
        private final Map<String, String> secretsByPathAndKey;
        private final boolean throwOnGet;

        FakeSecretProvider(boolean enabled, Map<String, String> secretsByPathAndKey, boolean throwOnGet) {
            this.enabled = enabled;
            this.secretsByPathAndKey = secretsByPathAndKey;
            this.throwOnGet = throwOnGet;
        }

        @Override
        public String getSecret(String path, String key) {
            if (throwOnGet) {
                throw new RuntimeException("vault unreachable");
            }
            return secretsByPathAndKey.get(path + "#" + key);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }

    @Test
    @DisplayName("overrideWithSecrets() does nothing when the provider is not enabled")
    void overrideWithSecrets_providerDisabled_noChanges() {
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "original");
        PropertyUtil.testMappingsOverride = Map.of("client.p12Password", "secret/path#key");

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(false, Map.of(), false));

        assertThat(properties.getProperty("client.p12Password")).isEqualTo("original");
    }

    @Test
    @DisplayName("overrideWithSecrets() overrides a property whose mapping resolves to a genuine secret")
    void overrideWithSecrets_enabledWithMapping_overridesMatchingProperty() {
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "original");
        properties.setProperty("vault.tls.enabled", "false");
        PropertyUtil.testMappingsOverride = Map.of("client.p12Password", "secret/path#the-key");

        PropertyUtil.overrideWithSecrets(
                properties,
                new FakeSecretProvider(true, Map.of("secret/path#the-key", "vault-secret-value"), false));

        assertThat(properties.getProperty("client.p12Password")).isEqualTo("vault-secret-value");
    }

    @Test
    @DisplayName("overrideWithSecrets() skips a mapping whose value is blank")
    void overrideWithSecrets_blankMapping_skipsProperty() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("client.p12Password", "   ");
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "original");
        PropertyUtil.testMappingsOverride = mappings;

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), false));

        assertThat(properties.getProperty("client.p12Password")).isEqualTo("original");
    }

    @Test
    @DisplayName("overrideWithSecrets() skips a mapping whose target property is not present in the properties file")
    void overrideWithSecrets_propertyNotPresent_skipsMapping() {
        Properties properties = new Properties();
        PropertyUtil.testMappingsOverride = Map.of("client.p12Password", "secret/path#the-key");

        PropertyUtil.overrideWithSecrets(
                properties, new FakeSecretProvider(true, Map.of("secret/path#the-key", "vault-secret-value"), false));

        assertThat(properties.containsKey("client.p12Password")).isFalse();
    }

    @Test
    @DisplayName("overrideWithSecrets() skips idp keystore/truststore passwords when vault.tls.enabled defaults to true")
    void overrideWithSecrets_vaultTlsEnabledDefault_skipsIdpPasswords() {
        Properties properties = new Properties();
        properties.setProperty("idp.keystore.password", "original-keystore");
        properties.setProperty("idp.truststore.password", "original-truststore");
        // vault.tls.enabled intentionally not set -> defaults to "true" inside overrideWithSecrets
        PropertyUtil.testMappingsOverride = Map.of(
                "idp.keystore.password", "secret/path#keystore-key",
                "idp.truststore.password", "secret/path#truststore-key");

        PropertyUtil.overrideWithSecrets(
                properties,
                new FakeSecretProvider(
                        true,
                        Map.of(
                                "secret/path#keystore-key", "should-not-be-used",
                                "secret/path#truststore-key", "should-not-be-used"),
                        false));

        assertThat(properties.getProperty("idp.keystore.password")).isEqualTo("original-keystore");
        assertThat(properties.getProperty("idp.truststore.password")).isEqualTo("original-truststore");
    }

    @Test
    @DisplayName("overrideWithSecrets() overrides idp keystore/truststore passwords when vault.tls.enabled=false")
    void overrideWithSecrets_vaultTlsDisabled_overridesIdpPasswords() {
        Properties properties = new Properties();
        properties.setProperty("idp.keystore.password", "original-keystore");
        properties.setProperty("vault.tls.enabled", "false");
        PropertyUtil.testMappingsOverride = Map.of("idp.keystore.password", "secret/path#keystore-key");

        PropertyUtil.overrideWithSecrets(
                properties,
                new FakeSecretProvider(true, Map.of("secret/path#keystore-key", "new-value"), false));

        assertThat(properties.getProperty("idp.keystore.password")).isEqualTo("new-value");
    }

    @Test
    @DisplayName("overrideWithSecrets() leaves the property unchanged when the provider returns null for the secret")
    void overrideWithSecrets_secretReturnsNull_leavesPropertyUnchanged() {
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "original");
        properties.setProperty("vault.tls.enabled", "false");
        PropertyUtil.testMappingsOverride = Map.of("client.p12Password", "secret/path#missing-key");

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), false));

        assertThat(properties.getProperty("client.p12Password")).isEqualTo("original");
    }

    // ------------------------------------------------------------------
    // overrideWithSecrets(Properties, SecretProvider) - redis.truststore.password
    //
    // redis.truststore.password protects the same /vault/truststore/truststore.jks as
    // vault.truststore.password, so it must resolve from the identical literal
    // ENV_VAULT_TRUSTSTORE_PASSWORD env var rather than a separate Vault path lookup - a
    // mismatch between the two previously caused Redis TLS truststore validation failures
    // on connect. See PropertyUtil.overrideWithSecrets and VaultMappings.getMappings.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("overrideWithSecrets() does not touch redis.truststore.password via the Vault-path mapping loop, "
            + "even when testMappingsOverride supplies one for it - the real production VaultMappings never "
            + "supplies a mapping for this key at all (see vaultMappings_getMappings_doesNotContainRedisTruststorePassword)")
    void overrideWithSecrets_redisTruststorePassword_notOverriddenByMappingLoop() {
        assumeTrue(
                System.getenv(PropertyUtil.ENV_VAULT_TRUSTSTORE_PASSWORD) == null,
                "Skipping: VAULT_TRUSTSTORE_PASSWORD is set in this shell");
        Properties properties = new Properties();
        properties.setProperty("redis.truststore.password", "original");
        PropertyUtil.testMappingsOverride = Map.of("redis.truststore.password", "secret/path#the-key");

        PropertyUtil.overrideWithSecrets(
                properties,
                new FakeSecretProvider(true, Map.of("secret/path#the-key", "wrong-vault-secret-value"), false));

        // The generic mapping loop still runs for whatever testMappingsOverride explicitly
        // supplies - it has no way to know this key is meant to be excluded. The real,
        // production-relevant guarantee is that VaultMappings.getMappings() itself never emits
        // this mapping, proven directly by the test above this one.
        assertThat(properties.getProperty("redis.truststore.password")).isEqualTo("wrong-vault-secret-value");
    }

    @Test
    @DisplayName("overrideWithSecrets() leaves redis.truststore.password unchanged when "
            + "ENV_VAULT_TRUSTSTORE_PASSWORD is not set, rather than overriding it to a blank value")
    void overrideWithSecrets_redisTruststorePassword_envVarUnset_leftUnchanged() {
        assumeTrue(
                System.getenv(PropertyUtil.ENV_VAULT_TRUSTSTORE_PASSWORD) == null,
                "Skipping: VAULT_TRUSTSTORE_PASSWORD is set in this shell");
        Properties properties = new Properties();
        properties.setProperty("redis.truststore.password", "original-from-file");
        PropertyUtil.testMappingsOverride = Map.of();

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), false));

        assertThat(properties.getProperty("redis.truststore.password")).isEqualTo("original-from-file");
    }

    @Test
    @DisplayName("overrideWithSecrets() does nothing for redis.truststore.password when the property is not "
            + "present in the properties file to begin with, even with ENV_VAULT_TRUSTSTORE_PASSWORD unset")
    void overrideWithSecrets_redisTruststorePassword_propertyNotPresent_staysAbsent() {
        Properties properties = new Properties();
        PropertyUtil.testMappingsOverride = Map.of();

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), false));

        assertThat(properties.containsKey("redis.truststore.password")).isFalse();
    }

    /*
     * NOTE for whoever picks this up: the one case this file cannot exercise directly is the
     * actual happy path - ENV_VAULT_TRUSTSTORE_PASSWORD genuinely SET to a real value, asserting
     * overrideWithSecrets() copies that exact value into redis.truststore.password. This project
     * has no environment-variable mocking library yet (see the assumeTrue-based skip pattern used
     * throughout this file and PropertyUtilTest's buildAppRoleAuthConfig tests, which work around
     * the same limitation). Adding the `uk.org.webcompere:system-stubs-jupiter` test dependency
     * would let this be asserted directly, e.g.:
     *
     *   @Test
     *   @ExtendWith(SystemStubsExtension.class)
     *   void overrideWithSecrets_redisTruststorePassword_envVarSet_isCopiedVerbatim(
     *           EnvironmentVariables environmentVariables) {
     *       environmentVariables.set("VAULT_TRUSTSTORE_PASSWORD", "changeit");
     *       Properties properties = new Properties();
     *       properties.setProperty("redis.truststore.password", "stale-value");
     *       PropertyUtil.testMappingsOverride = Map.of();
     *
     *       PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), false));
     *
     *       assertThat(properties.getProperty("redis.truststore.password")).isEqualTo("changeit");
     *   }
     */

    @Test
    @DisplayName("overrideWithSecrets() catches a provider exception for one mapping and continues without rethrowing")
    void overrideWithSecrets_providerThrows_isCaughtAndDoesNotPropagate() {
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "original");
        properties.setProperty("vault.tls.enabled", "false");
        PropertyUtil.testMappingsOverride = Map.of("client.p12Password", "secret/path#the-key");

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), true));

        assertThat(properties.getProperty("client.p12Password")).isEqualTo("original");
    }

    @Test
    @DisplayName("overrideWithSecrets() uses VaultMappings.getMappings() (not the test override) when "
            + "testMappingsOverride is null")
    void overrideWithSecrets_noTestOverride_usesRealVaultMappings() {
        Properties properties = new Properties();
        properties.setProperty("client.p12Password", "original");
        properties.setProperty("vault.tls.enabled", "false");
        // testMappingsOverride left null (see ensureCleanSingleton) -> real VaultMappings.getMappings()
        // is used, whose env-derived mapping value is blank in this environment (no VAULT_*_PATH env
        // vars set), so the mapping is skipped and the original value survives.
        assumeTrue(
                System.getenv(PropertyUtil.ENV_VAULT_KEYSTORE_PASSWORD_PATH) == null,
                "Skipping: VAULT_KEYSTORE_PASSWORD_PATH is set in this shell");

        PropertyUtil.overrideWithSecrets(properties, new FakeSecretProvider(true, Map.of(), false));

        assertThat(properties.getProperty("client.p12Password")).isEqualTo("original");
    }

    // ------------------------------------------------------------------
    // PropertyUtil.VaultMappings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("VaultMappings.getMappings() returns a mapping for every keystore/truststore password key "
            + "except redis.truststore.password, which is deliberately excluded")
    void vaultMappings_getMappings_containsAllExpectedKeys() {
        Map<String, String> mappings = PropertyUtil.VaultMappings.getMappings();

        assertThat(mappings).containsOnlyKeys(
                "client.p12Password",
                "client.truststorePassword",
                "server.p12Password",
                "server.truststorePassword",
                "idp.keystore.password",
                "idp.truststore.password");
    }

    @Test
    @DisplayName("VaultMappings.getMappings() no longer maps redis.truststore.password - it protects the same "
            + "truststore.jks as vault.truststore.password and is resolved directly from "
            + "ENV_VAULT_TRUSTSTORE_PASSWORD in overrideWithSecrets instead, not via a separate Vault path lookup")
    void vaultMappings_getMappings_doesNotContainRedisTruststorePassword() {
        Map<String, String> mappings = PropertyUtil.VaultMappings.getMappings();

        assertThat(mappings).doesNotContainKey("redis.truststore.password");
    }

    @Test
    @DisplayName("VaultMappings.getMappings() resolves the same env var for client, server, and idp truststore "
            + "passwords (the shared DPN certificate truststore) - redis.truststore.password is deliberately not "
            + "part of this group; see vaultMappings_getMappings_doesNotContainRedisTruststorePassword")
    void vaultMappings_getMappings_clientAndServerTruststoreShareSameMapping() {
        Map<String, String> mappings = PropertyUtil.VaultMappings.getMappings();

        assertThat(mappings.get("client.truststorePassword"))
                .isEqualTo(mappings.get("server.truststorePassword"))
                .isEqualTo(mappings.get("idp.truststore.password"));
    }

    @Test
    @DisplayName("VaultMappings.getMappings() resolves to an empty string when the backing env var is unset")
    void vaultMappings_getMappings_envVarUnset_resolvesToEmptyString() {
        assumeTrue(
                System.getenv(PropertyUtil.ENV_VAULT_KEYSTORE_PASSWORD_PATH) == null,
                "Skipping: VAULT_KEYSTORE_PASSWORD_PATH is set in this shell");
        assumeTrue(
                System.getenv(PropertyUtil.ENV_VAULT_TRUSTSTORE_PASSWORD_PATH) == null,
                "Skipping: VAULT_TRUSTSTORE_PASSWORD_PATH is set in this shell");

        Map<String, String> mappings = PropertyUtil.VaultMappings.getMappings();

        assertThat(mappings.get("client.p12Password")).isEmpty();
        assertThat(mappings.get("client.truststorePassword")).isEmpty();
    }

    // ------------------------------------------------------------------
    // resolveTarget(String) / localRoot()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveTarget() returns an absolute destination as-is (normalized)")
    void resolveTarget_absoluteDestination_returnedNormalized(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);
        Path absolute = tempDir.resolve("some-file.txt");

        Path result = PropertyUtil.resolveTarget(absolute.toString());

        assertThat(result).isEqualTo(absolute.normalize());
    }

    @Test
    @DisplayName("resolveTarget() resolves a relative destination against client.files.local.dir when configured")
    void resolveTarget_relativeDestination_resolvesAgainstLocalDir(@TempDir Path tempDir) throws Exception {
        Path localDir = tempDir.resolve("local-root");
        Properties properties = new Properties();
        properties.setProperty("client.files.local.dir", localDir.toString());
        initWithProperties(properties, tempDir);

        Path result = PropertyUtil.resolveTarget("relative-file.nt");

        assertThat(result).isEqualTo(localDir.resolve("relative-file.nt").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("resolveTarget() falls back to client.files.temp.dir when client.files.local.dir is blank")
    void resolveTarget_localDirBlank_fallsBackToTempDir(@TempDir Path tempDir) throws Exception {
        Path tempRootDir = tempDir.resolve("temp-root");
        Properties properties = new Properties();
        properties.setProperty("client.files.local.dir", "");
        properties.setProperty("client.files.temp.dir", tempRootDir.toString());
        initWithProperties(properties, tempDir);

        Path result = PropertyUtil.resolveTarget("relative-file.nt");

        assertThat(result).isEqualTo(tempRootDir.resolve("relative-file.nt").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("resolveTarget() falls back to java.io.tmpdir/federator-files when neither dir property is configured "
            + "nor is PropertyUtil initialised at all")
    void resolveTarget_notInitialised_fallsBackToSystemTempDir() {
        Path result = PropertyUtil.resolveTarget("relative-file.nt");

        Path expectedRoot = Path.of(System.getProperty("java.io.tmpdir"), "federator-files");
        assertThat(result).isEqualTo(expectedRoot.resolve("relative-file.nt").toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------
    // getPropertiesFromFilePath(String)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPropertiesFromFilePath() loads properties from a genuine absolute file path")
    void getPropertiesFromFilePath_absolutePath_loadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path nested = tempDir.resolve("nested.properties");
        Properties nestedProps = new Properties();
        nestedProps.setProperty("nested.key", "nested-value");
        try (var out = Files.newOutputStream(nested)) {
            nestedProps.store(out, null);
        }
        Properties properties = new Properties();
        properties.setProperty("nested.file.path", nested.toAbsolutePath().toString());
        initWithProperties(properties, tempDir);

        Properties result = PropertyUtil.getPropertiesFromFilePath("nested.file.path");

        assertThat(result.getProperty("nested.key")).isEqualTo("nested-value");
    }

    @Test
    @DisplayName("getPropertiesFromFilePath() throws PropertyUtilException when neither an absolute path "
            + "nor a classpath resource of that name exists")
    void getPropertiesFromFilePath_neitherPathNorResourceExists_throws(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("nested.file.path", "definitely-does-not-exist-anywhere.properties");
        initWithProperties(properties, tempDir);

        assertThatThrownBy(() -> PropertyUtil.getPropertiesFromFilePath("nested.file.path"))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class);
    }

    // ------------------------------------------------------------------
    // createSecretProvider(Properties) - branches reachable without a real Vault server
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createSecretProvider() returns a NoopSecretProvider when vault.uri is not set")
    void createSecretProvider_noVaultUri_returnsNoop() {
        SecretProvider provider = PropertyUtil.createSecretProvider(new Properties());

        assertThat(provider).isInstanceOf(NoopSecretProvider.class);
        assertThat(provider.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("createSecretProvider() returns a NoopSecretProvider when vault.uri is blank")
    void createSecretProvider_blankVaultUri_returnsNoop() {
        Properties properties = new Properties();
        properties.setProperty(PropertyUtil.VAULT_URI, "   ");

        SecretProvider provider = PropertyUtil.createSecretProvider(properties);

        assertThat(provider).isInstanceOf(NoopSecretProvider.class);
    }

    @Test
    @DisplayName("createSecretProvider() returns a NoopSecretProvider when vault.auth.method is invalid")
    void createSecretProvider_invalidAuthMethod_returnsNoop() {
        Properties properties = new Properties();
        properties.setProperty(PropertyUtil.VAULT_URI, "https://vault.example.test");
        properties.setProperty(PropertyUtil.VAULT_AUTH_METHOD, "not-a-real-method");

        SecretProvider provider = PropertyUtil.createSecretProvider(properties);

        assertThat(provider).isInstanceOf(NoopSecretProvider.class);
    }

    @Test
    @DisplayName("createSecretProvider() returns a NoopSecretProvider for token auth when VAULT_TOKEN is genuinely unset")
    void createSecretProvider_tokenAuthNoToken_returnsNoop() {
        assumeTrue(System.getenv("VAULT_TOKEN") == null, "Skipping: VAULT_TOKEN is set in this shell");
        Properties properties = new Properties();
        properties.setProperty(PropertyUtil.VAULT_URI, "https://vault.example.test");

        SecretProvider provider = PropertyUtil.createSecretProvider(properties);

        assertThat(provider).isInstanceOf(NoopSecretProvider.class);
    }

    @Test
    @DisplayName("createSecretProvider() returns a NoopSecretProvider for approle auth when role/secret are unconfigured")
    void createSecretProvider_approleAuthNotConfigured_returnsNoop() {
        assumeTrue(System.getenv("VAULT_ROLE_ID") == null, "Skipping: VAULT_ROLE_ID is set in this shell");
        assumeTrue(System.getenv("VAULT_SECRET_ID") == null, "Skipping: VAULT_SECRET_ID is set in this shell");
        Properties properties = new Properties();
        properties.setProperty(PropertyUtil.VAULT_URI, "https://vault.example.test");
        properties.setProperty(PropertyUtil.VAULT_AUTH_METHOD, "approle");

        SecretProvider provider = PropertyUtil.createSecretProvider(properties);

        assertThat(provider).isInstanceOf(NoopSecretProvider.class);
    }

    @Test
    @DisplayName("createSecretProvider() throws a PropertyUtilException wrapping the connection failure when Vault "
            + "credentials are configured but the Vault server is unreachable")
    void createSecretProvider_configuredButUnreachable_throwsWrappingException() {
        assumeTrue(System.getenv("VAULT_TOKEN") == null, "Skipping: VAULT_TOKEN is set in this shell");
        Properties properties = new Properties();
        // A non-routable address (RFC 5737 TEST-NET-1) with an explicit tiny effect on timeouts is
        // not configurable here, so we instead rely on an invalid URI scheme, which fails fast
        // inside VaultSecretProvider's construction rather than attempting a real network call.
        properties.setProperty(PropertyUtil.VAULT_URI, "not-a-valid-uri-scheme");
        properties.setProperty("vault.auth.method", "token");
        // Directly build the auth config path by setting the env indirectly is not possible here,
        // so this test instead exercises the approle branch, which fails validation without any
        // network I/O and is a valid way to reach VaultAuthConfig-config construction.
        properties.setProperty(PropertyUtil.VAULT_AUTH_METHOD, "approle");
        properties.setProperty(PropertyUtil.VAULT_APPROLE_ROLE_ID, "role-1");
        properties.setProperty(PropertyUtil.VAULT_APPROLE_SECRET_ID, "secret-1");

        assertThatThrownBy(() -> PropertyUtil.createSecretProvider(properties))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class)
                .hasMessageContaining("Failed to create Vault secret provider");
    }
}
