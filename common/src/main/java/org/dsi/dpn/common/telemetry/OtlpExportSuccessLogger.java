// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the already-fully-configured OTLP exporters (HTTPS truststore and/or OAuth Bearer token
 * already applied, if enabled - see {@link OtlpTruststoreSupport}/{@link OtelBearerTokenSupport})
 * to log a single, explicit confirmation the first time an export to the Collector genuinely
 * succeeds.
 *
 * <p>Without this, the only telemetry-related log lines at runtime are either purely declarative
 * ("OTLP export will trust..."/"...will authenticate...", logged once at startup regardless of
 * whether the Collector is ever actually reachable) or failure-only (see
 * {@link CollectorUnavailableLogHandler}) - there was no positive, in-application proof that an
 * export over HTTPS with the Bearer token attached had actually been accepted by the Collector,
 * short of independently reproducing the request (curl) or reasoning from the absence of failure
 * warnings over time.
 *
 * <p>Logs only once per exporter (trace/metric/log are wrapped and tracked separately) to avoid
 * spamming this line on every batch - the point is proof-of-connectivity, not a running tally.
 */
final class OtlpExportSuccessLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtlpExportSuccessLogger.class);

    private OtlpExportSuccessLogger() {}

    static SpanExporter wrap(SpanExporter delegate) {
        return new LoggingSpanExporter(delegate);
    }

    static MetricExporter wrap(MetricExporter delegate) {
        return new LoggingMetricExporter(delegate);
    }

    static LogRecordExporter wrap(LogRecordExporter delegate) {
        return new LoggingLogRecordExporter(delegate);
    }

    private static void logFirstSuccess(AtomicBoolean alreadyLogged, String signalType, CompletableResultCode result) {
        result.whenComplete(() -> {
            if (result.isSuccess() && alreadyLogged.compareAndSet(false, true)) {
                LOGGER.info(
                        "OTLP {} export to the Collector succeeded - HTTPS trust and/or OAuth "
                                + "Bearer token (whichever is enabled) are confirmed working end-to-end "
                                + "(further successful exports are not logged individually).",
                        signalType);
            }
        });
    }

    private static final class LoggingSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final AtomicBoolean logged = new AtomicBoolean(false);

        private LoggingSpanExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            logFirstSuccess(logged, "trace", result);
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    private static final class LoggingMetricExporter implements MetricExporter {
        private final MetricExporter delegate;
        private final AtomicBoolean logged = new AtomicBoolean(false);

        private LoggingMetricExporter(MetricExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return delegate.getAggregationTemporality(instrumentType);
        }

        @Override
        public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
            return delegate.getDefaultAggregation(instrumentType);
        }

        @Override
        public MemoryMode getMemoryMode() {
            return delegate.getMemoryMode();
        }

        @Override
        public CompletableResultCode export(Collection<MetricData> metrics) {
            CompletableResultCode result = delegate.export(metrics);
            logFirstSuccess(logged, "metrics", result);
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    private static final class LoggingLogRecordExporter implements LogRecordExporter {
        private final LogRecordExporter delegate;
        private final AtomicBoolean logged = new AtomicBoolean(false);

        private LoggingLogRecordExporter(LogRecordExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            CompletableResultCode result = delegate.export(logs);
            logFirstSuccess(logged, "logs", result);
            return result;
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }
}
