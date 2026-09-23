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
 * Further additional unit tests for {@link PropertyUtil} - covers the remaining
 * <em>partially</em>-covered methods the JaCoCo report identified: {@code firstNonBlank},
 * {@code readSecretIdFromFile}, {@code buildAppRoleAuthConfig}, {@code safeProp}, and
 * {@code getPropertyFileValue}. Together with {@code PropertyUtilAdditionalTest}, this completes
 * coverage of every method the report flagged as under-tested.
 */
class PropertyUtilFurtherAdditionalTest {

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
    // firstNonBlank(String...) - private varargs, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("firstNonBlank() returns the first non-null, non-blank value in the list")
    void firstNonBlank_firstValuePresent_returnsIt() throws Exception {
        Object result = invokeStatic("firstNonBlank", new Class<?>[] {String[].class}, (Object) new String[] {
            "first", "second"
        });

        assertThat(result).isEqualTo("first");
    }

    @Test
    @DisplayName("firstNonBlank() skips null and blank leading values, returning the first genuine one")
    void firstNonBlank_leadingValuesNullOrBlank_skipsToFirstGenuine() throws Exception {
        Object result = invokeStatic("firstNonBlank", new Class<?>[] {String[].class}, (Object) new String[] {
            null, "   ", "third"
        });

        assertThat(result).isEqualTo("third");
    }

    @Test
    @DisplayName("firstNonBlank() returns null when every value is null or blank")
    void firstNonBlank_allValuesNullOrBlank_returnsNull() throws Exception {
        Object result = invokeStatic("firstNonBlank", new Class<?>[] {String[].class}, (Object) new String[] {
            null, "   ", ""
        });

        assertThat(result).isNull();
    }

    // ------------------------------------------------------------------
    // readSecretIdFromFile(String) - private, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("readSecretIdFromFile() returns null when the path is null")
    void readSecretIdFromFile_nullPath_returnsNull() throws Exception {
        Object result = invokeStatic("readSecretIdFromFile", new Class<?>[] {String.class}, (String) null);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("readSecretIdFromFile() returns null when the path is blank")
    void readSecretIdFromFile_blankPath_returnsNull() throws Exception {
        Object result = invokeStatic("readSecretIdFromFile", new Class<?>[] {String.class}, "   ");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("readSecretIdFromFile() reads and trims the contents of a genuine file")
    void readSecretIdFromFile_realFile_readsAndTrims(@TempDir Path tempDir) throws Exception {
        Path secretFile = tempDir.resolve("secret-id");
        Files.writeString(secretFile, "  the-secret-id-value  \n");

        Object result = invokeStatic("readSecretIdFromFile", new Class<?>[] {String.class}, secretFile.toString());

        assertThat(result).isEqualTo("the-secret-id-value");
    }

    @Test
    @DisplayName("readSecretIdFromFile() returns null (not throwing) when the file does not exist")
    void readSecretIdFromFile_missingFile_returnsNull(@TempDir Path tempDir) throws Exception {
        Object result = invokeStatic(
                "readSecretIdFromFile", new Class<?>[] {String.class}, tempDir.resolve("does-not-exist").toString());

        assertThat(result).isNull();
    }

    // ------------------------------------------------------------------
    // buildAppRoleAuthConfig(Properties) - private, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildAppRoleAuthConfig() returns null when neither env var nor property "
            + "supplies a role-id, even if secret-id is present")
    void buildAppRoleAuthConfig_missingRoleId_returnsNull() throws Exception {
        assumeTrue(System.getenv("VAULT_ROLE_ID") == null, "Skipping: VAULT_ROLE_ID is set in this shell");
        assumeTrue(System.getenv("VAULT_SECRET_ID") == null, "Skipping: VAULT_SECRET_ID is set in this shell");
        Properties properties = new Properties();
        properties.setProperty("vault.approle.secret-id", "some-secret-id");

        Object result = invokeStatic("buildAppRoleAuthConfig", new Class<?>[] {Properties.class}, properties);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("buildAppRoleAuthConfig() returns null when role-id is present but secret-id is missing entirely")
    void buildAppRoleAuthConfig_missingSecretId_returnsNull() throws Exception {
        assumeTrue(System.getenv("VAULT_ROLE_ID") == null, "Skipping: VAULT_ROLE_ID is set in this shell");
        assumeTrue(System.getenv("VAULT_SECRET_ID") == null, "Skipping: VAULT_SECRET_ID is set in this shell");
        Properties properties = new Properties();
        properties.setProperty("vault.approle.role-id", "some-role-id");

        Object result = invokeStatic("buildAppRoleAuthConfig", new Class<?>[] {Properties.class}, properties);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("buildAppRoleAuthConfig() builds a real VaultAuthConfig when both role-id and "
            + "secret-id are supplied via properties, using the default mount path when unset")
    void buildAppRoleAuthConfig_bothPropertiesPresent_buildsConfigWithDefaultMountPath() throws Exception {
        assumeTrue(System.getenv("VAULT_ROLE_ID") == null, "Skipping: VAULT_ROLE_ID is set in this shell");
        assumeTrue(System.getenv("VAULT_SECRET_ID") == null, "Skipping: VAULT_SECRET_ID is set in this shell");
        Properties properties = new Properties();
        properties.setProperty("vault.approle.role-id", "some-role-id");
        properties.setProperty("vault.approle.secret-id", "some-secret-id");

        Object result = invokeStatic("buildAppRoleAuthConfig", new Class<?>[] {Properties.class}, properties);

        assertThat(result).isInstanceOf(VaultAuthConfig.class);
        VaultAuthConfig config = (VaultAuthConfig) result;
        assertThat(config.getRoleId()).isEqualTo("some-role-id");
        assertThat(config.getApproleMountPath()).isEqualTo(VaultAuthConfig.DEFAULT_APPROLE_MOUNT_PATH);
    }

    @Test
    @DisplayName("buildAppRoleAuthConfig() uses a custom mount path property when supplied")
    void buildAppRoleAuthConfig_customMountPath_isUsed() throws Exception {
        assumeTrue(System.getenv("VAULT_ROLE_ID") == null, "Skipping: VAULT_ROLE_ID is set in this shell");
        assumeTrue(System.getenv("VAULT_SECRET_ID") == null, "Skipping: VAULT_SECRET_ID is set in this shell");
        Properties properties = new Properties();
        properties.setProperty("vault.approle.role-id", "some-role-id");
        properties.setProperty("vault.approle.secret-id", "some-secret-id");
        properties.setProperty("vault.approle.mount-path", "custom-approle-mount");

        Object result = invokeStatic("buildAppRoleAuthConfig", new Class<?>[] {Properties.class}, properties);

        VaultAuthConfig config = (VaultAuthConfig) result;
        assertThat(config.getApproleMountPath()).isEqualTo("custom-approle-mount");
    }

    @Test
    @DisplayName("buildAppRoleAuthConfig() reads the secret-id from a file path property when "
            + "no env var or direct property is set")
    void buildAppRoleAuthConfig_secretIdFromFile_isRead(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getenv("VAULT_ROLE_ID") == null, "Skipping: VAULT_ROLE_ID is set in this shell");
        assumeTrue(System.getenv("VAULT_SECRET_ID") == null, "Skipping: VAULT_SECRET_ID is set in this shell");
        Path secretFile = tempDir.resolve("secret-id");
        Files.writeString(secretFile, "secret-id-from-file");
        Properties properties = new Properties();
        properties.setProperty("vault.approle.role-id", "some-role-id");
        properties.setProperty("vault.approle.secret-id-path", secretFile.toString());

        Object result = invokeStatic("buildAppRoleAuthConfig", new Class<?>[] {Properties.class}, properties);

        assertThat(result).isInstanceOf(VaultAuthConfig.class);
    }

    // ------------------------------------------------------------------
    // safeProp(String) - private, via reflection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("safeProp() returns the trimmed value when the key is present")
    void safeProp_presentKey_returnsTrimmedValue(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("my.key", "  padded-value  ");
        initWithProperties(properties, tempDir);

        Object result = invokeStatic("safeProp", new Class<?>[] {String.class}, "my.key");

        assertThat(result).isEqualTo("padded-value");
    }

    @Test
    @DisplayName("safeProp() returns an empty string, not throwing, when the key is genuinely absent")
    void safeProp_missingKey_returnsEmptyString(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);

        Object result = invokeStatic("safeProp", new Class<?>[] {String.class}, "does.not.exist");

        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("safeProp() returns an empty string, not throwing, when PropertyUtil is not initialised at all")
    void safeProp_notInitialised_returnsEmptyString() throws Exception {
        Object result = invokeStatic("safeProp", new Class<?>[] {String.class}, "any.key");

        assertThat(result).isEqualTo("");
    }

    // ------------------------------------------------------------------
    // getPropertyFileValue(String) - public, direct call
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPropertyFileValue() throws PropertyUtilException when the property's value "
            + "names a classpath resource that does not exist")
    void getPropertyFileValue_resourceDoesNotExist_throws(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("my.file.key", "this-resource-does-not-exist.txt");
        initWithProperties(properties, tempDir);

        assertThatThrownBy(() -> PropertyUtil.getPropertyFileValue("my.file.key"))
                .isInstanceOf(PropertyUtil.PropertyUtilException.class);
    }

    @Test
    @DisplayName("getPropertyFileValue() throws when the key itself is missing")
    void getPropertyFileValue_missingKey_throws(@TempDir Path tempDir) throws Exception {
        initWithProperties(new Properties(), tempDir);

        assertThatThrownBy(() -> PropertyUtil.getPropertyFileValue("does.not.exist"))
                .isInstanceOf(RuntimeException.class);
    }
}
