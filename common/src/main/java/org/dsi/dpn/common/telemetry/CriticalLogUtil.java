// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Logs at CRITICAL/FATAL severity, working around the fact that neither java.util.logging nor
 * Logback has a native FATAL level (only ERROR/WARN/INFO/DEBUG/TRACE).
 *
 * <p>Mechanism: sets a control MDC key ({@value #SEVERITY_OVERRIDE_MDC_KEY}) immediately before
 * logging at ERROR, then clears it immediately after — a downstream layout/appender can check for
 * this key and emit severity_number=21/"FATAL" instead of the usual ERROR mapping.
 *
 * <p>Use for conditions that mean "this service instance is going down" — startup failures that
 * prevent the process from running at all, and unexpected/abnormal shutdown — distinct from
 * ordinary recoverable ERROR-level failures.
 *
 * <p>Ported from dpn-federator's telemetry package (same class, same behaviour).
 */
public final class CriticalLogUtil {

    static final String SEVERITY_OVERRIDE_MDC_KEY = "severity.override";
    static final String SEVERITY_OVERRIDE_FATAL_VALUE = "FATAL";

    private CriticalLogUtil() {}

    public static void logCritical(Logger log, String message) {
        MDC.put(SEVERITY_OVERRIDE_MDC_KEY, SEVERITY_OVERRIDE_FATAL_VALUE);
        try {
            log.error(message);
        } finally {
            MDC.remove(SEVERITY_OVERRIDE_MDC_KEY);
        }
    }

    public static void logCritical(Logger log, String message, Throwable t) {
        MDC.put(SEVERITY_OVERRIDE_MDC_KEY, SEVERITY_OVERRIDE_FATAL_VALUE);
        try {
            log.error(message, t);
        } finally {
            MDC.remove(SEVERITY_OVERRIDE_MDC_KEY);
        }
    }
}
