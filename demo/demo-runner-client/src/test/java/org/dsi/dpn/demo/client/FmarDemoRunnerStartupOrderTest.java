// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.demo.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Tracer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Start-up ordering of the demo-runner client Job: configuration (PropertyUtil) must be initialised
 * BEFORE the OpenTelemetry SDK starts, or its log exporter races the Vault login inside PropertyUtil
 * and drops records with "Property Util not properly initialised".
 */
class FmarDemoRunnerStartupOrderTest {

    @Test
    @DisplayName("configuration is initialised before telemetry starts")
    void configurationIsInitialisedBeforeTelemetry() {
        List<String> order = new ArrayList<>();

        RuntimeException failure = FmarDemoRunner.initialiseConfigurationThenTelemetry(
                () -> order.add("configuration"), () -> order.add("telemetry"));

        assertThat(order).containsExactly("configuration", "telemetry");
        assertThat(failure).isNull();
    }

    @Test
    @DisplayName("telemetry only starts after a slow configuration initialisation has FINISHED "
            + "(stands in for the Vault login inside PropertyUtil)")
    void telemetryStartsOnlyAfterSlowConfigurationFinishes() {
        AtomicBoolean configurationReady = new AtomicBoolean(false);
        AtomicBoolean readyWhenTelemetryStarted = new AtomicBoolean(false);

        FmarDemoRunner.initialiseConfigurationThenTelemetry(
                () -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    configurationReady.set(true);
                },
                () -> readyWhenTelemetryStarted.set(configurationReady.get()));

        assertThat(readyWhenTelemetryStarted).as("configuration was ready when telemetry started").isTrue();
    }

    @Test
    @DisplayName("a configuration failure is RETURNED for main() to re-throw where it always did, and "
            + "telemetry still starts")
    void configurationFailureIsReturnedAndTelemetryStillStarts() {
        AtomicBoolean telemetryStarted = new AtomicBoolean(false);
        IllegalStateException boom = new IllegalStateException("client.properties unreadable");

        RuntimeException failure = FmarDemoRunner.initialiseConfigurationThenTelemetry(
                () -> {
                    throw boom;
                },
                () -> telemetryStarted.set(true));

        assertThat(failure).isSameAs(boom);
        assertThat(telemetryStarted).isTrue();
    }

    @Test
    @DisplayName("a held configuration failure is re-thrown unchanged, and no failure is a no-op")
    void rethrowIfFailed_rethrowsTheSameExceptionOrDoesNothing() {
        IllegalStateException boom = new IllegalStateException("client.properties unreadable");

        assertThatThrownBy(() -> FmarDemoRunner.rethrowIfFailed(boom)).isSameAs(boom);
        assertThatCode(() -> FmarDemoRunner.rethrowIfFailed(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FmarDemoRunner holds no static Tracer: that would start OpenTelemetry when the JVM loads "
            + "the class, before main() can initialise configuration (the original cause of this race)")
    void noStaticTracerThatWouldStartTelemetryAtClassLoad() {
        List<String> offending = Arrays.stream(FmarDemoRunner.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()))
                .filter(f -> Tracer.class.isAssignableFrom(f.getType()))
                .map(Field::getName)
                .toList();

        assertThat(offending).isEmpty();
    }
}
