// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.dsi.dpn.common.exception.RebuildableRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Unit tests for {@link ResilienceSupport}. {@link PropertyUtil} is mocked via
 * {@link org.mockito.Mockito#mockStatic} so registry construction never needs a real properties
 * file; unstubbed defaults simply parse the caller-supplied default value, matching the pattern
 * used in {@code IdpTokenServiceClientSecretImplTest}.
 */
class ResilienceSupportTest {

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
        // Force a single attempt / fast retry so these tests run quickly.
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyIntValue("management.node.resilience.retry.maxAttempts", "10"))
                .thenReturn(2);
        propertyUtilMock
                .when(() -> PropertyUtil.getPropertyDurationValue(
                        "management.node.resilience.retry.initialWait", "PT5S"))
                .thenReturn(java.time.Duration.ofMillis(1));
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
    @DisplayName("getRetry(null) throws NullPointerException")
    void getRetry_nullName_throws() {
        assertThatThrownBy(() -> ResilienceSupport.getRetry(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getCircuitBreaker(null) throws NullPointerException")
    void getCircuitBreaker_nullName_throws() {
        assertThatThrownBy(() -> ResilienceSupport.getCircuitBreaker(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getRetry/getCircuitBreaker return the same named instance across calls (registry caching)")
    void getRetryAndCircuitBreaker_sameName_returnsSameInstance() {
        assertThat(ResilienceSupport.getRetry("component-a")).isSameAs(ResilienceSupport.getRetry("component-a"));
        assertThat(ResilienceSupport.getCircuitBreaker("component-a"))
                .isSameAs(ResilienceSupport.getCircuitBreaker("component-a"));
    }

    @Test
    @DisplayName("decorateAndExecute returns the supplier's value on success")
    void decorateAndExecute_success_returnsValue() {
        String result = ResilienceSupport.decorateAndExecute("comp", "op", "target-1", () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("decorateAndExecute rethrows a plain RuntimeException unchanged (not RebuildableRuntimeException)")
    void decorateAndExecute_plainRuntimeException_isRethrownUnchanged() {
        RuntimeException original = new RuntimeException("boom");

        assertThatThrownBy(() -> ResilienceSupport.decorateAndExecute("comp-plain", "op", "target-2", () -> {
                    throw original;
                }))
                .isSameAs(original);
    }

    @Test
    @DisplayName("decorateAndExecute enriches and rethrows a RebuildableRuntimeException with a detailed message")
    void decorateAndExecute_rebuildableException_isEnrichedWithFailureDetails() {
        assertThatThrownBy(() -> ResilienceSupport.decorateAndExecute("comp-rebuild", "fetchThing", "target-99", () -> {
                    throw new FakeRebuildableException("original message", new SocketTimeoutException("timed out"));
                }))
                .isInstanceOf(FakeRebuildableException.class)
                .hasMessageContaining("timeout while calling comp-rebuild")
                .hasMessageContaining("target-99");
    }

    @Test
    @DisplayName("buildFailureMessage classifies a SocketTimeoutException root cause as a timeout")
    void buildFailureMessage_socketTimeout_classifiesAsTimeout() {
        String message = ResilienceSupport.buildFailureMessage(
                "base", new SocketTimeoutException("timed out"), "comp", "op", "target-1");

        assertThat(message).contains("timeout while calling comp").contains("target-1");
    }

    @Test
    @DisplayName("buildFailureMessage classifies an HttpTimeoutException root cause as a timeout")
    void buildFailureMessage_httpTimeout_classifiesAsTimeout() {
        String message =
                ResilienceSupport.buildFailureMessage("base", new HttpTimeoutException("timed out"), "comp", "op", null);

        assertThat(message).contains("timeout while calling comp");
        // targetId null/blank -> no " for <target>" suffix
        assertThat(message).doesNotContain(" for ");
    }

    @Test
    @DisplayName("buildFailureMessage classifies a plain IOException root cause as an I/O error")
    void buildFailureMessage_ioException_classifiesAsIoError() {
        String message = ResilienceSupport.buildFailureMessage(
                "base", new IOException("disk full"), "comp", "op", "target-x");

        assertThat(message).contains("I/O error while calling comp");
    }

    @Test
    @DisplayName("buildFailureMessage classifies a JedisException root cause as a redis cache failure")
    void buildFailureMessage_jedisException_classifiesAsRedisCacheFailure() {
        String message = ResilienceSupport.buildFailureMessage(
                "base", new JedisConnectionException("connection refused"), "comp", "op", "target-y");

        assertThat(message).contains("redis cache failure");
    }

    @Test
    @DisplayName("buildFailureMessage falls back to a generic message for an unclassified exception")
    void buildFailureMessage_unclassifiedException_usesGenericMessage() {
        String message =
                ResilienceSupport.buildFailureMessage("base", new IllegalStateException("weird"), "comp", "doStuff", "t");

        assertThat(message).contains("unexpected failure during doStuff");
    }

    @Test
    @DisplayName("buildFailureMessage walks to the deepest cause, not just the immediate one")
    void buildFailureMessage_nestedCause_usesRootCause() {
        Exception wrapped = new RuntimeException("outer", new RuntimeException("middle", new IOException("innermost")));

        String message = ResilienceSupport.buildFailureMessage("base", wrapped, "comp", "op", "t");

        assertThat(message).contains("I/O error while calling comp");
    }

    @Test
    @DisplayName("buildFailureMessage omits the ' for <targetId>' suffix when targetId is blank")
    void buildFailureMessage_blankTargetId_omitsSuffix() {
        String message = ResilienceSupport.buildFailureMessage("base", new IllegalStateException("x"), "comp", "op", "   ");

        assertThat(message).doesNotContain(" for ");
    }

    @Test
    @DisplayName("clearForTests resets both registries so a later call rebuilds fresh instances")
    void clearForTests_resetsRegistries() {
        var beforeRetry = ResilienceSupport.getRetry("comp-clear");
        var beforeCb = ResilienceSupport.getCircuitBreaker("comp-clear");

        ResilienceSupport.clearForTests();

        var afterRetry = ResilienceSupport.getRetry("comp-clear");
        var afterCb = ResilienceSupport.getCircuitBreaker("comp-clear");

        assertThat(afterRetry).isNotSameAs(beforeRetry);
        assertThat(afterCb).isNotSameAs(beforeCb);
    }

    /** A minimal concrete {@link RebuildableRuntimeException} for exercising the enrich-and-rethrow path. */
    private static final class FakeRebuildableException extends RebuildableRuntimeException {
        FakeRebuildableException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public RebuildableRuntimeException rebuild(String enrichedMessage, Throwable cause) {
            return new FakeRebuildableException(enrichedMessage, cause);
        }
    }
}
