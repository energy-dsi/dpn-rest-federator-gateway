// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts the OpenTelemetry SDK's internal java.util.logging (JUL) records — specifically the
 * ones emitted when an OTLP export attempt fails because the collector is unreachable (DNS
 * failure, connection refused, timeout, etc.) — and replaces them with a single short
 * application log line instead of the full exception + stack trace.
 *
 * <p>Ported from dpn-federator's telemetry package (same class, same behaviour).
 *
 * <p>Only records that look like an export-connectivity failure are intercepted; anything else
 * from the {@code io.opentelemetry} logger passes through to a normal {@link ConsoleHandler}
 * unchanged.
 */
final class CollectorUnavailableLogHandler extends Handler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectorUnavailableLogHandler.class);

    private final ConsoleHandler fallback = new ConsoleHandler();

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }

        if (isExportConnectivityFailure(record)) {
            LOGGER.warn("OTel Collector export failed - telemetry is being dropped until connectivity is restored.");
            return;
        }

        fallback.publish(record);
    }

    private static boolean isExportConnectivityFailure(LogRecord record) {
        Throwable thrown = record.getThrown();
        if (thrown instanceof UnknownHostException
                || thrown instanceof ConnectException
                || thrown instanceof SocketTimeoutException) {
            return true;
        }
        Throwable cause = thrown == null ? null : thrown.getCause();
        if (cause instanceof UnknownHostException
                || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException) {
            return true;
        }
        String message = record.getMessage();
        return message != null && message.contains("Failed to export");
    }

    @Override
    public void flush() {
        fallback.flush();
    }

    @Override
    public void close() {
        fallback.close();
    }
}
