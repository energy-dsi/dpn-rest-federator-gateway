// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
}
