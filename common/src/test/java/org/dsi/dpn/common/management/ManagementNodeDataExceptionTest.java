// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.management;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManagementNodeDataExceptionTest {

    @Test @DisplayName("carries message and cause")
    void messageAndCause() {
        var cause = new RuntimeException("io");
        assertThat(new ManagementNodeDataException("failed"))
                .isInstanceOf(RuntimeException.class).hasMessage("failed");
        assertThat(new ManagementNodeDataException("failed", cause))
                .hasMessage("failed").hasCause(cause);
    }
}
