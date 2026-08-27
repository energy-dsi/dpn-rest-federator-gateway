// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.dsi.dpn.common.telemetry.HeartbeatService;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;

/**
 * REST Federator — Producer-side server.
 * Standalone Spring Boot application.
 * No gRPC, no Kafka, no JobRunr required.
 * Uses the same common.configuration file as the gRPC Federator.
 */
@SpringBootApplication
public class RestFederatorServerApplication {

    /** Component name used for both OTEL service identity and heartbeat logs. */
    private static final String COMPONENT_NAME = "rest-federator-server";

    public static void main(String[] args) {
        OpenTelemetryConfig.initialize();
        // Periodic liveness beat, 15 minutes by default (HEARTBEAT_INTERVAL_SECONDS
        // overrides), matching dpn-federator's federator-server cadence. Started
        // before Spring so a beat is emitted even if context startup is slow, and
        // registers its own shutdown hook.
        HeartbeatService.startFromEnv(COMPONENT_NAME);
        SpringApplication.run(RestFederatorServerApplication.class, args);
    }
}
