// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest;

import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.util.function.BooleanSupplier;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.dsi.dpn.common.service.secret.VaultFileBasedSslBundleInitializer;
import org.dsi.dpn.common.telemetry.HeartbeatService;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;
import org.dsi.dpn.common.utils.PropertyUtil;

/**
 * REST Federator - Producer-side server.
 * Standalone Spring Boot application.
 * No gRPC, no Kafka, no JobRunr required.
 * Uses the same common.configuration file as the gRPC Federator.
 */
@SpringBootApplication
public class RestFederatorServerApplication {

    /** Component name used for both OTEL service identity and heartbeat logs. */
    private static final String COMPONENT_NAME = "rest-federator-server";

    public static void main(String[] args) throws Exception {
        initialiseConfigurationThenTelemetry(PropertyUtil::initializeProperties,
                () -> OpenTelemetryConfig.initialize());

        // Sources this gateway's inbound HTTPS listener from Vault via a real,
        // file-based SSL bundle - see VaultFileBasedSslBundleInitializer's own
        // javadoc for why the file-based approach is used instead of the
        // in-memory SslBundleRegistrar this project used previously. Must run
        // before SpringApplication.run() so the properties it sets are already
        // present in the Environment when Spring resolves server.ssl.bundle.
        VaultFileBasedSslBundleInitializer.initialise("federator-tls");

        // Periodic liveness beat, 15 minutes by default (HEARTBEAT_INTERVAL_SECONDS
        // overrides), matching dpn-federator's federator-server cadence. Started
        // before Spring so a beat is emitted even if context startup is slow, and
        // registers its own shutdown hook.
        HeartbeatService.startFromEnv(COMPONENT_NAME);
        SpringApplication app = new SpringApplication(RestFederatorServerApplication.class);
        // Spring Boot re-initialises Logback from logback.xml, which drops the OTLP appender that
        // OpenTelemetryConfig.initialize() attached; without this, only the handful of records
        // emitted before Spring starts are ever exported and the dashboard goes silent.
        app.addListeners((ApplicationListener<ApplicationPreparedEvent>) event ->
                reattachOtelLogAppender(() -> OpenTelemetryAppender.install(OpenTelemetryConfig.get())));
        app.run(args);
    }

    /**
     * Re-attaches the OpenTelemetry Logback appender, and can NEVER stop the gateway starting.
     *
     * <p>This runs inside a Spring {@code ApplicationPreparedEvent} listener, where any exception
     * aborts start-up. Telemetry is not worth that: a logging back-end swap, or a changed API in an
     * upgraded OpenTelemetry appender (it is an alpha artifact), must degrade to "application logs
     * are not exported over OTLP", with a warning, and nothing worse.
     *
     * @param install performs the actual re-attachment
     * @return true if it was re-attached, false if it failed (start-up carries on either way)
     */
    static boolean reattachOtelLogAppender(Runnable install) {
        try {
            install.run();
            LoggerFactory.getLogger(RestFederatorServerApplication.class)
                    .info("OpenTelemetry log appender re-attached after logging system initialisation");
            return true;
        } catch (Exception | LinkageError e) {
            LoggerFactory.getLogger(RestFederatorServerApplication.class)
                    .warn("Could not re-attach the OpenTelemetry log appender - application logs will not be "
                            + "exported over OTLP, start-up continues: {}", e.toString());
            return false;
        }
    }

    /**
     * Initialises {@code PropertyUtil} BEFORE the OpenTelemetry SDK, not after.
     *
     * <p>Starting the SDK also starts its background log exporter, and with OAuth export enabled
     * every export needs a Bearer token whose service is built from {@code PropertyUtil}. If the SDK
     * is started first, the exporter's first send (immediate with {@code
     * OTEL_BLRP_MAX_EXPORT_BATCH_SIZE=1}) races {@code PropertyUtil}'s initialisation (about 0.5 s,
     * mostly the Vault login), fails with {@code PropertyUtilException: Property Util not properly
     * initialised}, and the SDK drops those records with a stack trace. Initialising configuration
     * first removes the race at its cause - no exporter exists until the configuration it needs does.
     *
     * <p>Log lines written before the SDK exists are not lost: the Logback OpenTelemetry appender
     * buffers them and replays them once the SDK is installed.
     *
     * <p>A failed configuration load is deliberately not fatal here: {@code
     * VaultFileBasedSslBundleInitializer.initialise} calls the same (idempotent) initialisation
     * next, and fails fast and reports exactly as it did before this ordering change.
     *
     * @param configurationInit initialises {@code PropertyUtil}; its result is not needed here
     * @param telemetryInit     starts the OpenTelemetry SDK
     */
    static void initialiseConfigurationThenTelemetry(BooleanSupplier configurationInit, Runnable telemetryInit) {
        try {
            configurationInit.getAsBoolean();
        } catch (RuntimeException e) {
            // Reported by the fail-fast initialisation that follows in main().
        }
        telemetryInit.run();
    }
}
