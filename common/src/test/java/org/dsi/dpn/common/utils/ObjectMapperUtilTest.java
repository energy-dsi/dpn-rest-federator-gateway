// SPDX-License-Identifier: Apache-2.0
// © Crown Copyright 2026. National Digital Twin Programme.
package org.dsi.dpn.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObjectMapperUtilTest {

    @Test @DisplayName("getInstance returns a shared, non-null ObjectMapper")
    void singleton() {
        ObjectMapper a = ObjectMapperUtil.getInstance();
        ObjectMapper b = ObjectMapperUtil.getInstance();
        assertThat(a).isNotNull();
        assertThat(b).isSameAs(a);
    }

    @Test @DisplayName("round-trips a simple object")
    void roundTrip() throws Exception {
        ObjectMapper mapper = ObjectMapperUtil.getInstance();
        String json = mapper.writeValueAsString(new Sample("dpn", 42));
        Sample back = mapper.readValue(json, Sample.class);
        assertThat(back.name()).isEqualTo("dpn");
        assertThat(back.value()).isEqualTo(42);
    }

    @Test @DisplayName("ignores unknown properties on deserialisation")
    void ignoresUnknown() throws Exception {
        ObjectMapper mapper = ObjectMapperUtil.getInstance();
        Sample back = mapper.readValue("{\"name\":\"x\",\"value\":1,\"extra\":\"ignored\"}", Sample.class);
        assertThat(back.name()).isEqualTo("x");
    }

    record Sample(String name, int value) {}
}
