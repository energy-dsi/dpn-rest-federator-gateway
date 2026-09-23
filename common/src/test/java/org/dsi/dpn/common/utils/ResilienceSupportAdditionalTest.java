// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Additional unit tests for {@link ResilienceSupport}, covering the {@code retryOn}
 * configuration parsing branches (a real, comma-separated list of exception class names, mixing
 * valid Throwable subclasses with an unknown class name and a non-Throwable class) which
 * {@link ResilienceSupportTest} does not exercise, and the actual retry-triggering path that runs
 * the {@code IntervalFunction} built by {@code getIntervalFunction}.
 */
class ResilienceSupportAdditionalTest {

    private MockedStatic<PropertyUtil> propertyUtilMock;

    @BeforeEach
    void setUp() {
        ResilienceSupport.clearForTests();
        propertyUtilMock = mockStatic(PropertyUtil.class, invocation -> {
            Object[] args = invocation.getArguments();
            if (args.length == 2 && args[1] instanceof String defaultValue) {
                return switch (invocation.getMethod().getName()) {
                    case "getPropertyIntValue" -> Integer.parseInt(defaultValue);
                    case "getPropertyDurationValue" -> java.time.Duration.parse(defaultValue);
                    default -> defaultValue;
                };
            }
            return null;
        });
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue(
                        "management.node.resilience.circuitBreaker.minimumNumberOfCalls", "20"))
                .thenReturn(1000); // keeps the circuit breaker closed regardless of failures below
    }

    @AfterEach
    void tearDown() {
        propertyUtilMock.close();
        ResilienceSupport.clearForTests();
    }

    @Test
    @DisplayName("retryOn configuration containing a mix of a valid Throwable class, an unknown class name, and a "
            + "non-Throwable class only retries on the valid Throwable class")
    void retryOn_mixedValidAndInvalidClassNames_onlyRetriesOnValidThrowable() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(
                        "management.node.resilience.retry.retryOn", "java.lang.RuntimeException"))
                .thenReturn("java.lang.IllegalStateException,not.a.real.ClassName,java.lang.String");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(3);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyDurationValue(
                        "management.node.resilience.retry.initialWait", "PT5S"))
                .thenReturn(java.time.Duration.ofMillis(1));

        AtomicInteger attempts = new AtomicInteger();
        // IllegalStateException is configured as retryable -> should retry up to maxAttempts (3).
        try {
            ResilienceSupport.decorateAndExecute("retry-configured-comp", "op", "t", () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // expected: retries exhausted, original exception rethrown unchanged (not Rebuildable)
        }

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("retryOn configuration that is blank falls back to retrying on RuntimeException")
    void retryOn_blankConfiguration_fallsBackToRuntimeException() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(
                        "management.node.resilience.retry.retryOn", "java.lang.RuntimeException"))
                .thenReturn("   ");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(2);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyDurationValue(
                        "management.node.resilience.retry.initialWait", "PT5S"))
                .thenReturn(java.time.Duration.ofMillis(1));

        AtomicInteger attempts = new AtomicInteger();
        try {
            ResilienceSupport.decorateAndExecute("retry-blank-comp", "op", "t", () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException expected) {
            // expected
        }

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a genuine retry actually invokes the configured IntervalFunction (getIntervalFunction) between "
            + "attempts, exercising its backoff-capping logic")
    void decorateAndExecute_multipleAttempts_exercisesIntervalFunction() {
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyValue(
                        "management.node.resilience.retry.retryOn", "java.lang.RuntimeException"))
                .thenReturn("java.lang.RuntimeException");
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(4);
        // Small initial wait and max backoff so the interval function's doubling-with-cap logic is
        // actually exercised across several attempts within a fast-running test.
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyDurationValue(
                        "management.node.resilience.retry.initialWait", "PT5S"))
                .thenReturn(java.time.Duration.ofMillis(2));
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyDurationValue(
                        "management.node.resilience.retry.maxBackoff", "PT15M"))
                .thenReturn(java.time.Duration.ofMillis(5));

        AtomicInteger attempts = new AtomicInteger();
        String result = ResilienceSupport.decorateAndExecute("interval-fn-comp", "op", "t", () -> {
            if (attempts.incrementAndGet() < 4) {
                throw new RuntimeException("transient failure " + attempts.get());
            }
            return "succeeded-on-attempt-" + attempts.get();
        });

        assertThat(result).isEqualTo("succeeded-on-attempt-4");
        assertThat(attempts.get()).isEqualTo(4);
    }
}
