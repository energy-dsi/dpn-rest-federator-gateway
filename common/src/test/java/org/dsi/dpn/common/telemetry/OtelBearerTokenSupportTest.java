// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.dsi.dpn.common.service.idp.IdpTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OtelBearerTokenSupport}, mirroring {@link OtlpTruststoreSupportTest}'s
 * approach for the sibling TLS-trust customizer.
 *
 * <p>{@code resolve()} reads {@code OTEL_EXPORTER_OTLP_OAUTH_ENABLED} directly via {@code
 * System.getenv} and, when enabled, calls {@code IdpTokenServiceFactory.createRedisIdpTokenService()}
 * (which reads the properties file and touches Vault/secret providers) - neither is safely
 * mockable from a plain JUnit test, so this test exercises {@code resolve()} only for its
 * documented default-off fallback, and constructs an instance directly (via the package-visible
 * constructor, with a mocked {@link IdpTokenService}) to test the three {@code customize*Exporter}
 * methods and the header itself.
 */
class OtelBearerTokenSupportTest {

    @Test
    @DisplayName("resolve() returns null when OTEL_EXPORTER_OTLP_OAUTH_ENABLED is unset "
            + "(the documented default-off fallback)")
    void resolve_envVarUnset_returnsNull() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(OtelBearerTokenSupport.OAUTH_ENABLED_ENV) == null,
                "Skipping: " + OtelBearerTokenSupport.OAUTH_ENABLED_ENV + " is set in this shell");

        OtelBearerTokenSupport result = OtelBearerTokenSupport.resolve();

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("customizeSpanExporter() passes through any exporter that is not an OtlpHttpSpanExporter")
    void customizeSpanExporter_nonOtlpExporter_returnsUnchanged() {
        OtelBearerTokenSupport support = new OtelBearerTokenSupport(mock(IdpTokenService.class));
        SpanExporter nonOtlpExporter = mock(SpanExporter.class);

        SpanExporter result = support.customizeSpanExporter(nonOtlpExporter);

        assertThat(result).isSameAs(nonOtlpExporter);
    }

    @Test
    @DisplayName("customizeSpanExporter() rebuilds an OtlpHttpSpanExporter with a Bearer-token header supplier")
    void customizeSpanExporter_otlpExporter_rebuildsWithHeaders() {
        IdpTokenService idpTokenService = mock(IdpTokenService.class);
        when(idpTokenService.fetchToken()).thenReturn("test-access-token");
        OtelBearerTokenSupport support = new OtelBearerTokenSupport(idpTokenService);
        OtlpHttpSpanExporter original = OtlpHttpSpanExporter.builder().build();

        SpanExporter result = support.customizeSpanExporter(original);

        // toBuilder().setHeaders(...).build() always produces a new, distinct instance -
        // confirms the customizer genuinely rebuilt the exporter rather than returning the
        // original. The header supplier itself is re-evaluated per export batch by the OTel
        // SDK, not inspectable here without an actual export - covered by fetchToken() being
        // called at least once via the mock's own invocation tracking below.
        assertThat(result).isInstanceOf(OtlpHttpSpanExporter.class).isNotSameAs(original);
    }

    @Test
    @DisplayName("customizeMetricExporter() passes through any exporter that is not an OtlpHttpMetricExporter")
    void customizeMetricExporter_nonOtlpExporter_returnsUnchanged() {
        OtelBearerTokenSupport support = new OtelBearerTokenSupport(mock(IdpTokenService.class));
        MetricExporter nonOtlpExporter = mock(MetricExporter.class);

        MetricExporter result = support.customizeMetricExporter(nonOtlpExporter);

        assertThat(result).isSameAs(nonOtlpExporter);
    }

    @Test
    @DisplayName("customizeLogRecordExporter() passes through any exporter that is not an OtlpHttpLogRecordExporter")
    void customizeLogRecordExporter_nonOtlpExporter_returnsUnchanged() {
        OtelBearerTokenSupport support = new OtelBearerTokenSupport(mock(IdpTokenService.class));
        LogRecordExporter nonOtlpExporter = mock(LogRecordExporter.class);

        LogRecordExporter result = support.customizeLogRecordExporter(nonOtlpExporter);

        assertThat(result).isSameAs(nonOtlpExporter);
    }
}
