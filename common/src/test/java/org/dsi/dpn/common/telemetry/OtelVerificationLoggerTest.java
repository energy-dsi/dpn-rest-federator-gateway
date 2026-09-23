// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Unit tests for {@link OtelVerificationLogger} - previously entirely untested.
 * <p>
 * Rather than stub or verify SLF4J's overloaded, varargs {@code info}/{@code error} methods
 * with argument matchers (fragile to get exactly right for a varargs signature), this test
 * applies a single default {@link org.mockito.stubbing.Answer} to the whole mock and inspects
 * each invocation's method name directly - unambiguous regardless of which overload SLF4J
 * resolves to at the call site.
 * </p>
 */
class OtelVerificationLoggerTest {

    private static final String ATTR_CLIENT_ID = "dpn.certificate.client_id";
    private static final String ATTR_TIMESTAMP = "dpn.certificate.verification_timestamp";
    private static final String ATTR_STATUS = "dpn.certificate.verification_status";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private long countInvocationsOfMethod(Logger mockLogger, String methodName) {
        return mockingDetails(mockLogger).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(methodName))
                .count();
    }

    @Test
    @DisplayName("log() with active=true calls exactly one Logger method, and it is info(), not error()")
    void log_active_callsInfoNotError() {
        Logger logger = mock(Logger.class);

        OtelVerificationLogger.log(logger, "consumer-1", Instant.parse("2026-09-23T10:00:00Z"), true, "ACTIVE");

        assertThat(countInvocationsOfMethod(logger, "info")).isEqualTo(1);
        assertThat(countInvocationsOfMethod(logger, "error")).isZero();
    }

    @Test
    @DisplayName("log() with active=false calls exactly one Logger method, and it is error(), not info()")
    void log_inactive_callsErrorNotInfo() {
        Logger logger = mock(Logger.class);

        OtelVerificationLogger.log(logger, "consumer-1", Instant.parse("2026-09-23T10:00:00Z"), false, "REVOKED");

        assertThat(countInvocationsOfMethod(logger, "error")).isEqualTo(1);
        assertThat(countInvocationsOfMethod(logger, "info")).isZero();
    }

    @Test
    @DisplayName("log() passes the clientId, status, and timestamp through as arguments to the delegated call")
    void log_passesArgumentsThrough() {
        Logger logger = mock(Logger.class);
        Instant timestamp = Instant.parse("2026-09-23T10:00:00Z");

        OtelVerificationLogger.log(logger, "consumer-1", timestamp, true, "ACTIVE");

        Object[] arguments = mockingDetails(logger).getInvocations().iterator().next().getArguments();
        assertThat(arguments).contains("ACTIVE", "consumer-1", timestamp);
    }

    @Test
    @DisplayName("log() sets all three MDC attributes during the delegated log call, using a mock "
            + "with a default answer that captures MDC state at invocation time")
    void log_setsMdcAttributesDuringCall() {
        String[] clientIdDuringCall = new String[1];
        String[] statusDuringCall = new String[1];
        String[] timestampDuringCall = new String[1];
        Logger logger = mock(
                Logger.class,
                invocation -> {
                    if ("info".equals(invocation.getMethod().getName())) {
                        clientIdDuringCall[0] = MDC.get(ATTR_CLIENT_ID);
                        statusDuringCall[0] = MDC.get(ATTR_STATUS);
                        timestampDuringCall[0] = MDC.get(ATTR_TIMESTAMP);
                    }
                    return null;
                });

        Instant timestamp = Instant.parse("2026-09-23T10:00:00Z");
        OtelVerificationLogger.log(logger, "consumer-1", timestamp, true, "ACTIVE");

        assertThat(clientIdDuringCall[0]).isEqualTo("consumer-1");
        assertThat(statusDuringCall[0]).isEqualTo("ACTIVE");
        assertThat(timestampDuringCall[0]).isEqualTo(timestamp.toString());
    }

    @Test
    @DisplayName("log() removes all three MDC attributes after the call completes")
    void log_removesMdcAttributesAfterCall() {
        Logger logger = mock(Logger.class);

        OtelVerificationLogger.log(logger, "consumer-1", Instant.now(), true, "ACTIVE");

        assertThat(MDC.get(ATTR_CLIENT_ID)).isNull();
        assertThat(MDC.get(ATTR_TIMESTAMP)).isNull();
        assertThat(MDC.get(ATTR_STATUS)).isNull();
    }

    @Test
    @DisplayName("log() removes MDC attributes even when the logger itself throws")
    void log_loggerThrows_stillRemovesMdcAttributes() {
        Logger logger = mock(Logger.class, invocation -> {
            throw new RuntimeException("logging backend unavailable");
        });

        assertThatThrownBy(() -> OtelVerificationLogger.log(logger, "consumer-1", Instant.now(), true, "ACTIVE"))
                .isInstanceOf(RuntimeException.class);

        assertThat(MDC.get(ATTR_CLIENT_ID)).isNull();
        assertThat(MDC.get(ATTR_TIMESTAMP)).isNull();
        assertThat(MDC.get(ATTR_STATUS)).isNull();
    }
}
