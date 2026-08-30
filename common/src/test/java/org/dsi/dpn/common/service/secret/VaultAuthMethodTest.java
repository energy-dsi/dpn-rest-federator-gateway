// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VaultAuthMethodTest {

    @Test @DisplayName("null or blank defaults to TOKEN")
    void defaultsToToken() {
        assertThat(VaultAuthMethod.fromString(null)).isEqualTo(VaultAuthMethod.TOKEN);
        assertThat(VaultAuthMethod.fromString("")).isEqualTo(VaultAuthMethod.TOKEN);
        assertThat(VaultAuthMethod.fromString("   ")).isEqualTo(VaultAuthMethod.TOKEN);
    }

    @Test @DisplayName("case-insensitive and trimmed parsing")
    void parsesKnownValues() {
        assertThat(VaultAuthMethod.fromString("token")).isEqualTo(VaultAuthMethod.TOKEN);
        assertThat(VaultAuthMethod.fromString("APPROLE")).isEqualTo(VaultAuthMethod.APPROLE);
        assertThat(VaultAuthMethod.fromString("  approle ")).isEqualTo(VaultAuthMethod.APPROLE);
    }

    @Test @DisplayName("unknown value throws IllegalArgumentException")
    void unknownThrows() {
        assertThatThrownBy(() -> VaultAuthMethod.fromString("kerberos"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
