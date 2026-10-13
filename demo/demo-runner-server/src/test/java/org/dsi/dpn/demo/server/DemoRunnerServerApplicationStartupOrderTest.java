// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Start-up ordering of demo-runner-server: configuration (PropertyUtil) must be initialised BEFORE
 * the OpenTelemetry SDK starts, or its exporter threads race it and fail with
 * "Property Util not properly initialised".
 */
class DemoRunnerServerApplicationStartupOrderTest {

    @Test
    @DisplayName("configuration is initialised before telemetry starts")
    void configurationIsInitialisedBeforeTelemetry() {
        List<String> order = new ArrayList<>();

        boolean configured = DemoRunnerServerApplication.initialiseConfigurationThenTelemetry(
                () -> {
                    order.add("configuration");
                    return true;
                },
                () -> order.add("telemetry"));

        assertThat(order).containsExactly("configuration", "telemetry");
        assertThat(configured).isTrue();
    }

    @Test
    @DisplayName("telemetry only starts after a slow configuration load has FINISHED")
    void telemetryStartsOnlyAfterSlowConfigurationFinishes() {
        AtomicBoolean configurationReady = new AtomicBoolean(false);
        AtomicBoolean readyWhenTelemetryStarted = new AtomicBoolean(false);

        DemoRunnerServerApplication.initialiseConfigurationThenTelemetry(
                () -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    configurationReady.set(true);
                    return true;
                },
                () -> readyWhenTelemetryStarted.set(configurationReady.get()));

        assertThat(readyWhenTelemetryStarted).as("configuration was ready when telemetry started").isTrue();
    }

    @Test
    @DisplayName("telemetry still starts and failure is reported when the configuration load returns false")
    void telemetryStartsWhenConfigurationReturnsFalse() {
        AtomicBoolean telemetryStarted = new AtomicBoolean(false);

        boolean configured = DemoRunnerServerApplication.initialiseConfigurationThenTelemetry(
                () -> false, () -> telemetryStarted.set(true));

        assertThat(configured).isFalse();
        assertThat(telemetryStarted).isTrue();
    }

    @Test
    @DisplayName("telemetry still starts, and nothing is thrown, when the configuration load throws")
    void telemetryStartsWhenConfigurationThrows() {
        AtomicBoolean telemetryStarted = new AtomicBoolean(false);

        assertThatCode(() -> assertThat(DemoRunnerServerApplication.initialiseConfigurationThenTelemetry(
                () -> {
                    throw new IllegalStateException("server.properties unreadable");
                },
                () -> telemetryStarted.set(true))).isFalse())
                .doesNotThrowAnyException();

        assertThat(telemetryStarted).isTrue();
    }

    @Test
    @DisplayName("a failure while starting telemetry is not swallowed")
    void telemetryFailureIsNotSwallowed() {
        assertThatThrownBy(() -> DemoRunnerServerApplication.initialiseConfigurationThenTelemetry(
                () -> true,
                () -> {
                    throw new IllegalStateException("telemetry init failed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("telemetry init failed");
    }
}
