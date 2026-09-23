// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers two branches of {@link PropertyUtil}'s private constructor that JaCoCo showed as
 * uncovered even after {@code PropertyUtilVaultAndPrefixTest}: the {@code common.configuration}
 * loading failure being caught and logged rather than propagated (constructor lines ~136-139),
 * and the default (non-test-override) {@code createSecretProvider(commonProperties)} call path
 * (constructor line ~144), which every other test bypasses via
 * {@code PropertyUtil.providerOverrideForTest}.
 */
class PropertyUtilConstructorPathsTest {

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

    @Test
    @DisplayName("init() tolerates a common.configuration value that cannot be loaded, logging a warning "
            + "rather than failing initialisation")
    void init_commonConfigPointsToUnloadableFile_isSkippedNotThrown(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("common.configuration", "definitely-does-not-exist-anywhere.properties");
        properties.setProperty("some.other.key", "some-value");
        // Deliberately NOT setting providerOverrideForTest, so the constructor takes the real
        // createSecretProvider(commonProperties) path too (commonProperties will be empty here,
        // since loading it failed) - covers both target branches in a single real PropertyUtil()
        // construction. No vault.uri is set anywhere, so createSecretProvider still degrades to a
        // fast NoopSecretProvider with no network I/O.
        File file = tempDir.resolve("test.properties").toFile();
        try (var out = Files.newOutputStream(file.toPath())) {
            properties.store(out, null);
        }

        PropertyUtil.init(file);

        assertThat(PropertyUtil.getPropertyValue("some.other.key")).isEqualTo("some-value");
    }

    @Test
    @DisplayName("init() with no common.configuration property set still completes via the default "
            + "createSecretProvider(commonProperties) path")
    void init_noCommonConfigProperty_usesDefaultSecretProviderPath(@TempDir Path tempDir) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("some.key", "some-value");
        // Again no providerOverrideForTest - exercises the real createSecretProvider() call made
        // directly from the constructor.
        File file = tempDir.resolve("test.properties").toFile();
        try (var out = Files.newOutputStream(file.toPath())) {
            properties.store(out, null);
        }

        PropertyUtil.init(file);

        assertThat(PropertyUtil.getPropertyValue("some.key")).isEqualTo("some-value");
    }
}
