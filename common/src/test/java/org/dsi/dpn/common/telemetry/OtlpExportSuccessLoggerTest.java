// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link OtlpExportSuccessLogger}.
 *
 * <p>The actual log output is asserted directly via a Logback {@link ListAppender} attached to
 * this class's own logger - {@code logback-classic} is already a direct dependency of this
 * module (see {@code opentelemetry-logback-appender-1.0}'s own requirement), so this needs no
 * new test dependency. {@link SpanData}, {@link MetricData}, and {@link LogRecordData} are
 * mocked with only {@code getResource()} stubbed, matching this module's existing convention
 * (see {@link OtelBearerTokenSupportTest}) of mocking the narrow surface actually exercised
 * rather than hand-building full SDK data objects.
 */
class OtlpExportSuccessLoggerTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        loggerUnderTest().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        loggerUnderTest().detachAppender(appender);
    }

    private Logger loggerUnderTest() {
        return (Logger) LoggerFactory.getLogger(OtlpExportSuccessLogger.class);
    }

    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ------------------------------------------------------------------
    // Spans
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wrap(SpanExporter): on a successful export, logs once with signal type 'trace', "
            + "the batch's service.name, and the batch size")
    void spanExporter_successfulExport_logsWithServiceNameAndCount() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofSuccess());
        SpanExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        SpanData span = mock(SpanData.class);
        when(span.getResource())
                .thenReturn(Resource.builder().put("service.name", "rest-federator-server").build());

        wrapped.export(List.of(span, span, span));

        assertThat(loggedMessages()).hasSize(1);
        assertThat(loggedMessages().get(0))
                .contains("trace")
                .contains("rest-federator-server")
                .contains("3");
    }

    @Test
    @DisplayName("wrap(SpanExporter): on a failed export, logs nothing")
    void spanExporter_failedExport_logsNothing() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofFailure());
        SpanExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        SpanData span = mock(SpanData.class);
        when(span.getResource()).thenReturn(Resource.builder().put("service.name", "irrelevant").build());

        wrapped.export(List.of(span));

        assertThat(loggedMessages()).isEmpty();
    }

    @Test
    @DisplayName("wrap(SpanExporter): logs on EVERY successful export call, not only the first - "
            + "covers the actual requirement (one confirmation per demo-runner-client run, not "
            + "once ever per gateway process)")
    void spanExporter_multipleSuccessfulExports_logsEveryTime() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofSuccess());
        SpanExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        SpanData span = mock(SpanData.class);
        when(span.getResource()).thenReturn(Resource.builder().put("service.name", "demo-runner-client").build());

        wrapped.export(List.of(span));
        wrapped.export(List.of(span));
        wrapped.export(List.of(span));

        assertThat(loggedMessages()).hasSize(3);
        assertThat(loggedMessages()).allMatch(message -> message.contains("demo-runner-client"));
    }

    @Test
    @DisplayName("wrap(SpanExporter): falls back to 'unknown' when the batch's Resource has no "
            + "service.name attribute, rather than throwing or logging a null")
    void spanExporter_resourceWithoutServiceName_logsUnknownFallback() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofSuccess());
        SpanExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        SpanData span = mock(SpanData.class);
        when(span.getResource()).thenReturn(Resource.builder().put("some.other.attribute", "x").build());

        wrapped.export(List.of(span));

        assertThat(loggedMessages().get(0)).contains("unknown");
    }

    @Test
    @DisplayName("wrap(SpanExporter): empty batch does not throw, falls back to 'unknown' "
            + "service.name since there is no first element to read a Resource from")
    void spanExporter_emptyBatch_doesNotThrowAndLogsUnknown() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofSuccess());
        SpanExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        wrapped.export(List.of());

        assertThat(loggedMessages()).hasSize(1);
        assertThat(loggedMessages().get(0)).contains("unknown").contains("batch size=0");
    }

    @Test
    @DisplayName("wrap(SpanExporter): flush() and shutdown() are delegated unchanged")
    void spanExporter_flushAndShutdown_delegate() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.flush()).thenReturn(CompletableResultCode.ofSuccess());
        when(delegate.shutdown()).thenReturn(CompletableResultCode.ofSuccess());
        SpanExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        assertThat(wrapped.flush().isSuccess()).isTrue();
        assertThat(wrapped.shutdown().isSuccess()).isTrue();
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wrap(MetricExporter): on a successful export, logs once with signal type "
            + "'metrics', the batch's service.name, and the batch size")
    void metricExporter_successfulExport_logsWithServiceNameAndCount() {
        MetricExporter delegate = mock(MetricExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofSuccess());
        MetricExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        MetricData metric = mock(MetricData.class);
        when(metric.getResource())
                .thenReturn(Resource.builder().put("service.name", "rest-federator-server").build());

        wrapped.export(List.of(metric, metric));

        assertThat(loggedMessages()).hasSize(1);
        assertThat(loggedMessages().get(0))
                .contains("metrics")
                .contains("rest-federator-server")
                .contains("2");
    }

    @Test
    @DisplayName("wrap(MetricExporter): on a failed export, logs nothing")
    void metricExporter_failedExport_logsNothing() {
        MetricExporter delegate = mock(MetricExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofFailure());
        MetricExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        MetricData metric = mock(MetricData.class);
        when(metric.getResource()).thenReturn(Resource.builder().put("service.name", "irrelevant").build());

        wrapped.export(List.of(metric));

        assertThat(loggedMessages()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Logs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wrap(LogRecordExporter): on a successful export, logs once with signal type "
            + "'logs', the batch's service.name, and the batch size")
    void logRecordExporter_successfulExport_logsWithServiceNameAndCount() {
        LogRecordExporter delegate = mock(LogRecordExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofSuccess());
        LogRecordExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        LogRecordData logRecord = mock(LogRecordData.class);
        when(logRecord.getResource())
                .thenReturn(Resource.builder().put("service.name", "demo-runner-client").build());

        wrapped.export(List.of(logRecord, logRecord, logRecord, logRecord));

        assertThat(loggedMessages()).hasSize(1);
        assertThat(loggedMessages().get(0))
                .contains("logs")
                .contains("demo-runner-client")
                .contains("4");
    }

    @Test
    @DisplayName("wrap(LogRecordExporter): on a failed export, logs nothing")
    void logRecordExporter_failedExport_logsNothing() {
        LogRecordExporter delegate = mock(LogRecordExporter.class);
        when(delegate.export(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableResultCode.ofFailure());
        LogRecordExporter wrapped = OtlpExportSuccessLogger.wrap(delegate);

        LogRecordData logRecord = mock(LogRecordData.class);
        when(logRecord.getResource()).thenReturn(Resource.builder().put("service.name", "irrelevant").build());

        wrapped.export(List.of(logRecord));

        assertThat(loggedMessages()).isEmpty();
    }
}
