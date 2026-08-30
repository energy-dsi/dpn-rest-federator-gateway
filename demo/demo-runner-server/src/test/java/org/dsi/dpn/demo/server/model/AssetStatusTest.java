// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.demo.server.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AssetStatusTest {

    @Test @DisplayName("wire value round-trips (by display string and by enum name, case-insensitive)")
    void fromWireValue() {
        assertThat(AssetStatus.ENERGISED.getWireValue()).isEqualTo("Energised");
        assertThat(AssetStatus.fromWireValue("Energised")).isEqualTo(AssetStatus.ENERGISED);
        assertThat(AssetStatus.fromWireValue("energised")).isEqualTo(AssetStatus.ENERGISED);
        assertThat(AssetStatus.fromWireValue("ENERGISED")).isEqualTo(AssetStatus.ENERGISED);
        assertThat(AssetStatus.fromWireValue("Awaiting Energisation"))
                .isEqualTo(AssetStatus.AWAITING_ENERGISATION);
    }

    @Test @DisplayName("null maps to null; unknown throws")
    void nullAndUnknown() {
        assertThat(AssetStatus.fromWireValue(null)).isNull();
        assertThatThrownBy(() -> AssetStatus.fromWireValue("Nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nonsense");
    }

    @Test @DisplayName("only AWAITING_ENERGISATION and ENERGISED are valid for registration")
    void validForRegistration() {
        assertThat(AssetStatus.AWAITING_ENERGISATION.isValidForRegistration()).isTrue();
        assertThat(AssetStatus.ENERGISED.isValidForRegistration()).isTrue();
        assertThat(AssetStatus.SPECULATIVE.isValidForRegistration()).isFalse();
        assertThat(AssetStatus.WITHDRAWN.isValidForRegistration()).isFalse();
    }
}
