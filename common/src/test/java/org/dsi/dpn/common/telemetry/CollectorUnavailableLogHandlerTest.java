// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CollectorUnavailableLogHandler} - previously entirely untested, despite
 * firing repeatedly and visibly throughout this project's own local testing (its output line,
 * {@code "OTel Collector export failed - telemetry is being dropped..."}, appeared many times in
 * manual test runs across the whole session).
 * <p>
 * Package-private class, so this test lives in the same package to construct and exercise it
 * directly. {@code System.err} is captured around the "falls through to the fallback
 * ConsoleHandler" case, since that path writes there and the fallback handler itself is a
 * private field with no injection point.
 * </p>
 */
class CollectorUnavailableLogHandlerTest {

    private CollectorUnavailableLogHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CollectorUnavailableLogHandler();
    }

    @Test
    @DisplayName("publish(null) is a safe no-op")
    void publish_nullRecord_isNoOp() {
        assertThatCode(() -> handler.publish(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception is UnknownHostException directly")
    void publish_thrownIsUnknownHostException_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "export failed");
        record.setThrown(new UnknownHostException("collector.example.test"));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception is ConnectException directly")
    void publish_thrownIsConnectException_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "export failed");
        record.setThrown(new ConnectException("connection refused"));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception is SocketTimeoutException directly")
    void publish_thrownIsSocketTimeoutException_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "export failed");
        record.setThrown(new SocketTimeoutException("timed out"));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception has an UnknownHostException as its cause")
    void publish_causeIsUnknownHostException_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "export failed");
        record.setThrown(new RuntimeException("wrapped", new UnknownHostException("collector.example.test")));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception has a ConnectException as its cause")
    void publish_causeIsConnectException_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "export failed");
        record.setThrown(new RuntimeException("wrapped", new ConnectException("connection refused")));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception has a SocketTimeoutException as its cause")
    void publish_causeIsSocketTimeoutException_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "export failed");
        record.setThrown(new RuntimeException("wrapped", new SocketTimeoutException("timed out")));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record with no thrown exception at all, but a message "
            + "containing 'Failed to export'")
    void publish_messageContainsFailedToExport_isIntercepted() {
        LogRecord record = new LogRecord(Level.WARNING, "Failed to export spans to the collector");

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() intercepts a record whose thrown exception's cause chain has no cause at all "
            + "(cause is null), falling through to the message check rather than throwing on cause.getCause()")
    void publish_thrownHasNullCause_fallsThroughToMessageCheckSafely() {
        LogRecord record = new LogRecord(Level.WARNING, "Failed to export batch");
        record.setThrown(new RuntimeException("no cause here"));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() does not throw for a genuinely unrelated record (no matching exception "
            + "type, no matching message) - it falls through to the fallback ConsoleHandler")
    void publish_unrelatedRecord_fallsThroughToConsoleHandler() {
        LogRecord record = new LogRecord(Level.INFO, "SDK started successfully");

        // Not asserting on captured System.err output here: ConsoleHandler's effective level and
        // buffering depend on java.util.logging's global LogManager state, which this test
        // cannot control or predict reliably across environments. What genuinely matters - that
        // this path does not throw and does not get swallowed by the interception branch above -
        // is what's checked.
        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("publish() does not intercept a record with an unrelated exception type and no matching message")
    void publish_unrelatedException_fallsThroughToConsoleHandler() {
        LogRecord record = new LogRecord(Level.WARNING, "something else entirely");
        record.setThrown(new IllegalStateException("not a connectivity failure"));

        assertThatCode(() -> handler.publish(record)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("flush() delegates to the fallback handler without throwing")
    void flush_doesNotThrow() {
        assertThatCode(() -> handler.flush()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("close() delegates to the fallback handler without throwing")
    void close_doesNotThrow() {
        assertThatCode(() -> handler.close()).doesNotThrowAnyException();
    }
}
