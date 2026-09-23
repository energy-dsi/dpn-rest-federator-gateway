// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.federator.rest.framework.server.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BackendProperties} - previously entirely untested.
 */
class BackendPropertiesTest {

    @Test
    @DisplayName("Constructor strips a single trailing slash from baseUrl")
    void constructor_baseUrlWithTrailingSlash_stripsIt() {
        BackendProperties props = new BackendProperties("http://backend.example.test/", "key");

        assertThat(props.baseUrl()).isEqualTo("http://backend.example.test");
    }

    @Test
    @DisplayName("Constructor leaves baseUrl unchanged when it has no trailing slash")
    void constructor_baseUrlWithoutTrailingSlash_leavesUnchanged() {
        BackendProperties props = new BackendProperties("http://backend.example.test", "key");

        assertThat(props.baseUrl()).isEqualTo("http://backend.example.test");
    }

    @Test
    @DisplayName("Constructor does not throw when baseUrl is null")
    void constructor_nullBaseUrl_doesNotThrow() {
        BackendProperties props = new BackendProperties(null, "key");

        assertThat(props.baseUrl()).isNull();
    }

    @Test
    @DisplayName("hasApiKey() returns true when apiKey is a genuine, non-blank value")
    void hasApiKey_presentValue_returnsTrue() {
        BackendProperties props = new BackendProperties("http://backend.example.test", "real-secret");

        assertThat(props.hasApiKey()).isTrue();
    }

    @Test
    @DisplayName("hasApiKey() returns false when apiKey is null")
    void hasApiKey_nullValue_returnsFalse() {
        BackendProperties props = new BackendProperties("http://backend.example.test", null);

        assertThat(props.hasApiKey()).isFalse();
    }

    @Test
    @DisplayName("hasApiKey() returns false when apiKey is blank")
    void hasApiKey_blankValue_returnsFalse() {
        BackendProperties props = new BackendProperties("http://backend.example.test", "   ");

        assertThat(props.hasApiKey()).isFalse();
    }

    @Test
    @DisplayName("hasApiKey() returns false when apiKey is an empty string")
    void hasApiKey_emptyValue_returnsFalse() {
        BackendProperties props = new BackendProperties("http://backend.example.test", "");

        assertThat(props.hasApiKey()).isFalse();
    }

    @Test
    @DisplayName("API_KEY_HEADER constant has the exact expected header name")
    void apiKeyHeaderConstant_hasExpectedName() {
        assertThat(BackendProperties.API_KEY_HEADER).isEqualTo("X-Backend-Api-Key");
    }
}
