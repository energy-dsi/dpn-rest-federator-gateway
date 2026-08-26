// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme
// and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
package org.dsi.dpn.common.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes the OpenTelemetry SDK for this JVM (rest-federator-server, the client library's
 * consuming apps, demo-runner-server, demo-runner-client), using the standard OTel autoconfigure
 * environment-variable convention:
 *
 * <pre>
 *   OTEL_EXPORTER_OTLP_ENDPOINT   e.g. http://dpn-otel-collector:4317
 *   OTEL_EXPORTER_OTLP_PROTOCOL   grpc | http
 *   OTEL_EXPORTER_OTLP_INSECURE   true | false
 *   OTEL_SERVICE_NAME
 *   OTEL_RESOURCE_ATTRIBUTES      e.g. service.version=1.0.0,deployment.environment=dev
 *   OTEL_TRACES_SAMPLER           e.g. parentbased_traceidratio
 *   OTEL_TRACES_SAMPLER_ARG       e.g. 1.0
 * </pre>
 *
 * <p>Ported from dpn-federator's {@code common.telemetry.OpenTelemetryConfig} (same class shape,
 * same fail-open behaviour): if the collector is unreachable or the SDK otherwise fails to
 * initialise, this falls back to {@link OpenTelemetry#noop()} rather than crashing the app —
 * telemetry must never be a hard dependency for availability.
 *
 * <p>Also registers a JVM shutdown hook that logs at CRITICAL/FATAL severity whenever this
 * process exits, for any reason (graceful shutdown or crash).
 *
 * <p>Call {@link #initialize()} once, as early as possible in each app's startup (e.g. the first
 * line of {@code main(...)}, or a static initializer reached before any other bean creation).
 */
public final class OpenTelemetryConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenTelemetryConfig.class);
    private static volatile OpenTelemetry openTelemetry;

    static {
        // The OTel SDK's own exporters log via java.util.logging (JUL), not SLF4J/Logback —
        // completely separate from our own logging config. Replace the default console handler
        // on this logger with CollectorUnavailableLogHandler, which swallows the stack trace and
        // emits a single short application-level message instead, on every failed OTLP export.
        java.util.logging.Logger otelJulLogger = java.util.logging.Logger.getLogger("io.opentelemetry");
        otelJulLogger.setUseParentHandlers(false);
        for (java.util.logging.Handler existing : otelJulLogger.getHandlers()) {
            otelJulLogger.removeHandler(existing);
        }
        otelJulLogger.addHandler(new CollectorUnavailableLogHandler());
        otelJulLogger.setLevel(java.util.logging.Level.ALL);
    }

    private OpenTelemetryConfig() {}

    public static synchronized OpenTelemetry initialize() {
        if (openTelemetry != null) {
            return openTelemetry;
        }

        boolean exportingViaOtlp = false;
        try {
            String componentName = resolveComponentName();
            AutoConfiguredOpenTelemetrySdk autoConfigured = AutoConfiguredOpenTelemetrySdk.builder()
                    // Stamp "component.name" onto every emitted log record so downstream log
                    // pipelines can attribute records to a component without editing every
                    // individual log call site.
                    .addLogRecordProcessorCustomizer((delegate, config) ->
                            LogRecordProcessor.composite(
                                    new ComponentNameLogRecordProcessor(componentName), delegate))
                    .build();
            OpenTelemetrySdk sdk = autoConfigured.getOpenTelemetrySdk();
            openTelemetry = sdk;

            // Wires the Logback appender (see logback-spring.xml's "OpenTelemetry" appender) to
            // this SDK instance, so every log event is exported as a genuine OTel LogRecord.
            OpenTelemetryAppender.install(openTelemetry);
            exportingViaOtlp = true;
        } catch (Throwable t) {
            LOGGER.warn("OTel Collector is not available - continuing without OTLP telemetry "
                    + "export. Console/application logging is unaffected.");
            openTelemetry = OpenTelemetry.noop();
        }

        // Any JVM exit (Ctrl+C, container stop, OOM, uncaught fatal error) runs this — ensures
        // "service going down" is always logged at CRITICAL, regardless of cause.
        String serviceName = System.getProperty("otel.service.name", System.getenv("OTEL_SERVICE_NAME"));
        String displayName = serviceName != null ? serviceName : "rest-federator";
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                CriticalLogUtil.logCritical(LOGGER, displayName + " process is shutting down")));

        if (exportingViaOtlp) {
            LOGGER.info("OpenTelemetry SDK initialised; exporting via OTLP per OTEL_* environment variables");
        }
        return openTelemetry;
    }

    /**
     * Resolves the component name to stamp onto every log record: otel.service.name (sys prop)
     * -&gt; OTEL_SERVICE_NAME (env) -&gt; service.name in OTEL_RESOURCE_ATTRIBUTES -&gt;
     * "dpn-rest-federator".
     */
    private static String resolveComponentName() {
        String fromSysProp = System.getProperty("otel.service.name");
        if (fromSysProp != null && !fromSysProp.isBlank()) {
            return fromSysProp;
        }
        String fromEnv = System.getenv("OTEL_SERVICE_NAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String raw = System.getProperty("otel.resource.attributes");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("OTEL_RESOURCE_ATTRIBUTES");
        }
        if (raw != null) {
            for (String pair : raw.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "service.name".equals(pair.substring(0, eq).trim())) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return "dpn-rest-federator";
    }

    public static OpenTelemetry get() {
        if (openTelemetry == null) {
            // Telemetry must never be a hard dependency for a caller — resolve lazily to noop
            // rather than throwing, so a class that races initialize() (e.g. a static field in
            // another class initialized before main() calls initialize()) still gets a usable,
            // if inert, OpenTelemetry instance.
            synchronized (OpenTelemetryConfig.class) {
                if (openTelemetry == null) {
                    return initialize();
                }
            }
        }
        return openTelemetry;
    }
}
