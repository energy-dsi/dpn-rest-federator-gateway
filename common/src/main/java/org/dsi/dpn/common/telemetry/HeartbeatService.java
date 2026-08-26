// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Periodic health / heartbeat logger, ported from dpn-federator's
 * {@code common.telemetry.HeartbeatService} so REST and gRPC services emit
 * identically-shaped heartbeats to the same collector.
 *
 * <p>A daemon {@link ScheduledExecutorService} fires the beat. Each beat carries
 * {@code event.name=component.heartbeat} plus {@code component.name},
 * {@code heartbeat.timestamp}, {@code heartbeat.sequence},
 * {@code component.uptime_seconds}, {@code heartbeat.interval_seconds},
 * {@code component.status} and any custom metadata. A one-off
 * {@code heartbeat.started} is logged on start and {@code heartbeat.stopped}
 * (with {@code heartbeat.total_count}) on stop.
 *
 * <p>Attributes are emitted as SLF4J 2.x key/value pairs so they serialise as JSON
 * numbers where appropriate. They only reach the collector because logback.xml
 * sets {@code captureKeyValuePairAttributes} on the OTLP appender — without that
 * they are dropped on the OTLP path and appear on stdout only.
 *
 * <p>Interval resolution: an explicit {@link Duration} wins, otherwise the
 * {@code HEARTBEAT_INTERVAL_SECONDS} environment variable, otherwise
 * {@link #DEFAULT_INTERVAL_SECONDS} (15 minutes).
 *
 * <p>Emits immediately on start, then every interval. That immediate first beat
 * is what makes this useful for short-lived processes such as the consumer-side
 * client Job, which records a single beat for its run rather than a periodic
 * series like the long-running gateway.
 */
public final class HeartbeatService {

    /** 15 minutes, matching dpn-federator's federator-server cadence. */
    public static final long DEFAULT_INTERVAL_SECONDS = 900L;

    private static final DateTimeFormatter HEARTBEAT_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    private final Logger logger;
    private final String componentName;
    private final long intervalSeconds;
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final Supplier<String> statusSupplier;

    private ScheduledExecutorService scheduler;
    private volatile Instant startTime;
    private volatile long heartbeatCount = 0;

    private HeartbeatService(
            String componentName, Duration interval, Map<String, Object> metadata, Supplier<String> statusSupplier) {
        // logger name == component name, so the collector sees it as the service
        this.logger = LoggerFactory.getLogger(componentName);
        this.componentName = componentName;
        this.intervalSeconds = resolveInterval(interval);
        if (metadata != null) {
            this.metadata.putAll(metadata);
        }
        this.statusSupplier = statusSupplier != null ? statusSupplier : () -> "healthy";
    }

    /** Build without starting. */
    public static HeartbeatService create(String componentName) {
        return new HeartbeatService(componentName, null, null, null);
    }

    public static HeartbeatService create(
            String componentName, Duration interval, Map<String, Object> metadata, Supplier<String> statusSupplier) {
        return new HeartbeatService(componentName, interval, metadata, statusSupplier);
    }

    /** Build, start, and register a shutdown hook that stops it cleanly. */
    public static HeartbeatService start(String componentName, Duration interval) {
        return start(componentName, interval, null, null);
    }

    public static HeartbeatService start(
            String componentName, Duration interval, Map<String, Object> metadata, Supplier<String> statusSupplier) {
        HeartbeatService service = new HeartbeatService(componentName, interval, metadata, statusSupplier);
        service.start();
        Runtime.getRuntime().addShutdownHook(new Thread(service::stop, componentName + "-heartbeat-shutdown"));
        return service;
    }

    /**
     * Resolves the interval from {@code HEARTBEAT_INTERVAL_SECONDS} (or the
     * 15-minute default) and starts. Convenience for the common case.
     */
    public static HeartbeatService startFromEnv(String componentName) {
        return start(componentName, null);
    }

    /** Start on a daemon scheduler. Emits immediately, then every interval. No-op if already running. */
    public synchronized void start() {
        if (isRunning()) {
            logger.atWarn().addKeyValue("component.name", componentName).log("Heartbeat already running");
            return;
        }
        heartbeatCount = 0;
        startTime = Instant.now();
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory(componentName));
        // initialDelay 0 -> emit immediately, then every interval.
        scheduler.scheduleAtFixedRate(this::emit, 0, intervalSeconds, TimeUnit.SECONDS);

        logger.atInfo()
                .addKeyValue("event.name", "heartbeat.started")
                .addKeyValue("component.name", componentName)
                .addKeyValue("heartbeat.interval_seconds", intervalSeconds)
                .log("Heartbeat logger started");
    }

    /** Emit a single heartbeat log entry. Visible for testing. */
    void emit() {
        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            long uptime = startTime != null ? Duration.between(startTime, Instant.now()).getSeconds() : 0L;
            heartbeatCount++;
            String status = safeStatus();

            LoggingEventBuilder event = logger.atInfo()
                    .addKeyValue("event.name", "component.heartbeat")
                    .addKeyValue("component.name", componentName)
                    .addKeyValue("heartbeat.timestamp", now.format(HEARTBEAT_TS))
                    .addKeyValue("heartbeat.sequence", heartbeatCount)
                    .addKeyValue("component.uptime_seconds", uptime)
                    .addKeyValue("heartbeat.interval_seconds", intervalSeconds)
                    .addKeyValue("component.status", status);

            for (Map.Entry<String, Object> e : metadata.entrySet()) {
                event = event.addKeyValue(e.getKey(), e.getValue());
            }
            event.log("Heartbeat: {} is {}", componentName, status);
        } catch (Exception e) {
            // Never let one bad beat kill the scheduled task.
            logger.atWarn()
                    .addKeyValue("component.name", componentName)
                    .addKeyValue("error.type", e.getClass().getSimpleName())
                    .addKeyValue("error.message", e.getMessage())
                    .log("Heartbeat emit failed for {}", componentName);
        }
    }

    /** Stop the scheduler, wait up to {@code timeout}, then log heartbeat.stopped with the total count. */
    public synchronized void stop(Duration timeout) {
        if (!isRunning()) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.atInfo()
                .addKeyValue("event.name", "heartbeat.stopped")
                .addKeyValue("component.name", componentName)
                .addKeyValue("heartbeat.total_count", heartbeatCount)
                .log("Heartbeat logger stopped");
    }

    /** Stop with the default 5s join timeout. */
    public void stop() {
        stop(Duration.ofSeconds(5));
    }

    /** Merge metadata into FUTURE heartbeats, without restarting. */
    public void updateMetadata(Map<String, Object> extra) {
        if (extra != null) {
            metadata.putAll(extra);
        }
    }

    public boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    public long heartbeatCount() {
        return heartbeatCount;
    }

    private String safeStatus() {
        try {
            String s = statusSupplier.get();
            return s != null ? s : "unknown";
        } catch (Exception e) {
            return "unhealthy";
        }
    }

    private static long resolveInterval(Duration interval) {
        if (interval != null) {
            return interval.getSeconds();
        }
        String env = System.getenv("HEARTBEAT_INTERVAL_SECONDS");
        try {
            return env != null && !env.isEmpty() ? Long.parseLong(env.trim()) : DEFAULT_INTERVAL_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_INTERVAL_SECONDS;
        }
    }

    private static ThreadFactory daemonThreadFactory(String componentName) {
        return runnable -> {
            Thread t = new Thread(runnable, "heartbeat-" + componentName);
            t.setDaemon(true);
            return t;
        };
    }
}
