// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoopSecretProvider}, the fallback {@link SecretProvider} used when Vault
 * is not configured.
 */
class NoopSecretProviderTest {

    private final NoopSecretProvider provider = new NoopSecretProvider();

    @Test
    @DisplayName("getSecret always returns null, regardless of path/key")
    void getSecret_alwaysNull() {
        assertThat(provider.getSecret("node-net/client/certificate", "certificate")).isNull();
        assertThat(provider.getSecret(null, null)).isNull();
        assertThat(provider.getSecret("", "")).isNull();
    }

    @Test
    @DisplayName("isEnabled uses the SecretProvider default of false (not overridden)")
    void isEnabled_defaultsFalse() {
        assertThat(provider.isEnabled()).isFalse();
    }
}
