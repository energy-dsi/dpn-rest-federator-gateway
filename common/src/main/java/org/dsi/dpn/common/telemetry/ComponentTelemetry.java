// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * Telemetry handle for a reusable library component (e.g. {@code rest-federator-client}) that must
 * appear in the dashboard under its OWN component name, independent of the application hosting it.
 * Backed by {@link OpenTelemetryConfig#forComponent(String)}: spans and log records emitted here
 * carry a Resource with {@code component.name=<componentName>}, so a change of host application
 * (demo runner today, anything else tomorrow) does not change how the component is reported.
 *
 * <p>Fail-open: every method is safe on a no-op SDK and never throws.
 */
public final class ComponentTelemetry {

    private final String componentName;
    private final OpenTelemetry otel;
    private ScheduledExecutorService heartbeatScheduler;
    private volatile long heartbeatCount;

    private ComponentTelemetry(String componentName) {
        this.componentName = componentName;
        this.otel = OpenTelemetryConfig.forComponent(componentName);
    }

    public static ComponentTelemetry of(String componentName) {
        return new ComponentTelemetry(componentName);
    }

    public String componentName() {
        return componentName;
    }

    /**
     * Reports every log line of the given package (and sub-packages) under THIS component rather
     * than the host application - see {@link LogBridge}. Fail-open; call once, early.
     *
     * @return this, for chaining
     */
    public ComponentTelemetry bridgeLogsFrom(String packageLoggerName) {
        try {
            LogBridge.attach(packageLoggerName, componentName, otel);
        } catch (Throwable ignored) {
            // telemetry must never affect the caller
        }
        return this;
    }

    public Tracer tracer(String instrumentationScope) {
        return otel.getTracer(instrumentationScope);
    }

    public TextMapPropagator propagator() {
        return otel.getPropagators().getTextMapPropagator();
    }

    /**
     * Starts a periodic "component.heartbeat" on this component's Resource, same shape as
     * {@link HeartbeatService} (timestamp, sequence, uptime, interval, status). Emits immediately,
     * then every {@code HEARTBEAT_INTERVAL_SECONDS} (default {@link HeartbeatService#DEFAULT_INTERVAL_SECONDS}).
     * Daemon thread, so it never keeps the JVM alive; idempotent.
     */
    public synchronized void startHeartbeat(Supplier<String> status) {
        if (heartbeatScheduler != null) {
            return;
        }
        long interval = resolveInterval();
        Instant start = Instant.now();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-" + componentName);
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                String st;
                try {
                    st = status.get();
                } catch (RuntimeException e) {
                    st = "unhealthy";
                }
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put("heartbeat.timestamp", OffsetDateTime.now(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")));
                attrs.put("heartbeat.sequence", ++heartbeatCount);
                attrs.put("component.uptime_seconds", java.time.Duration.between(start, Instant.now()).getSeconds());
                attrs.put("heartbeat.interval_seconds", interval);
                attrs.put("component.status", st != null ? st : "unknown");
                log(Severity.INFO, "component.heartbeat", "Heartbeat: " + componentName + " is " + st, attrs);
            } catch (RuntimeException ignored) {
                // never let one bad beat kill the schedule
            }
        }, 0, interval, TimeUnit.SECONDS);
    }

    private static long resolveInterval() {
        try {
            String env = System.getenv("HEARTBEAT_INTERVAL_SECONDS");
            long v = env != null && !env.isBlank() ? Long.parseLong(env.trim()) : HeartbeatService.DEFAULT_INTERVAL_SECONDS;
            return v > 0 ? v : HeartbeatService.DEFAULT_INTERVAL_SECONDS;
        } catch (NumberFormatException e) {
            return HeartbeatService.DEFAULT_INTERVAL_SECONDS;
        }
    }

    /** Emits one OTLP log record on this component's Resource, linked to the current span. */
    public void log(Severity severity, String eventName, String message, Map<String, ?> attributes) {
        try {
            LogRecordBuilder builder = otel.getLogsBridge()
                    .loggerBuilder(componentName)
                    .build()
                    .logRecordBuilder()
                    .setContext(Context.current())
                    .setSeverity(severity)
                    .setSeverityText(severity.name())
                    .setBody(message)
                    .setAttribute(AttributeKey.stringKey("event.name"), eventName)
                    .setAttribute(AttributeKey.stringKey("component.name"), componentName);
            if (attributes != null) {
                AttributesBuilder attrs = io.opentelemetry.api.common.Attributes.builder();
                attributes.forEach((k, v) -> {
                    if (v instanceof Long || v instanceof Integer) {
                        attrs.put(AttributeKey.longKey(k), ((Number) v).longValue());
                    } else if (v instanceof Boolean b) {
                        attrs.put(AttributeKey.booleanKey(k), b);
                    } else if (v != null) {
                        attrs.put(AttributeKey.stringKey(k), v.toString());
                    }
                });
                builder.setAllAttributes(attrs.build());
            }
            builder.emit();
        } catch (RuntimeException ignored) {
            // telemetry must never affect the caller
        }
    }

    /**
     * Routes every SLF4J/Logback log line from a library's package (e.g. the REST client's
     * {@code org.dsi.dpn.federator.rest.framework.client}) into that component's OWN SDK, so ordinary
     * log lines - not just explicit events - are reported under the component's name instead of the
     * host application's.
     *
     * <p>An OpenTelemetry Logback appender bound to the component's SDK is attached to the package
     * logger, which is made non-additive so the host's root OTLP appender does NOT also export the same
     * line under the host's name. The root logger's non-OTLP appenders (stdout) are attached to the
     * package logger too, so console output is unchanged.
     *
     * <p>Fail-open and Logback-only: if SLF4J is bound to something else, or anything goes wrong,
     * nothing is changed and the lines stay with the host application. Kept as a nested class so
     * Logback types are only loaded when the bridge is actually used.
     */
    static final class LogBridge {

        private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LogBridge.class);

        private LogBridge() {}

        /**
         * An OpenTelemetry appender that keeps the SDK it was bound to. {@code
         * OpenTelemetryAppender.install(...)} - called for the process-wide SDK at startup and again
         * after Spring re-initialises logging - sets its SDK on EVERY such appender in the context;
         * ignoring those calls stops the host's SDK from silently replacing the component's.
         */
        static final class BoundOpenTelemetryAppender extends OpenTelemetryAppender {
            private volatile boolean bound;

            void bind(OpenTelemetry openTelemetry) {
                super.setOpenTelemetry(openTelemetry);
                bound = true;
            }

            @Override
            public void setOpenTelemetry(OpenTelemetry openTelemetry) {
                if (!bound) {
                    super.setOpenTelemetry(openTelemetry);
                }
            }
        }

        /** @return true if the bridge is in place (newly attached or already attached). */
        static boolean attach(String packageLoggerName, String componentName, OpenTelemetry sdk) {
            if (sdk == null || sdk == OpenTelemetry.noop()) {
                return false;
            }
            try {
                org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
                if (!(factory instanceof LoggerContext context)) {
                    return false;
                }
                Logger packageLogger = context.getLogger(packageLoggerName);
                String name = "OpenTelemetry-" + componentName;
                if (packageLogger.getAppender(name) != null) {
                    return true;
                }

                BoundOpenTelemetryAppender otlp = new BoundOpenTelemetryAppender();
                otlp.setName(name);
                otlp.setContext(context);
                otlp.setCaptureExperimentalAttributes(true);
                otlp.setCaptureCodeAttributes(true);
                otlp.setCaptureMdcAttributes("*");
                otlp.setCaptureKeyValuePairAttributes(true);
                otlp.setCaptureLoggerContext(true);
                otlp.bind(sdk);
                otlp.start();

                // Keep the same stdout output: re-attach every non-OTLP appender from the root logger.
                Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                List<Appender<ILoggingEvent>> passthrough = new ArrayList<>();
                for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                    Appender<ILoggingEvent> appender = it.next();
                    if (!(appender instanceof OpenTelemetryAppender)) {
                        passthrough.add(appender);
                    }
                }
                passthrough.forEach(packageLogger::addAppender);
                packageLogger.addAppender(otlp);
                packageLogger.setAdditive(false);

                LOGGER.info("Log lines from '{}' are now reported as component '{}'",
                        packageLoggerName, componentName);
                return true;
            } catch (Throwable t) {
                // Includes NoClassDefFoundError when Logback is not on the classpath.
                LOGGER.warn("Could not route '{}' logs to component '{}' - they stay with the host "
                        + "application: {}", packageLoggerName, componentName, t.toString());
                return false;
            }
        }
    }
}
