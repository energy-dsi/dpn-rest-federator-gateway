// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Verifies {@link ComponentTelemetry.LogBridge}: log lines from a library package are exported by the
 * COMPONENT's SDK only (not also by the host's root OTLP appender), stdout output is preserved, and
 * the host's {@code OpenTelemetryAppender.install(...)} cannot steal the component's appender.
 */
class ComponentTelemetryLogBridgeTest {

    private static final String LIB_PACKAGE = "test.bridge.library";
    private static final String COMPONENT = "rest-federator-client-test";

    private final List<String> hostExported = new CopyOnWriteArrayList<>();
    private final List<String> componentExported = new CopyOnWriteArrayList<>();
    private OpenTelemetrySdk hostSdk;
    private OpenTelemetrySdk componentSdk;
    private Logger root;
    private OpenTelemetryAppender hostAppender;
    private ListAppender<ILoggingEvent> console;
    private Logger libLogger;

    private static OpenTelemetrySdk sdkCapturingTo(List<String> sink) {
        LogRecordExporter exporter = new LogRecordExporter() {
            @Override
            public CompletableResultCode export(Collection<LogRecordData> logs) {
                logs.forEach(l -> sink.add(l.getBodyValue() == null ? "" : l.getBodyValue().asString()));
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        return OpenTelemetrySdk.builder()
                .setLoggerProvider(SdkLoggerProvider.builder()
                        .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
                        .build())
                .build();
    }

    @BeforeEach
    void setUp() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        libLogger = context.getLogger(LIB_PACKAGE);

        hostSdk = sdkCapturingTo(hostExported);
        componentSdk = sdkCapturingTo(componentExported);

        console = new ListAppender<>();
        console.setContext(context);
        console.setName("TestConsole");
        console.start();
        root.addAppender(console);

        hostAppender = new OpenTelemetryAppender();
        hostAppender.setName("TestHostOpenTelemetry");
        hostAppender.setContext(context);
        hostAppender.start();
        root.addAppender(hostAppender);
        OpenTelemetryAppender.install(hostSdk);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(hostAppender);
        hostAppender.stop();
        root.detachAppender(console);
        console.stop();
        libLogger.detachAndStopAllAppenders();
        libLogger.setAdditive(true);
        hostSdk.close();
        componentSdk.close();
    }

    @Test
    @DisplayName("library log lines are exported by the component's SDK only; other lines stay with the host")
    void libraryLogs_goToComponent_notHost() {
        assertThat(ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, componentSdk)).isTrue();

        LoggerFactory.getLogger(LIB_PACKAGE + ".RestClient").info("library-line");
        LoggerFactory.getLogger("some.host.App").info("host-line");

        assertThat(componentExported).contains("library-line").doesNotContain("host-line");
        assertThat(hostExported).contains("host-line").doesNotContain("library-line");
    }

    @Test
    @DisplayName("stdout output is unchanged: the library line still reaches the root's other appenders")
    void libraryLogs_stillReachConsole() {
        ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, componentSdk);

        LoggerFactory.getLogger(LIB_PACKAGE + ".RestClient").info("library-line");

        assertThat(console.list).extracting(ILoggingEvent::getFormattedMessage).containsOnlyOnce("library-line");
    }

    @Test
    @DisplayName("the host's OpenTelemetryAppender.install(...) cannot replace the component's SDK")
    void hostInstall_doesNotStealComponentAppender() {
        ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, componentSdk);

        OpenTelemetryAppender.install(hostSdk); // e.g. again after Spring re-initialises logging
        LoggerFactory.getLogger(LIB_PACKAGE + ".RestClient").info("still-component");

        assertThat(componentExported).contains("still-component");
        assertThat(hostExported).doesNotContain("still-component");
    }

    @Test
    @DisplayName("attach is idempotent and a no-op for null / no-op telemetry")
    void attach_isIdempotentAndSafe() {
        assertThat(ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, null)).isFalse();
        assertThat(ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, OpenTelemetry.noop())).isFalse();

        assertThat(ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, componentSdk)).isTrue();
        assertThat(ComponentTelemetry.LogBridge.attach(LIB_PACKAGE, COMPONENT, componentSdk)).isTrue();
        LoggerFactory.getLogger(LIB_PACKAGE + ".RestClient").info("once");

        assertThat(componentExported.stream().filter("once"::equals).count()).isEqualTo(1);
    }
}
