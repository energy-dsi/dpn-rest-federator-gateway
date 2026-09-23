// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link ConfigKeys} - a pure constants holder with no existing test file. The
 * only executable behaviour is the private constructor guard, plus the constant values
 * themselves.
 */
class ConfigKeysTest {

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("ConfigKeys cannot be instantiated")
    void constructor_throwsUnsupportedOperationException() throws Exception {
        var constructor = ConfigKeys.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Constants have their expected values")
    void constants_haveExpectedValues() {
        assertThat(ConfigKeys.ENV_SERVER_PROPS).isEqualTo("FEDERATOR_SERVER_PROPERTIES");
        assertThat(ConfigKeys.SERVER_PROPERTIES).isEqualTo("server.properties");
        assertThat(ConfigKeys.COMMON_CONFIG_PROPERTIES).isEqualTo("common.configuration");
    }
}
