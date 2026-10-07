// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest.framework.client.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.logs.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Severity of the per-call "rest_client_request" event: only real faults are ERROR. */
class RestClientSeverityTest {

    @Test
    @DisplayName("success is INFO")
    void success_isInfo() {
        assertThat(RestClient.severityFor("success", 200)).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("4xx (e.g. expected 404 not found, 409 conflict) is WARN, not a failure")
    void clientErrors_areWarn() {
        assertThat(RestClient.severityFor("failure", 404)).isEqualTo(Severity.WARN);
        assertThat(RestClient.severityFor("failure", 409)).isEqualTo(Severity.WARN);
        assertThat(RestClient.severityFor("failure", 400)).isEqualTo(Severity.WARN);
        assertThat(RestClient.severityFor("failure", 499)).isEqualTo(Severity.WARN);
    }

    @Test
    @DisplayName("5xx and exceptions (no HTTP status) are ERROR")
    void serverErrorsAndExceptions_areError() {
        assertThat(RestClient.severityFor("failure", 500)).isEqualTo(Severity.ERROR);
        assertThat(RestClient.severityFor("failure", 503)).isEqualTo(Severity.ERROR);
        assertThat(RestClient.severityFor("error", 0)).isEqualTo(Severity.ERROR);
    }
}
