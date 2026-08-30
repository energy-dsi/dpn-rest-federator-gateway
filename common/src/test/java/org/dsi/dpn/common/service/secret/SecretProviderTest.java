// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretProviderTest {

    @Test @DisplayName("NoopSecretProvider returns null for any path/key")
    void noopReturnsNull() {
        SecretProvider provider = new NoopSecretProvider();
        assertThat(provider.getSecret("node-net/client/certificate", "certificate")).isNull();
        assertThat(provider.getSecret("anything", "any-key")).isNull();
    }
}
