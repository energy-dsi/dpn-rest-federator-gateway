// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest;

import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.dsi.dpn.common.service.secret.VaultFileBasedSslBundleInitializer;
import org.dsi.dpn.common.telemetry.HeartbeatService;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;

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
        OpenTelemetryConfig.initialize();

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
        app.addListeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
            OpenTelemetryAppender.install(OpenTelemetryConfig.get());
            LoggerFactory.getLogger(RestFederatorServerApplication.class)
                    .info("OpenTelemetry log appender re-attached after logging system initialisation");
        });
        app.run(args);
    }
}
