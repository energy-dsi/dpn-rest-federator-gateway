// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.lang.reflect.Constructor;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OtlpTruststoreSupport} - previously entirely untested (part of
 * {@code common.telemetry}'s 44% coverage).
 * <p>
 * {@code resolve()} reads real environment variables directly ({@code System.getenv}), which
 * cannot be safely or portably mocked from within a plain JUnit test across platforms (Windows
 * and Linux implement the underlying environment map differently) - so this test exercises
 * {@code resolve()} only for its documented fallback behaviour (returns {@code null} when these
 * non-standard variables are absent, which is the case in any environment that hasn't explicitly
 * set them), and constructs an instance directly via reflection (bypassing the private
 * constructor) to test the three {@code customize*Exporter} methods, which have no environment
 * dependency at all.
 * </p>
 */
class OtlpTruststoreSupportTest {

    @Test
    @DisplayName("resolve() returns null when OTEL_EXPORTER_OTLP_TRUSTSTORE_PATH/_PASSWORD are unset "
            + "(the documented fallback - both are non-standard, environment-specific variables "
            + "that are absent in any environment that hasn't explicitly configured them)")
    void resolve_envVarsUnset_returnsNull() {
        // Skips rather than fails if this exact variable happens to already be set in the
        // shell running this test (plausible in this project specifically, since manual local
        // testing throughout development repeatedly exports this exact variable) - this test's
        // purpose is confirming the documented fallback, not asserting a particular shell state.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(OtlpTruststoreSupport.TRUSTSTORE_PATH_ENV) == null,
                "Skipping: " + OtlpTruststoreSupport.TRUSTSTORE_PATH_ENV + " is set in this shell");

        OtlpTruststoreSupport result = OtlpTruststoreSupport.resolve();

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("customizeSpanExporter() passes through any exporter that is not an OtlpHttpSpanExporter")
    void customizeSpanExporter_nonOtlpExporter_returnsUnchanged() throws Exception {
        OtlpTruststoreSupport support = newInstance();
        SpanExporter nonOtlpExporter = mock(SpanExporter.class);

        SpanExporter result = support.customizeSpanExporter(nonOtlpExporter);

        assertThat(result).isSameAs(nonOtlpExporter);
    }

    @Test
    @DisplayName("customizeSpanExporter() rebuilds an OtlpHttpSpanExporter with the resolved SSLContext/trust manager")
    void customizeSpanExporter_otlpExporter_rebuildsWithSslContext() throws Exception {
        OtlpTruststoreSupport support = newInstance();
        OtlpHttpSpanExporter original = OtlpHttpSpanExporter.builder().build();

        SpanExporter result = support.customizeSpanExporter(original);

        // toBuilder().setSslContext(...).build() always produces a new, distinct instance, even
        // when the effective configuration ends up equal - confirms the customizer genuinely
        // rebuilt the exporter rather than returning the original.
        assertThat(result).isInstanceOf(OtlpHttpSpanExporter.class).isNotSameAs(original);
    }

    @Test
    @DisplayName("customizeMetricExporter() passes through any exporter that is not an OtlpHttpMetricExporter")
    void customizeMetricExporter_nonOtlpExporter_returnsUnchanged() throws Exception {
        OtlpTruststoreSupport support = newInstance();
        MetricExporter nonOtlpExporter = mock(MetricExporter.class);

        MetricExporter result = support.customizeMetricExporter(nonOtlpExporter);

        assertThat(result).isSameAs(nonOtlpExporter);
    }

    @Test
    @DisplayName("customizeLogRecordExporter() passes through any exporter that is not an OtlpHttpLogRecordExporter")
    void customizeLogRecordExporter_nonOtlpExporter_returnsUnchanged() throws Exception {
        OtlpTruststoreSupport support = newInstance();
        LogRecordExporter nonOtlpExporter = mock(LogRecordExporter.class);

        LogRecordExporter result = support.customizeLogRecordExporter(nonOtlpExporter);

        assertThat(result).isSameAs(nonOtlpExporter);
    }

    /**
     * Constructs a real {@link OtlpTruststoreSupport} via its private constructor, with a real,
     * empty in-memory trust manager/SSLContext pair - avoids depending on {@code resolve()}'s
     * environment-variable reading entirely, while still exercising the class's real
     * constructor and field wiring rather than a mock of the class itself.
     */
    private OtlpTruststoreSupport newInstance() throws Exception {
        KeyStore emptyTrustStore = KeyStore.getInstance("JKS");
        emptyTrustStore.load(null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(emptyTrustStore);
        TrustManager[] trustManagers = tmf.getTrustManagers();
        X509TrustManager x509TrustManager = (X509TrustManager) trustManagers[0];

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, null);

        Constructor<OtlpTruststoreSupport> constructor =
                OtlpTruststoreSupport.class.getDeclaredConstructor(SSLContext.class, X509TrustManager.class);
        constructor.setAccessible(true);
        return constructor.newInstance(sslContext, x509TrustManager);
    }
}
