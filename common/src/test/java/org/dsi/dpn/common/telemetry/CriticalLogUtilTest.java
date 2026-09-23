// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Unit tests for {@link CriticalLogUtil} - previously entirely untested, despite being used
 * directly by {@link OpenTelemetryConfig}'s own shutdown-hook logging (visible in this
 * project's real logs as {@code "... process is shutting down"} at ERROR level).
 * <p>
 * Uses a mocked {@link Logger} with a captured-at-call-time check of {@link MDC#get} to verify
 * the severity-override key is genuinely set <em>during</em> the delegated log call, not merely
 * before or after it - and confirms the key is removed afterward even when the logger itself
 * throws, via the class's own {@code try/finally}.
 * </p>
 */
class CriticalLogUtilTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("logCritical(log, message) sets the severity-override MDC key to FATAL "
            + "during the delegated log.error call")
    void logCritical_messageOnly_setsMdcKeyDuringLogCall() {
        Logger logger = mock(Logger.class);
        String[] mdcValueDuringCall = new String[1];
        doAnswer(invocation -> {
                    mdcValueDuringCall[0] = MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY);
                    return null;
                })
                .when(logger)
                .error("service is shutting down");

        CriticalLogUtil.logCritical(logger, "service is shutting down");

        assertThat(mdcValueDuringCall[0]).isEqualTo(CriticalLogUtil.SEVERITY_OVERRIDE_FATAL_VALUE);
        verify(logger).error("service is shutting down");
    }

    @Test
    @DisplayName("logCritical(log, message) removes the severity-override MDC key after the call completes")
    void logCritical_messageOnly_removesMdcKeyAfterCall() {
        Logger logger = mock(Logger.class);

        CriticalLogUtil.logCritical(logger, "service is shutting down");

        assertThat(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("logCritical(log, message) removes the MDC key even when the logger itself throws")
    void logCritical_messageOnly_loggerThrows_stillRemovesMdcKey() {
        Logger logger = mock(Logger.class);
        doThrow(new RuntimeException("logging backend unavailable")).when(logger).error("boom");

        assertThatThrownBy(() -> CriticalLogUtil.logCritical(logger, "boom"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("logging backend unavailable");
        assertThat(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("logCritical(log, message, throwable) sets the severity-override MDC key "
            + "during the delegated log.error(message, throwable) call")
    void logCritical_withThrowable_setsMdcKeyDuringLogCall() {
        Logger logger = mock(Logger.class);
        Throwable cause = new IllegalStateException("startup failure");
        String[] mdcValueDuringCall = new String[1];
        doAnswer(invocation -> {
                    mdcValueDuringCall[0] = MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY);
                    return null;
                })
                .when(logger)
                .error("startup failed", cause);

        CriticalLogUtil.logCritical(logger, "startup failed", cause);

        assertThat(mdcValueDuringCall[0]).isEqualTo(CriticalLogUtil.SEVERITY_OVERRIDE_FATAL_VALUE);
        verify(logger).error("startup failed", cause);
    }

    @Test
    @DisplayName("logCritical(log, message, throwable) removes the MDC key after the call completes")
    void logCritical_withThrowable_removesMdcKeyAfterCall() {
        Logger logger = mock(Logger.class);
        Throwable cause = new IllegalStateException("startup failure");

        CriticalLogUtil.logCritical(logger, "startup failed", cause);

        assertThat(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("logCritical(log, message, throwable) removes the MDC key even when the logger itself throws")
    void logCritical_withThrowable_loggerThrows_stillRemovesMdcKey() {
        Logger logger = mock(Logger.class);
        Throwable cause = new IllegalStateException("startup failure");
        doThrow(new RuntimeException("logging backend unavailable")).when(logger).error("boom", cause);

        assertThatThrownBy(() -> CriticalLogUtil.logCritical(logger, "boom", cause))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("logging backend unavailable");
        assertThat(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("logCritical does not leak its MDC key into a value that was already set before the call, "
            + "beyond correctly restoring the absence of that key")
    void logCritical_noPriorMdcState_leavesNoTraceAfterward() {
        Logger logger = mock(Logger.class);
        assertThat(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY)).isNull();

        CriticalLogUtil.logCritical(logger, "message");

        assertThat(MDC.get(CriticalLogUtil.SEVERITY_OVERRIDE_MDC_KEY)).isNull();
    }
}
