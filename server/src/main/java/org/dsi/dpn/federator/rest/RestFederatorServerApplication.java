// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.dsi.dpn.common.telemetry.OpenTelemetryConfig;

/**
 * REST Federator — Producer-side server.
 * Standalone Spring Boot application.
 * No gRPC, no Kafka, no JobRunr required.
 * Uses the same common.configuration file as the gRPC Federator.
 */
@SpringBootApplication
public class RestFederatorServerApplication {
    public static void main(String[] args) {
        OpenTelemetryConfig.initialize();
        SpringApplication.run(RestFederatorServerApplication.class, args);
    }
}
