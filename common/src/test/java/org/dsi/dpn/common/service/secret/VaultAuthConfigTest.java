// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VaultAuthConfigTest {

    @Test @DisplayName("forToken builds a TOKEN config")
    void forToken() {
        VaultAuthConfig c = VaultAuthConfig.forToken("s.token");
        assertThat(c.getMethod()).isEqualTo(VaultAuthMethod.TOKEN);
        assertThat(c.isAppRole()).isFalse();
        assertThat(c.getToken()).isEqualTo("s.token");
        assertThat(c.getRenewalIntervalSeconds())
                .isEqualTo(VaultAuthConfig.DEFAULT_RENEWAL_INTERVAL_SECONDS);
    }

    @Test @DisplayName("forAppRole (2-arg) applies default mount path and interval")
    void forAppRoleDefaults() {
        VaultAuthConfig c = VaultAuthConfig.forAppRole("role", "secret");
        assertThat(c.getMethod()).isEqualTo(VaultAuthMethod.APPROLE);
        assertThat(c.isAppRole()).isTrue();
        assertThat(c.getRoleId()).isEqualTo("role");
        assertThat(c.getSecretId()).isEqualTo("secret");
        assertThat(c.getApproleMountPath()).isEqualTo(VaultAuthConfig.DEFAULT_APPROLE_MOUNT_PATH);
        assertThat(c.getRenewalIntervalSeconds())
                .isEqualTo(VaultAuthConfig.DEFAULT_RENEWAL_INTERVAL_SECONDS);
    }

    @Test @DisplayName("forAppRole (4-arg) honours explicit mount path and interval")
    void forAppRoleExplicit() {
        VaultAuthConfig c = VaultAuthConfig.forAppRole("role", "secret", "custom", 3600L);
        assertThat(c.getApproleMountPath()).isEqualTo("custom");
        assertThat(c.getRenewalIntervalSeconds()).isEqualTo(3600L);
    }

    @Test @DisplayName("blank mount path / non-positive interval fall back to defaults")
    void forAppRoleFallbacks() {
        VaultAuthConfig c = VaultAuthConfig.forAppRole("role", "secret", "  ", 0L);
        assertThat(c.getApproleMountPath()).isEqualTo(VaultAuthConfig.DEFAULT_APPROLE_MOUNT_PATH);
        assertThat(c.getRenewalIntervalSeconds())
                .isEqualTo(VaultAuthConfig.DEFAULT_RENEWAL_INTERVAL_SECONDS);
    }
}
