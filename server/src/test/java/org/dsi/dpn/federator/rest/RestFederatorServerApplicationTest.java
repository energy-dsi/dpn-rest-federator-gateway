// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for RestFederatorServerApplication.
 * Covers the application class instantiation without starting Spring context, and the start-up
 * ordering that keeps the OpenTelemetry log exporter from racing PropertyUtil's initialisation.
 */
class RestFederatorServerApplicationTest {

    @Test @DisplayName("RestFederatorServerApplication class can be instantiated")
    void applicationClassExists() {
        RestFederatorServerApplication app = new RestFederatorServerApplication();
        assertThat(app).isNotNull();
    }

    @Test
    @DisplayName("configuration is initialised before telemetry starts, so the exporter never sees a "
            + "half-initialised PropertyUtil")
    void configurationIsInitialisedBeforeTelemetry() {
        List<String> order = new ArrayList<>();

        RestFederatorServerApplication.initialiseConfigurationThenTelemetry(
                () -> {
                    order.add("configuration");
                    return true;
                },
                () -> order.add("telemetry"));

        assertThat(order).containsExactly("configuration", "telemetry");
    }

    @Test
    @DisplayName("telemetry only starts once a slow configuration initialisation has FINISHED "
            + "(reproduces the Vault-login window that caused 'Property Util not properly initialised')")
    void telemetryStartsOnlyAfterSlowConfigurationFinishes() {
        AtomicBoolean configurationReady = new AtomicBoolean(false);
        AtomicBoolean readyWhenTelemetryStarted = new AtomicBoolean(false);

        RestFederatorServerApplication.initialiseConfigurationThenTelemetry(
                () -> {
                    try {
                        Thread.sleep(300); // stands in for the Vault login inside PropertyUtil
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
    @DisplayName("telemetry still starts when the configuration load reports failure, leaving the "
            + "fail-fast initialisation that follows to report it as before")
    void telemetryStillStartsWhenConfigurationReturnsFalse() {
        AtomicBoolean telemetryStarted = new AtomicBoolean(false);

        RestFederatorServerApplication.initialiseConfigurationThenTelemetry(
                () -> false,
                () -> telemetryStarted.set(true));

        assertThat(telemetryStarted).isTrue();
    }

    @Test
    @DisplayName("telemetry still starts, and nothing is thrown, when the configuration load throws")
    void telemetryStillStartsWhenConfigurationThrows() {
        AtomicBoolean telemetryStarted = new AtomicBoolean(false);

        assertThatCode(() -> RestFederatorServerApplication.initialiseConfigurationThenTelemetry(
                () -> {
                    throw new IllegalStateException("properties file unreadable");
                },
                () -> telemetryStarted.set(true)))
                .doesNotThrowAnyException();

        assertThat(telemetryStarted).isTrue();
    }

    @Test
    @DisplayName("re-attaching the OTel log appender reports success when it works")
    void reattachOtelLogAppender_success() {
        AtomicBoolean installed = new AtomicBoolean(false);

        boolean result = RestFederatorServerApplication.reattachOtelLogAppender(() -> installed.set(true));

        assertThat(result).isTrue();
        assertThat(installed).isTrue();
    }

    @Test
    @DisplayName("re-attaching the OTel log appender can never stop start-up: an exception is swallowed "
            + "and reported as failure")
    void reattachOtelLogAppender_exceptionDoesNotAbortStartup() {
        assertThatCode(() -> assertThat(RestFederatorServerApplication.reattachOtelLogAppender(() -> {
            throw new IllegalStateException("appender API changed");
        })).isFalse()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("re-attaching the OTel log appender also survives a linkage error (e.g. Logback no longer "
            + "on the classpath)")
    void reattachOtelLogAppender_linkageErrorDoesNotAbortStartup() {
        assertThatCode(() -> assertThat(RestFederatorServerApplication.reattachOtelLogAppender(() -> {
            throw new NoClassDefFoundError("ch/qos/logback/classic/LoggerContext");
        })).isFalse()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a failure while starting telemetry is not swallowed")
    void telemetryFailureIsNotSwallowed() {
        assertThatCode(() -> RestFederatorServerApplication.initialiseConfigurationThenTelemetry(
                () -> true,
                () -> {
                    throw new IllegalStateException("telemetry init failed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("telemetry init failed");
    }
}
