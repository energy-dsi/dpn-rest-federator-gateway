// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the already-fully-configured OTLP exporters (HTTPS truststore and/or OAuth Bearer token
 * already applied, if enabled - see {@link OtlpTruststoreSupport}/{@link OtelBearerTokenSupport})
 * to log an explicit confirmation on every successful export to the Collector - not just the
 * first one - so that each demo-runner-client-triggered run produces its own, independently
 * visible confirmation line, carrying the exported batch's {@code service.name} and signal type
 * and count, rather than a single one-off line emitted once per gateway process lifetime.
 *
 * <p>Without this, the only telemetry-related log lines at runtime are either purely declarative
 * ("OTLP export will trust..."/"...will authenticate...", logged once at startup regardless of
 * whether the Collector is ever actually reachable) or failure-only (see
 * {@link CollectorUnavailableLogHandler}) - there was no positive, in-application proof, tied to
 * a specific export, that a batch had actually been accepted by the Collector, short of
 * independently reproducing the request (curl) or reasoning from the absence of failure warnings
 * over time.
 *
 * <p>Logs once per successful {@code export(...)} call (i.e. once per batch actually flushed to
 * the Collector) for each of trace/metric/log independently - this is intentionally more frequent
 * than a single one-time confirmation, since the explicit requirement is a confirmation visible
 * against every demo-runner-client run, not just the gateway's first export ever. The OTel SDK's
 * own batch processors (default: time- or size-based) still control how often {@code export(...)}
 * itself is actually invoked; this class does not change that cadence, only what is logged when
 * each invocation succeeds.
 */
final class OtlpExportSuccessLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtlpExportSuccessLogger.class);
    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

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

    private static String serviceNameOf(Resource resource) {
        if (resource == null) {
            return "unknown";
        }
        String name = resource.getAttribute(SERVICE_NAME);
        return name != null ? name : "unknown";
    }

    private static void logOnSuccess(
            String signalType, int count, String serviceName, CompletableResultCode result) {
        result.whenComplete(() -> {
            if (result.isSuccess()) {
                LOGGER.info(
                        "OTLP {} export to the Collector succeeded - service.name='{}', batch "
                                + "size={} - HTTPS trust and/or OAuth Bearer token (whichever is "
                                + "enabled) are confirmed working end-to-end for this export.",
                        signalType,
                        serviceName,
                        count);
            }
        });
    }

    private static final class LoggingSpanExporter implements SpanExporter {
        private final SpanExporter delegate;

        private LoggingSpanExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            CompletableResultCode result = delegate.export(spans);
            String serviceName = spans.stream()
                    .findFirst()
                    .map(SpanData::getResource)
                    .map(OtlpExportSuccessLogger::serviceNameOf)
                    .orElse("unknown");
            logOnSuccess("trace", spans.size(), serviceName, result);
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
            String serviceName = metrics.stream()
                    .findFirst()
                    .map(MetricData::getResource)
                    .map(OtlpExportSuccessLogger::serviceNameOf)
                    .orElse("unknown");
            logOnSuccess("metrics", metrics.size(), serviceName, result);
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

        private LoggingLogRecordExporter(LogRecordExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            CompletableResultCode result = delegate.export(logs);
            String serviceName = logs.stream()
                    .findFirst()
                    .map(LogRecordData::getResource)
                    .map(OtlpExportSuccessLogger::serviceNameOf)
                    .orElse("unknown");
            logOnSuccess("logs", logs.size(), serviceName, result);
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
