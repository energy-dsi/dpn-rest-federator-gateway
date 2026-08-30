// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the common exception hierarchy. */
class ExceptionsTest {

    @Test @DisplayName("AesCryptographicOperationException carries message and cause")
    void aesException() {
        var cause = new IllegalStateException("boom");
        var msgOnly = new AesCryptographicOperationException("bad");
        var withCause = new AesCryptographicOperationException("bad", cause);
        assertThat(msgOnly).isInstanceOf(RuntimeException.class).hasMessage("bad");
        assertThat(withCause).hasMessage("bad").hasCause(cause);
    }

    @Test @DisplayName("FederatorSslException carries message and cause")
    void sslException() {
        var cause = new RuntimeException("x");
        assertThat(new FederatorSslException("ssl")).hasMessage("ssl");
        assertThat(new FederatorSslException("ssl", cause)).hasMessage("ssl").hasCause(cause);
    }

    @Test @DisplayName("FederatorTokenException is rebuildable and returns its own type")
    void tokenExceptionRebuild() {
        var cause = new RuntimeException("orig");
        FederatorTokenException e = new FederatorTokenException("first");
        assertThat(e).isInstanceOf(RebuildableRuntimeException.class).hasMessage("first");

        RebuildableRuntimeException rebuilt = e.rebuild("enriched", cause);
        assertThat(rebuilt)
                .isInstanceOf(FederatorTokenException.class)
                .hasMessage("enriched")
                .hasCause(cause);
    }
}
