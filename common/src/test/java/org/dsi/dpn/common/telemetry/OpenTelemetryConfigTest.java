// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenTelemetryConfig} - previously entirely untested (part of
 * {@code common.telemetry}'s 44% coverage).
 * <p>
 * {@code initialize()} caches its result in a private static field, checked only via
 * {@code openTelemetry != null} - reset via reflection before and after each test so one test's
 * result cannot leak into another. Whether {@code initialize()} actually succeeds in exporting
 * via OTLP or falls back to {@link OpenTelemetry#noop()} depends on real network/environment
 * conditions this test cannot control portably, so what's tested here is the class's core,
 * environment-independent contract: it never throws, and it never returns {@code null} - plus
 * {@code resolveComponentName()}'s precedence logic, which is a pure function with no
 * environment I/O beyond reading system properties this test itself controls.
 * </p>
 */
class OpenTelemetryConfigTest {

    @BeforeEach
    void resetCachedState() throws Exception {
        setCachedOpenTelemetry(null);
        System.clearProperty("otel.service.name");
        System.clearProperty("otel.resource.attributes");
    }

    @AfterEach
    void tearDown() throws Exception {
        setCachedOpenTelemetry(null);
        System.clearProperty("otel.service.name");
        System.clearProperty("otel.resource.attributes");
    }

    private void setCachedOpenTelemetry(OpenTelemetry value) throws Exception {
        Field field = OpenTelemetryConfig.class.getDeclaredField("openTelemetry");
        field.setAccessible(true);
        field.set(null, value);
    }

    private String resolveComponentName() throws Exception {
        Method method = OpenTelemetryConfig.class.getDeclaredMethod("resolveComponentName");
        method.setAccessible(true);
        return (String) method.invoke(null);
    }

    // ------------------------------------------------------------------
    // Core contract: initialize()/get() never throw, never return null
    // ------------------------------------------------------------------

    @Test
    @DisplayName("initialize() never throws and never returns null, regardless of whether a real "
            + "OTLP collector is actually reachable")
    void initialize_neverThrowsAndNeverReturnsNull() {
        OpenTelemetry result = OpenTelemetryConfig.initialize();

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("initialize() is idempotent - a second call returns the exact same cached instance")
    void initialize_calledTwice_returnsSameCachedInstance() {
        OpenTelemetry first = OpenTelemetryConfig.initialize();
        OpenTelemetry second = OpenTelemetryConfig.initialize();

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("get() lazily initialises and returns a non-null instance when called before initialize()")
    void get_calledBeforeInitialize_lazilyInitialises() {
        OpenTelemetry result = OpenTelemetryConfig.get();

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("get() returns the same cached instance initialize() already produced")
    void get_calledAfterInitialize_returnsSameCachedInstance() {
        OpenTelemetry initialised = OpenTelemetryConfig.initialize();
        OpenTelemetry result = OpenTelemetryConfig.get();

        assertThat(result).isSameAs(initialised);
    }

    // ------------------------------------------------------------------
    // resolveComponentName() precedence: sys prop > env var > OTEL_RESOURCE_ATTRIBUTES > default
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveComponentName() prefers the otel.service.name system property above everything else")
    void resolveComponentName_systemPropertySet_takesPrecedence() throws Exception {
        System.setProperty("otel.service.name", "from-system-property");

        assertThat(resolveComponentName()).isEqualTo("from-system-property");
    }

    @Test
    @DisplayName("resolveComponentName() reads service.name out of otel.resource.attributes "
            + "when no system property or env var is set")
    void resolveComponentName_resourceAttributes_extractsServiceName() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OTEL_SERVICE_NAME") == null, "Skipping: OTEL_SERVICE_NAME is set in this shell");
        System.setProperty("otel.resource.attributes", "deployment.environment=dev,service.name=from-resource-attrs");

        assertThat(resolveComponentName()).isEqualTo("from-resource-attrs");
    }

    @Test
    @DisplayName("resolveComponentName() falls back to 'dpn-rest-federator' when nothing is configured")
    void resolveComponentName_nothingConfigured_fallsBackToDefault() throws Exception {
        assumeNoRelevantEnvVarsSet();

        assertThat(resolveComponentName()).isEqualTo("dpn-rest-federator");
    }

    @Test
    @DisplayName("resolveComponentName() ignores a blank otel.service.name system property "
            + "and falls through to the next source")
    void resolveComponentName_blankSystemProperty_fallsThroughToDefault() throws Exception {
        assumeNoRelevantEnvVarsSet();
        System.setProperty("otel.service.name", "   ");

        assertThat(resolveComponentName()).isEqualTo("dpn-rest-federator");
    }

    @Test
    @DisplayName("resolveComponentName() ignores otel.resource.attributes entries that are not "
            + "the service.name key")
    void resolveComponentName_resourceAttributesWithoutServiceName_fallsBackToDefault() throws Exception {
        assumeNoRelevantEnvVarsSet();
        System.setProperty("otel.resource.attributes", "deployment.environment=dev,region=uk");

        assertThat(resolveComponentName()).isEqualTo("dpn-rest-federator");
    }

    /**
     * Skips the calling test rather than failing it if OTEL_SERVICE_NAME or
     * OTEL_RESOURCE_ATTRIBUTES happen to already be set in the shell running the tests - these
     * tests assert the no-configuration default, which only holds when both are genuinely
     * absent, a fact this test cannot control.
     */
    private void assumeNoRelevantEnvVarsSet() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OTEL_SERVICE_NAME") == null && System.getenv("OTEL_RESOURCE_ATTRIBUTES") == null,
                "Skipping: OTEL_SERVICE_NAME or OTEL_RESOURCE_ATTRIBUTES is set in this shell");
    }
}
