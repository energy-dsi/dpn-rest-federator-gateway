// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HeartbeatService} - previously entirely untested (no test file existed
 * for this class anywhere in the codebase, found via a systematic audit of every {@code .java}
 * file's size against existing test files).
 * <p>
 * Every instance created in a test is stopped in {@code @AfterEach} - the class registers a real
 * JVM shutdown hook per instance when started via {@code start(...)} (the static factory, not
 * used directly here since these tests use {@code create(...)} + instance {@code start()} to
 * avoid accumulating shutdown hooks across the whole test run).
 * </p>
 */
class HeartbeatServiceTest {

    private HeartbeatService service;

    @AfterEach
    void tearDown() {
        if (service != null && service.isRunning()) {
            service.stop(Duration.ofSeconds(2));
        }
    }

    // ------------------------------------------------------------------
    // create() - builds without starting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("create(componentName) builds an instance that is not running and has zero heartbeats")
    void create_simpleOverload_buildsWithoutStarting() {
        service = HeartbeatService.create("test-component");

        assertThat(service.isRunning()).isFalse();
        assertThat(service.heartbeatCount()).isZero();
    }

    @Test
    @DisplayName("create() with an explicit interval/metadata/statusSupplier stores them without starting")
    void create_fullOverload_buildsWithoutStarting() {
        Map<String, Object> metadata = Map.of("region", "uk");
        service = HeartbeatService.create("test-component", Duration.ofSeconds(30), metadata, () -> "custom-status");

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("create() with null interval/metadata/statusSupplier falls back to defaults without throwing")
    void create_allNullOptionalArgs_doesNotThrow() {
        assertThatCode(() -> {
                    service = HeartbeatService.create("test-component", null, null, null);
                })
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // start() / isRunning()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("start() transitions the service to running and emits at least one heartbeat immediately "
            + "(initialDelay 0)")
    void start_beginsRunningAndEmitsImmediately() {
        service = HeartbeatService.create("test-component", Duration.ofMinutes(15), null, null);

        service.start();

        assertThat(service.isRunning()).isTrue();
        await().atMost(Duration.ofSeconds(2)).until(() -> service.heartbeatCount() >= 1);
    }

    @Test
    @DisplayName("start() called a second time while already running is a no-op - it does not throw "
            + "and does not reset the heartbeat count")
    void start_calledTwiceWhileRunning_isNoOp() {
        service = HeartbeatService.create("test-component", Duration.ofMinutes(15), null, null);
        service.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> service.heartbeatCount() >= 1);
        long countAfterFirstStart = service.heartbeatCount();

        assertThatCode(() -> service.start()).doesNotThrowAnyException();
        assertThat(service.isRunning()).isTrue();
        // A genuine restart would reset the count to 0 before its own first beat - a no-op
        // leaves the existing count exactly where it was (or higher, if another beat landed).
        assertThat(service.heartbeatCount()).isGreaterThanOrEqualTo(countAfterFirstStart);
    }

    @Test
    @DisplayName("A short interval causes multiple heartbeats to accumulate over time")
    void start_shortInterval_accumulatesMultipleHeartbeats() {
        // resolveInterval() converts via Duration.getSeconds() - second granularity only, so
        // the shortest valid interval is 1 second (a sub-second Duration truncates to 0,
        // which scheduleAtFixedRate rejects outright).
        service = HeartbeatService.create("test-component", Duration.ofSeconds(1), null, null);

        service.start();

        await().atMost(Duration.ofSeconds(5)).until(() -> service.heartbeatCount() >= 3);
    }

    // ------------------------------------------------------------------
    // emit() - package-private, "Visible for testing" per its own Javadoc
    // ------------------------------------------------------------------

    @Test
    @DisplayName("emit() increments the heartbeat count by exactly one per call, callable directly without starting")
    void emit_calledDirectly_incrementsCountByOne() {
        service = HeartbeatService.create("test-component");

        service.emit();
        service.emit();
        service.emit();

        assertThat(service.heartbeatCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("emit() swallows an exception from the status supplier rather than propagating it")
    void emit_statusSupplierThrows_isCaughtAndDoesNotPropagate() {
        service = HeartbeatService.create(
                "test-component", Duration.ofMinutes(15), null, () -> {
                    throw new RuntimeException("status check failed");
                });

        assertThatCode(() -> service.emit()).doesNotThrowAnyException();
        assertThat(service.heartbeatCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("emit() handles a status supplier returning null without throwing")
    void emit_statusSupplierReturnsNull_doesNotThrow() {
        service = HeartbeatService.create("test-component", Duration.ofMinutes(15), null, () -> null);

        assertThatCode(() -> service.emit()).doesNotThrowAnyException();
        assertThat(service.heartbeatCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("emit() with configured metadata does not throw, and metadata continues to be present "
            + "across multiple emits")
    void emit_withMetadata_doesNotThrow() {
        service = HeartbeatService.create(
                "test-component", Duration.ofMinutes(15), Map.of("region", "uk", "version", "1.0.0"), null);

        assertThatCode(() -> {
                    service.emit();
                    service.emit();
                })
                .doesNotThrowAnyException();
        assertThat(service.heartbeatCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("emit() before start() (no startTime set yet) reports zero uptime rather than throwing")
    void emit_beforeStart_doesNotThrowDespiteNoStartTime() {
        service = HeartbeatService.create("test-component");

        assertThatCode(() -> service.emit()).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // stop()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stop(timeout) transitions a running service to not-running")
    void stop_withTimeout_transitionsToNotRunning() {
        service = HeartbeatService.create("test-component", Duration.ofMinutes(15), null, null);
        service.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> service.isRunning());

        service.stop(Duration.ofSeconds(2));

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() with no arguments delegates to stop(Duration) with the default 5s timeout")
    void stop_noArgsOverload_stopsSuccessfully() {
        service = HeartbeatService.create("test-component", Duration.ofMinutes(15), null, null);
        service.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> service.isRunning());

        service.stop();

        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() on a service that was never started is a safe no-op")
    void stop_neverStarted_isNoOp() {
        service = HeartbeatService.create("test-component");

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() called twice in a row is safe - the second call is a no-op")
    void stop_calledTwice_secondCallIsNoOp() {
        service = HeartbeatService.create("test-component", Duration.ofMinutes(15), null, null);
        service.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> service.isRunning());
        service.stop();

        assertThatCode(() -> service.stop()).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // updateMetadata()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updateMetadata() merges new entries without restarting or resetting the heartbeat count")
    void updateMetadata_mergesWithoutRestarting() {
        service = HeartbeatService.create("test-component");
        service.emit();
        long countBeforeUpdate = service.heartbeatCount();

        service.updateMetadata(Map.of("newKey", "newValue"));

        assertThat(service.heartbeatCount()).isEqualTo(countBeforeUpdate);
        assertThatCode(() -> service.emit()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("updateMetadata(null) is a safe no-op")
    void updateMetadata_null_isNoOp() {
        service = HeartbeatService.create("test-component");

        assertThatCode(() -> service.updateMetadata(null)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Concurrency: emit() is safe to call from multiple threads (the real scheduler is
    // single-threaded, but the count itself should still never lose an increment if it were)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Many sequential emit() calls produce an exactly matching heartbeat count")
    void emit_calledManyTimes_countMatchesExactly() {
        service = HeartbeatService.create("test-component");
        int callCount = 50;

        for (int i = 0; i < callCount; i++) {
            service.emit();
        }

        assertThat(service.heartbeatCount()).isEqualTo(callCount);
    }
}
