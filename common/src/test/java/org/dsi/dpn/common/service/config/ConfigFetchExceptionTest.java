// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.dsi.dpn.common.exception.RebuildableRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigFetchExceptionTest {

    @Test @DisplayName("carries message and cause")
    void messageAndCause() {
        var cause = new RuntimeException("orig");
        assertThat(new ConfigFetchException("fetch failed")).hasMessage("fetch failed");
        assertThat(new ConfigFetchException("fetch failed", cause))
                .hasMessage("fetch failed").hasCause(cause);
    }

    @Test @DisplayName("rebuild returns a ConfigFetchException with the enriched message/cause")
    void rebuild() {
        var cause = new RuntimeException("orig");
        RebuildableRuntimeException rebuilt = new ConfigFetchException("first").rebuild("enriched", cause);
        assertThat(rebuilt)
                .isInstanceOf(ConfigFetchException.class)
                .hasMessage("enriched")
                .hasCause(cause);
    }
}
