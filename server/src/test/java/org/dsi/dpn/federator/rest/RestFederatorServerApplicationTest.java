// SPDX-License-Identifier: Apache-2.0
package org.dsi.dpn.federator.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for RestFederatorServerApplication.
 * Covers the application class instantiation without starting Spring context.
 */
class RestFederatorServerApplicationTest {

    @Test @DisplayName("RestFederatorServerApplication class can be instantiated")
    void applicationClassExists() {
        RestFederatorServerApplication app = new RestFederatorServerApplication();
        assertThat(app).isNotNull();
    }
}
